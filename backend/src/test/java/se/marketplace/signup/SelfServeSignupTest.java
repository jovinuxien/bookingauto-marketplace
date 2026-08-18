package se.marketplace.signup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import se.marketplace.notifications.SignupNotifier;

/**
 * The two claims this module is built on.
 *
 * <p><strong>Nothing exists until the address is proved.</strong> Registering
 * writes a row and sends a link, and touches neither Cal nor Stripe. That is
 * the difference between an endpoint that is safe to expose to the internet and
 * one that provisions connected accounts for whoever scripts it, and it is
 * invisible in the response — a caller cannot tell, which is why a test has to.
 *
 * <p><strong>It says the same thing either way.</strong> An address that
 * already has an account is answered identically to one that does not. The
 * login endpoint refuses to reveal which salons are on the platform; a signup
 * form that answered "taken" would give away exactly what that protects.
 */
class SelfServeSignupTest {

	private static final String IP = "198.51.100.7";

	private FakeProvisioning provisioning;
	private RecordingNotifier notifier;
	private FakeRepository repository;
	private CountingLimiter limiter;
	private SelfServeSignup signup;

	@BeforeEach
	void setUp() {
		provisioning = new FakeProvisioning();
		notifier = new RecordingNotifier();
		repository = new FakeRepository();
		limiter = new CountingLimiter();

		signup = new SelfServeSignup(repository, limiter, provisioning, notifier,
			new TransactionTemplate(new DirectTransactionManager()));

		ReflectionTestUtils.setField(signup, "publicUrl", "https://boka.example.se");
		ReflectionTestUtils.setField(signup, "tokenTtlHours", 24);
		ReflectionTestUtils.setField(signup, "perIpPerHour", 10);
		ReflectionTestUtils.setField(signup, "perEmailPerHour", 3);
		ReflectionTestUtils.setField(signup, "verifyPerIpPerHour", 30);
	}

	private SelfServeSignup.Registration form() {
		return new SelfServeSignup.Registration("Klipp & Co", "anna@klippco.se",
			"ett-riktigt-langt-losenord", "Storgatan 1", "112 34", "Stockholm");
	}

	private SelfServeSignup.Outcome register() {
		return signup.register(form(), IP);
	}

	/**
	 * The token, read out of the link that was emailed.
	 *
	 * <p>Which is the only place it exists. Nothing stores it — the row holds a
	 * hash — so a test that could reach for it another way would be testing a
	 * system where the token had leaked.
	 */
	private String emailedToken() {
		String url = notifier.verifyUrls.get(notifier.verifyUrls.size() - 1);
		return url.substring(url.indexOf("token=") + "token=".length());
	}

	// -------------------------------------------------- nothing until verified --

	@Test
	@DisplayName("registering creates no provider, no Cal account and no Stripe account")
	void registrationProvisionsNothing() {
		assertThat(register()).isInstanceOf(SelfServeSignup.Accepted.class);

		assertThat(provisioning.provisioned).isEmpty();
		assertThat(provisioning.loginsCreated).isEmpty();

		// One row and one email is the entire effect.
		assertThat(repository.created).hasSize(1);
		assertThat(notifier.kinds()).containsExactly("verification");
	}

	@Test
	@DisplayName("the link is what creates the salon")
	void verificationProvisions() {
		register();

		var result = signup.verify(emailedToken(), IP);

		assertThat(result).isInstanceOf(SelfServeSignup.Ready.class);
		assertThat(provisioning.provisioned).hasSize(1);
		assertThat(provisioning.provisioned.get(0).slug()).isEqualTo("klipp-och-co");
		assertThat(provisioning.loginsCreated).containsExactly("anna@klippco.se");
		assertThat(repository.completed).containsExactly(1L);
		assertThat(notifier.kinds()).containsExactly("verification", "welcome");
	}

	@Test
	@DisplayName("the token is stored hashed, never as sent")
	void tokenStoredHashed() {
		register();

		// A leaked backup of this table must not let the reader verify addresses
		// they do not control.
		assertThat(repository.created.get(0).tokenHash()).isNotEqualTo(emailedToken());
		assertThat(repository.created.get(0).tokenHash())
			.isEqualTo(SignupTokens.hash(emailedToken()));
	}

	@Test
	@DisplayName("the password is stored hashed too, and never reused for Cal")
	void passwordsAreSeparate() {
		register();
		signup.verify(emailedToken(), IP);

		assertThat(repository.created.get(0).passwordHash()).isEqualTo("hash:ett-riktigt-langt-losenord");

		// Reusing the console password on a third-party system would make one
		// breach into two. ADR 0010 already commits the salon to two logins.
		String calPassword = provisioning.provisioned.get(0).calPassword();
		assertThat(calPassword).isNotEqualTo("ett-riktigt-langt-losenord");
		assertThat(calPassword).hasSize(20);
	}

	// ------------------------------------------------------------ no enumeration --

	@Test
	@DisplayName("an address that already has an account is answered identically")
	void knownAddressIsIndistinguishable() {
		provisioning.existingLogins.add("anna@klippco.se");

		SelfServeSignup.Outcome outcome = register();

		assertThat(outcome).isEqualTo(new SelfServeSignup.Accepted());
		// No row, no verification link -- and nothing in the response that says so.
		assertThat(repository.created).isEmpty();
		assertThat(notifier.kinds()).containsExactly("already-registered");
	}

	@Test
	@DisplayName("the difference is told only to the mailbox that owns the address")
	void knownAddressIsToldByEmail() {
		provisioning.existingLogins.add("anna@klippco.se");
		register();

		assertThat(notifier.recipients).containsExactly("anna@klippco.se");
	}

	@Test
	@DisplayName("an address over its own limit is also answered identically")
	void throttledAddressIsIndistinguishable() {
		limiter.limits.put("signup:email:anna@klippco.se", 0);

		SelfServeSignup.Outcome outcome = register();

		// Not 429. "This address has been used a lot lately" is a thing we do
		// not tell strangers about other people's addresses.
		assertThat(outcome).isEqualTo(new SelfServeSignup.Accepted());
		assertThat(repository.created).isEmpty();
		assertThat(notifier.kinds()).isEmpty();
	}

	// ------------------------------------------------------------- rate limiting --

	@Test
	@DisplayName("a source over its limit is refused before anything is written")
	void ipLimit() {
		limiter.limits.put("signup:ip:" + IP, 0);

		assertThat(register()).isInstanceOf(SelfServeSignup.Throttled.class);
		assertThat(repository.created).isEmpty();
		assertThat(notifier.kinds()).isEmpty();
	}

	@Test
	@DisplayName("the source is counted before the form is validated")
	void ipLimitCountedOnGarbage() {
		// What this defends against is a script, and a script does not send
		// valid forms. Counting only valid ones would leave the endpoint open
		// to anything that sends nonsense quickly.
		signup.register(new SelfServeSignup.Registration(null, null, null, null, null, null), IP);

		assertThat(limiter.counted).contains("signup:ip:" + IP);
	}

	@Test
	@DisplayName("verification is limited too")
	void verifyLimit() {
		register();
		limiter.limits.put("signup:verify:" + IP, 0);

		assertThat(signup.verify(emailedToken(), IP))
			.isInstanceOf(SelfServeSignup.Throttled.class);
		assertThat(provisioning.provisioned).isEmpty();
	}

	// ------------------------------------------------------------------ the link --

	@Test
	@DisplayName("a second click on the same link does nothing")
	void secondClickIsANoOp() {
		register();
		signup.verify(emailedToken(), IP);

		var again = signup.verify(emailedToken(), IP);

		// One salon, not two. Provisioning takes seconds of HTTP, so two clicks
		// a moment apart is a real sequence rather than a theoretical one.
		assertThat(provisioning.provisioned).hasSize(1);
		assertThat(again).isInstanceOf(SelfServeSignup.LinkUnusable.class);
		assertThat(((SelfServeSignup.LinkUnusable) again).state()).isEqualTo("completed");
	}

	@Test
	@DisplayName("an unknown token says so without pretending to know more")
	void unknownToken() {
		var result = signup.verify("not-a-token", IP);

		assertThat(result).isInstanceOf(SelfServeSignup.LinkUnusable.class);
		assertThat(((SelfServeSignup.LinkUnusable) result).state()).isEqualTo("unknown");
	}

	@Test
	@DisplayName("registering again supersedes the link already in flight")
	void resendSupersedes() {
		register();
		String first = emailedToken();

		register();
		String second = emailedToken();

		// Someone who did not receive the first email fills the form in again,
		// and the link that arrives second has to be the one that works.
		assertThat(second).isNotEqualTo(first);

		var stale = signup.verify(first, IP);
		assertThat(stale).isInstanceOf(SelfServeSignup.LinkUnusable.class);
		assertThat(((SelfServeSignup.LinkUnusable) stale).state()).isEqualTo("superseded");

		assertThat(signup.verify(second, IP)).isInstanceOf(SelfServeSignup.Ready.class);
	}

	// --------------------------------------------------------- provisioning fails --

	@Test
	@DisplayName("a provisioning failure leaves the link usable and says so")
	void transientFailureIsRetryable() {
		register();
		provisioning.failWith = new IllegalStateException("stripe timed out");

		var result = signup.verify(emailedToken(), IP);

		assertThat(result).isInstanceOf(SelfServeSignup.ProvisioningFailed.class);
		assertThat(((SelfServeSignup.ProvisioningFailed) result).retryable()).isTrue();
		// The address was proved by the click and does not become unproved
		// because Stripe timed out.
		assertThat(repository.failed).containsKey(1L);
		assertThat(repository.stateOf(SignupTokens.hash(emailedToken()))).isEqualTo("failed");
	}

	@Test
	@DisplayName("a failed signup can be finished by clicking the link again")
	void failureIsResumable() {
		register();
		provisioning.failWith = new IllegalStateException("stripe timed out");
		signup.verify(emailedToken(), IP);

		provisioning.failWith = null;
		var second = signup.verify(emailedToken(), IP);

		// The alternative to this is a support ticket for every transient
		// outage, which is not a self-serve flow.
		assertThat(second).isInstanceOf(SelfServeSignup.Ready.class);
		assertThat(repository.completed).containsExactly(1L);
	}

	@Test
	@DisplayName("a taken Cal username asks for a different name rather than a retry")
	void nameTakenIsNotRetryable() {
		register();
		provisioning.failWith = new SalonProvisioning.NameTaken("taken");

		var result = signup.verify(emailedToken(), IP);

		assertThat(((SelfServeSignup.ProvisioningFailed) result).retryable()).isFalse();
	}

	@Test
	@DisplayName("a salon that exists but has no login is reported, not reported as success")
	void loginFailureIsLoud() {
		register();
		provisioning.failLogin = true;

		var result = signup.verify(emailedToken(), IP);

		// The worst case in this flow: the salon exists in Cal and in Stripe and
		// nobody can sign in to it.
		assertThat(result).isInstanceOf(SelfServeSignup.ProvisioningFailed.class);
		assertThat(repository.completed).isEmpty();
		assertThat(repository.failed.get(1L)).contains("login not created");
	}

	@Test
	@DisplayName("a resumed signup is not shown a Cal password it never received")
	void resumedSignupWithholdsThePassword() {
		register();
		provisioning.calAccountCreated = false;

		var ready = (SelfServeSignup.Ready) signup.verify(emailedToken(), IP);

		// Showing someone a freshly generated password for an account that
		// already has a different one is worse than showing none.
		assertThat(ready.calPassword()).isNull();
		assertThat(notifier.welcomePasswords).containsExactly((String) null);
	}

	// ------------------------------------------------------------------ the form --

	@Test
	@DisplayName("field problems come back per field")
	void validation() {
		var outcome = signup.register(new SelfServeSignup.Registration(
			"A", "not-an-email", "kort", "", "12345678", ""), IP);

		Map<String, String> problems = ((SelfServeSignup.Rejected) outcome).problems();

		assertThat(problems).containsOnlyKeys(
			"salonName", "email", "password", "city", "addressLine", "postalCode");
		assertThat(repository.created).isEmpty();
	}

	@Test
	@DisplayName("a postal code is five digits, with or without the space")
	void postalCodes() {
		assertThat(problemsFor("112 34")).doesNotContainKey("postalCode");
		assertThat(problemsFor("11234")).doesNotContainKey("postalCode");
		assertThat(problemsFor("1123")).containsKey("postalCode");
		assertThat(problemsFor("abcde")).containsKey("postalCode");
	}

	@Test
	@DisplayName("a name whose slug is taken is suffixed, not refused")
	void slugCollision() {
		provisioning.takenSlugs.add("klipp-och-co");

		register();

		assertThat(repository.created.get(0).slug()).isEqualTo("klipp-och-co-2");
	}

	@Test
	@DisplayName("a name colliding ten times asks for a better one")
	void slugExhaustion() {
		for (int i = 0; i < 10; i++) {
			provisioning.takenSlugs.add(SignupSlugs.candidate("klipp-och-co", i));
		}

		var outcome = register();

		assertThat(((SelfServeSignup.Rejected) outcome).problems()).containsKey("salonName");
	}

	private Map<String, String> problemsFor(String postalCode) {
		SelfServeSignup.Registration form = form();
		var outcome = signup.register(new SelfServeSignup.Registration(form.salonName(),
			form.email(), form.password(), form.addressLine(), postalCode, form.city()), IP);

		return outcome instanceof SelfServeSignup.Rejected rejected ? rejected.problems() : Map.of();
	}

	// ---------------------------------------------------------------- the fakes --

	private static final class FakeProvisioning implements SalonProvisioning {

		private final Set<String> existingLogins = new HashSet<>();
		private final Set<String> takenSlugs = new HashSet<>();
		private final List<NewSalon> provisioned = new ArrayList<>();
		private final List<String> loginsCreated = new ArrayList<>();
		private RuntimeException failWith;
		private boolean failLogin;
		private boolean calAccountCreated = true;

		@Override
		public boolean loginExists(String email) {
			return existingLogins.contains(email);
		}

		@Override
		public String hashPassword(String rawPassword) {
			return "hash:" + rawPassword;
		}

		@Override
		public boolean slugAvailable(String slug) {
			return !takenSlugs.contains(slug);
		}

		@Override
		public Provisioned provision(NewSalon salon) {
			if (failWith != null) {
				throw failWith;
			}
			provisioned.add(salon);
			return new Provisioned(42L, salon.slug(), calAccountCreated,
				"https://connect.stripe.com/setup/acct_1");
		}

		@Override
		public void createLogin(long providerId, String email, String passwordHash,
			String displayName) {
			if (failLogin) {
				throw new IllegalStateException("duplicate key");
			}
			loginsCreated.add(email);
		}

	}

	private static final class RecordingNotifier implements SignupNotifier {

		private final List<String> kinds = new ArrayList<>();
		private final List<String> recipients = new ArrayList<>();
		private final List<String> welcomePasswords = new ArrayList<>();
		private final List<String> verifyUrls = new ArrayList<>();

		@Override
		public void verificationRequested(String email, String salonName, String verifyUrl,
			String dedupeKey) {
			kinds.add("verification");
			recipients.add(email);
			verifyUrls.add(verifyUrl);
		}

		@Override
		public void alreadyRegistered(String email, String dedupeKey) {
			kinds.add("already-registered");
			recipients.add(email);
		}

		@Override
		public void welcome(String email, String salonName, String calUsername, String calPassword,
			String kycUrl, String dedupeKey) {
			kinds.add("welcome");
			recipients.add(email);
			welcomePasswords.add(calPassword);
		}

		private List<String> kinds() {
			return kinds;
		}

	}

	/** In-memory, and faithful about the one thing that matters: claiming is atomic. */
	private static final class FakeRepository extends SignupRepository {

		private final List<New> created = new ArrayList<>();
		private final Map<String, String> states = new HashMap<>();
		private final List<String> superseded = new ArrayList<>();
		private final List<Long> completed = new ArrayList<>();
		private final Map<Long, String> failed = new HashMap<>();

		private FakeRepository() {
			super(null);
		}

		@Override
		void supersedePending(String email) {
			created.stream()
				.filter(signup -> signup.email().equalsIgnoreCase(email))
				.filter(signup -> "pending".equals(states.get(signup.tokenHash())))
				.forEach(signup -> states.put(signup.tokenHash(), "superseded"));
			superseded.add(email);
		}

		@Override
		boolean slugPending(String slug) {
			return created.stream()
				.anyMatch(signup -> signup.slug().equals(slug)
					&& "pending".equals(states.get(signup.tokenHash())));
		}

		@Override
		long create(New signup) {
			created.add(signup);
			states.put(signup.tokenHash(), "pending");
			return created.size();
		}

		@Override
		Optional<Claimed> claim(String tokenHash) {
			String state = states.get(tokenHash);

			if (!"pending".equals(state) && !"failed".equals(state)) {
				return Optional.empty();
			}

			states.put(tokenHash, "verifying");

			for (int i = 0; i < created.size(); i++) {
				New signup = created.get(i);
				if (signup.tokenHash().equals(tokenHash)) {
					return Optional.of(new Claimed(i + 1, signup.email(), signup.salonName(),
						signup.slug(), signup.addressLine(), signup.postalCode(), signup.city(),
						signup.passwordHash()));
				}
			}

			return Optional.empty();
		}

		@Override
		String stateOf(String tokenHash) {
			return states.getOrDefault(tokenHash, "unknown");
		}

		@Override
		void markCompleted(long id, long providerId) {
			completed.add(id);
			states.put(created.get((int) id - 1).tokenHash(), "completed");
		}

		@Override
		void markFailed(long id, String failure) {
			failed.put(id, failure);
			states.put(created.get((int) id - 1).tokenHash(), "failed");
		}

	}

	/**
	 * Counts calls and refuses buckets set to a limit of zero. Faithful to the
	 * real one in the way that matters here: it counts before it answers.
	 */
	private static final class CountingLimiter extends RateLimiter {

		private final Map<String, Integer> limits = new HashMap<>();
		private final List<String> counted = new ArrayList<>();

		private CountingLimiter() {
			super(null);
		}

		@Override
		boolean allow(String bucket, int limit, java.time.Duration window) {
			counted.add(bucket);
			return limits.getOrDefault(bucket, Integer.MAX_VALUE) > 0;
		}

	}

	/** Runs the callback, and nothing else. There is no database here to roll back. */
	private static final class DirectTransactionManager implements PlatformTransactionManager {

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}

	}

}
