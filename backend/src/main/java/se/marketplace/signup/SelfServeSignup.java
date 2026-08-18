package se.marketplace.signup;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import se.marketplace.notifications.SignupNotifier;

/**
 * A salon registering itself, in two halves separated by an email.
 *
 * <p><strong>The ordering is the design.</strong> Registration writes one row
 * and sends one link. It does not create a provider, a Cal account or a Stripe
 * connected account, because at that point nobody has shown they own the
 * address — and an endpoint that provisions on unverified input is an endpoint
 * that provisions for whoever wants to script it.
 *
 * <p><strong>And it says the same thing either way.</strong> An address that
 * already has an account gets the identical response and a different email. The
 * login endpoint already refuses to reveal which salons exist; a signup form
 * that answers "taken" would give away exactly what that refusal protects.
 */
@Service
public class SelfServeSignup {

	private static final Logger log = LoggerFactory.getLogger(SelfServeSignup.class);

	private static final Duration HOUR = Duration.ofHours(1);

	// Deliberately loose. Anything stricter rejects addresses that work -- the
	// grammar for a real address is far wider than people expect -- and the
	// only proof that an address is deliverable is delivering to it, which is
	// what the whole verification step is.
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

	// Five digits, with or without the space Swedes write. Checked because a
	// salon nobody can find is a salon that will not sell anything, and a
	// postal code is the one field a typo makes silently useless.
	private static final Pattern POSTAL_CODE = Pattern.compile("^\\d{3}\\s?\\d{2}$");

	private static final DateTimeFormatter HOUR_STAMP =
		DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);

	private final SignupRepository repository;
	private final RateLimiter limiter;
	private final SalonProvisioning provisioning;
	private final SignupNotifier notifier;
	private final TransactionTemplate transactions;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	@Value("${marketplace.signup.token-ttl-hours:24}")
	private int tokenTtlHours;

	@Value("${marketplace.signup.per-ip-per-hour:10}")
	private int perIpPerHour;

	@Value("${marketplace.signup.per-email-per-hour:3}")
	private int perEmailPerHour;

	@Value("${marketplace.signup.verify-per-ip-per-hour:30}")
	private int verifyPerIpPerHour;

	SelfServeSignup(SignupRepository repository, RateLimiter limiter,
		SalonProvisioning provisioning, SignupNotifier notifier,
		TransactionTemplate transactions) {

		this.repository = repository;
		this.limiter = limiter;
		this.provisioning = provisioning;
		this.notifier = notifier;
		this.transactions = transactions;
	}

	/**
	 * Half one: take the details, prove the address.
	 *
	 * <p>Transactional as a whole, so the verification message and the row it
	 * refers to are written together. That is the outbox guarantee: if the
	 * signup exists, the email it is owed exists too, and a crash between them
	 * is not a state this can reach.
	 */
	public Outcome register(Registration request, String clientIp) {
		// Counted before the form is validated. What this limit defends against
		// is a script, and a script does not send valid forms. The allowance is
		// generous enough that a person mistyping a postal code four times is
		// not locked out for an hour.
		if (!limiter.allow("signup:ip:" + clientIp, perIpPerHour, HOUR)) {
			return new Throttled();
		}

		Map<String, String> problems = validate(request);

		if (!problems.isEmpty()) {
			return new Rejected(problems);
		}

		String email = request.email().trim();

		if (!limiter.allow("signup:email:" + email.toLowerCase(Locale.ROOT),
			perEmailPerHour, HOUR)) {
			// Same answer as success. A different one would say "this address
			// has been used a lot lately", which is a thing we do not tell
			// strangers about other people's addresses.
			log.info("signup throttled for an address that had already asked several times");
			return new Accepted();
		}

		if (provisioning.loginExists(email)) {
			// The only place the difference is told, and it is told to the
			// mailbox that owns the address rather than to whoever filled in the
			// form. Deduped per hour so a script cannot use it to send mail.
			notifier.alreadyRegistered(email,
				"signup_exists:" + email.toLowerCase(Locale.ROOT) + ":" + HOUR_STAMP.format(Instant.now()));
			return new Accepted();
		}

		String slug = firstFreeSlug(SignupSlugs.of(request.salonName()));

		if (slug == null) {
			return new Rejected(Map.of("salonName",
				"Namnet är upptaget. Prova att lägga till orten, till exempel «Klipp & Co Solna»."));
		}

		return transactions.execute(status -> {
			repository.supersedePending(email);

			String token = SignupTokens.issue();
			long id = repository.create(new SignupRepository.New(
				email, request.salonName().trim(), slug,
				trimmed(request.addressLine()), trimmed(request.postalCode()),
				trimmed(request.city()), provisioning.hashPassword(request.password()),
				SignupTokens.hash(token),
				Instant.now().plus(Duration.ofHours(tokenTtlHours))));

			notifier.verificationRequested(email, request.salonName().trim(),
				publicUrl + "/verifiera?token=" + token, "signup_verification:" + id);

			log.info("signup {} registered as {}, awaiting verification", id, slug);
			return new Accepted();
		});
	}

	/**
	 * Half two: the link was clicked, so build the salon.
	 *
	 * <p>Not transactional, and it must not be. Provisioning is two HTTP calls
	 * to systems that are not ours and are not fast, and holding a database
	 * transaction open across them turns a slow Stripe into a database problem —
	 * the same reason the outbox dispatcher keeps its batches small.
	 *
	 * <p>So the boundaries are drawn where they can be: claiming the token is
	 * one statement, provisioning is outside any transaction, and recording the
	 * result is another. The state between them is {@code verifying}, which is
	 * what makes a second click on the same link do nothing.
	 */
	public Verification verify(String token, String clientIp) {
		if (!limiter.allow("signup:verify:" + clientIp, verifyPerIpPerHour, HOUR)) {
			return new Throttled();
		}

		String tokenHash = SignupTokens.hash(token);
		SignupRepository.Claimed claimed = repository.claim(tokenHash).orElse(null);

		if (claimed == null) {
			return new LinkUnusable(repository.stateOf(tokenHash));
		}

		String calPassword = SignupTokens.calPassword();
		SalonProvisioning.Provisioned provisioned;

		try {
			provisioned = provisioning.provision(new SalonProvisioning.NewSalon(
				claimed.slug(), claimed.salonName(), claimed.city(),
				claimed.addressLine(), claimed.postalCode(), claimed.email(), calPassword));
		}
		catch (SalonProvisioning.NameTaken e) {
			repository.markFailed(claimed.id(), e.getMessage());
			log.warn("signup {} could not take the cal username {}", claimed.id(), claimed.slug());
			return new ProvisioningFailed(
				"Namnet är tyvärr upptaget hos vår kalenderleverantör. Registrera dig igen med "
					+ "ett något annorlunda namn, så löser det sig.", false);
		}
		catch (RuntimeException e) {
			// Cal or Stripe was unreachable, or refused. The address is proved
			// either way and the link stays usable, so the honest thing to say
			// is "try again shortly" rather than "something went wrong".
			repository.markFailed(claimed.id(), e.toString());
			log.error("signup {} failed to provision", claimed.id(), e);
			return new ProvisioningFailed(
				"Vi kunde inte slutföra registreringen just nu. Klicka på länken i mejlet igen "
					+ "om en stund — den fungerar fortfarande.", true);
		}

		try {
			transactions.executeWithoutResult(status -> {
				provisioning.createLogin(provisioned.providerId(), claimed.email(),
					claimed.passwordHash(), claimed.salonName());
				repository.markCompleted(claimed.id(), provisioned.providerId());

				notifier.welcome(claimed.email(), claimed.salonName(), provisioned.calUsername(),
					provisioned.calAccountCreated() ? calPassword : null,
					provisioned.kycUrl(), "signup_welcome:" + provisioned.providerId());
			});
		}
		catch (RuntimeException e) {
			// The salon now exists in Cal and Stripe and has no way to sign in.
			// Loud, because nothing else will notice and the person is looking
			// at a screen right now.
			repository.markFailed(claimed.id(), "login not created: " + e);
			log.error("signup {} provisioned provider {} but could not create its login",
				claimed.id(), provisioned.providerId(), e);
			return new ProvisioningFailed(
				"Din salong är skapad men inloggningen blev inte klar. Vi hör av oss.", false);
		}

		log.info("signup {} completed as provider {}", claimed.id(), provisioned.providerId());

		return new Ready(claimed.salonName(), claimed.slug(), provisioned.calUsername(),
			provisioned.calAccountCreated() ? calPassword : null, provisioned.kycUrl());
	}

	/**
	 * The first form of the name nobody has taken.
	 *
	 * <p>Checks live providers and registrations in flight, because a name held
	 * by someone halfway through verifying is a name that will collide in an
	 * hour. Ten is a lot of salons with one name; past that the person is better
	 * served by being asked for a more specific one than by being handed
	 * {@code klipp-och-co-11}.
	 */
	private String firstFreeSlug(String base) {
		if (!SignupSlugs.usable(base)) {
			return null;
		}

		for (int attempt = 0; attempt < 10; attempt++) {
			String candidate = SignupSlugs.candidate(base, attempt);

			if (provisioning.slugAvailable(candidate) && !repository.slugPending(candidate)) {
				return candidate;
			}
		}

		return null;
	}

	private Map<String, String> validate(Registration request) {
		Map<String, String> problems = new LinkedHashMap<>();

		if (blank(request.salonName()) || request.salonName().trim().length() < 2) {
			problems.put("salonName", "Skriv salongens namn.");
		}
		else if (request.salonName().trim().length() > 80) {
			problems.put("salonName", "Namnet får vara högst 80 tecken.");
		}
		else if (!SignupSlugs.usable(SignupSlugs.of(request.salonName()))) {
			// A name that folds to nothing usable -- all punctuation, or one of
			// the reserved words. Saying which is not worth a paragraph; asking
			// for a different name is.
			problems.put("salonName", "Det namnet går tyvärr inte att använda som webbadress.");
		}

		if (blank(request.email()) || !EMAIL.matcher(request.email().trim()).matches()) {
			problems.put("email", "Skriv en giltig e-postadress.");
		}

		if (blank(request.password()) || request.password().length() < 10) {
			// Length only. Composition rules push people towards Password1! and
			// buy less than four more characters would.
			problems.put("password", "Lösenordet måste vara minst 10 tecken.");
		}
		else if (request.email() != null
			&& request.password().equalsIgnoreCase(request.email().trim())) {
			problems.put("password", "Lösenordet får inte vara samma som e-postadressen.");
		}

		if (blank(request.city())) {
			problems.put("city", "Skriv orten salongen ligger i.");
		}

		if (blank(request.addressLine())) {
			problems.put("addressLine", "Skriv gatuadressen.");
		}

		if (blank(request.postalCode())
			|| !POSTAL_CODE.matcher(request.postalCode().trim()).matches()) {
			problems.put("postalCode", "Skriv postnumret som fem siffror.");
		}

		return problems;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String trimmed(String value) {
		return value == null ? null : value.trim();
	}

	/** What the salon typed. */
	public record Registration(
		String salonName,
		String email,
		String password,
		String addressLine,
		String postalCode,
		String city
	) {}

	public sealed interface Outcome permits Accepted, Rejected, Throttled {}

	/**
	 * Taken. Carries nothing on purpose — not the slug, not whether the address
	 * was new — because everything it could carry is something the caller has
	 * not earned the right to know.
	 */
	public record Accepted() implements Outcome {}

	/** @param problems field name to message, for the form to render in place */
	public record Rejected(Map<String, String> problems) implements Outcome {}

	public record Throttled() implements Outcome, Verification {}

	public sealed interface Verification permits Ready, LinkUnusable, ProvisioningFailed, Throttled {}

	/**
	 * @param calPassword null when the Cal account already existed, which is
	 *        what a resumed signup looks like. Shown once and never stored
	 */
	public record Ready(
		String salonName,
		String slug,
		String calUsername,
		String calPassword,
		String kycUrl
	) implements Verification {}

	/** @param state expired, superseded, verifying, completed or unknown */
	public record LinkUnusable(String state) implements Verification {}

	/** @param retryable whether clicking the same link again could work */
	public record ProvisioningFailed(String message, boolean retryable) implements Verification {}

}
