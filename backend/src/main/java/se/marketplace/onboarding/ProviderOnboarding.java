package se.marketplace.onboarding;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.onboarding.OnboardingRepository.Provider;
import se.marketplace.payments.StripeConnectPort;
import se.marketplace.sync.CalProvisioningPort;
import se.marketplace.categories.Categories;
import se.marketplace.categories.Category;

/**
 * Onboarding a salon.
 *
 * <p>Assembles two things that are not ours and do not happen at the same speed:
 * something to sell, in Cal, and somewhere to be paid, in Stripe. KYC takes days
 * and can be rejected; setting up services takes a salon owner an afternoon. So
 * this is a state machine that a provider moves through, and the interesting
 * state is the one where they stopped.
 *
 * <p><strong>The invariant.</strong> A provider is not sellable until it can be
 * paid <em>and</em> has something to sell. Both halves matter: a listing with no
 * services refuses every booking, and an active provider without payouts takes a
 * customer's money with nowhere to send it. The second is much the worse, and
 * the database refuses it independently of this class.
 *
 * <p><strong>Why the flow is shaped like this.</strong> Creating a Cal user is
 * public; creating that user's schedule and event types is not — those endpoints
 * need a paid Cal licence (ADR 0008). So the salon builds its services in Cal's
 * own UI and we import them. That is a worse flow than one we control end to
 * end, and it is the honest one available.
 */
@Service
public class ProviderOnboarding {

	private static final Logger log = LoggerFactory.getLogger(ProviderOnboarding.class);

	private final OnboardingRepository repository;
	private final CalProvisioningPort cal;
	private final StripeConnectPort connect;
	private final Categories categories;

	@Value("${marketplace.onboarding.country:SE}")
	private String country;

	@Value("${marketplace.onboarding.return-url:http://localhost:3000/onboarding/complete}")
	private String returnUrl;

	@Value("${marketplace.onboarding.refresh-url:http://localhost:3000/onboarding/refresh}")
	private String refreshUrl;

	/**
	 * Where a service goes when its name says nothing we recognise.
	 *
	 * <p>A real category rather than a null or an "other", because
	 * {@code category_slug} is what every search filters on and a service in no
	 * category is a service nobody can find.
	 *
	 * <p>The fallback behind the fallback. A provider that said what it sells
	 * at signup has its own default and this is never consulted for it; this
	 * is for providers created before the question was asked, and for an
	 * operator who did not answer it. ADR 0013 named the single configured
	 * default as a watch item; ADR 0015 is where it stopped being tolerable.
	 */
	@Value("${marketplace.onboarding.default-category:har}")
	private String defaultCategory;

	ProviderOnboarding(OnboardingRepository repository, CalProvisioningPort cal,
		StripeConnectPort connect, Categories categories) {
		this.repository = repository;
		this.cal = cal;
		this.connect = connect;
		this.categories = categories;
	}

	/**
	 * Step 1. Creates the provider, its Cal account and its Stripe account.
	 *
	 * <p>Ordered so that the reversible parts come first and nothing is left
	 * dangling if a later step fails: our row, then Cal, then Stripe. A provider
	 * with a Cal account and no Stripe account is a resumable state; a Stripe
	 * account with no provider row is an orphan nobody will ever look at.
	 *
	 * <p><strong>And it is now actually resumable.</strong> Each step is skipped
	 * if its result already exists, so calling this again after Stripe was
	 * briefly unreachable continues from where it stopped rather than building a
	 * second half-finished salon and then colliding on the Cal username. That
	 * mattered less when an operator ran this by hand and could look; it matters
	 * a great deal now that a salon signing up itself is the caller, because the
	 * alternative to a retry is a support ticket for every transient failure.
	 *
	 * <p>Resuming is restricted to a provider that never became sellable. Re-running
	 * this against a live salon would be a different and much worse thing.
	 */
	public Onboarded start(NewProvider request) {
		Provider provider = repository.findBySlug(request.slug())
			.map(ProviderOnboarding::mustBeResumable)
			.orElse(null);

		long providerId = provider != null
			? provider.id()
			: repository.create(request.slug(), request.name(), request.city(),
				request.addressLine(), request.postalCode(), request.email(),
				request.defaultCategory(), request.longitude(), request.latitude());

		String calUsername = request.slug();
		boolean calAccountCreated = false;

		if (provider == null || provider.calUserId() == null) {
			try {
				var user = cal.createUser(new CalProvisioningPort.NewCalUser(
					calUsername, request.email(), request.calPassword()));
				repository.recordCalUser(providerId, user.id(), user.username());
				calAccountCreated = true;
			}
			catch (CalProvisioningPort.CalUserExists e) {
				// Almost always a salon onboarding a second time. Not an error, but
				// not something to paper over either: linking silently would attach
				// us to an account we have not verified they control.
				log.warn("cal user {} already exists for provider {}", calUsername, providerId);
				throw new AlreadyOnCal(calUsername);
			}
		}

		String accountId = provider == null ? null : provider.stripeAccountId();

		if (accountId == null) {
			accountId = connect.createAccount(new StripeConnectPort.NewAccount(
				providerId, request.name(), request.email(), country)).accountId();
			repository.recordStripeAccount(providerId, accountId);
		}

		return new Onboarded(providerId, calUsername, accountId,
			connect.onboardingLink(accountId, returnUrl, refreshUrl), calAccountCreated);
	}

	/**
	 * Whether a name is still free.
	 *
	 * <p>Checked before anything is created rather than discovered from a unique
	 * violation, because the collision a self-serve form actually produces is two
	 * salons with the same name — and the second of them deserves to be told
	 * while they are still looking at the form.
	 */
	public boolean slugAvailable(String slug) {
		return repository.findBySlug(slug).isEmpty();
	}

	/**
	 * @throws AlreadyOnboarded for a provider that got all the way through. Half
	 *         finished is resumable; finished is not, and quietly re-running the
	 *         steps against a salon that is already selling would at best mint a
	 *         second Stripe account for it
	 */
	private static Provider mustBeResumable(Provider provider) {
		if ("ready".equals(provider.onboardingState()) || "active".equals(provider.status())) {
			throw new AlreadyOnboarded(provider.slug());
		}
		return provider;
	}

	/**
	 * Step 2. A fresh KYC link.
	 *
	 * <p>Generated on demand rather than stored, because Stripe's links expire
	 * quickly. Anything that caches one will hand an expired link to the salon
	 * that came back the next day, which is exactly when they need it to work.
	 */
	public String onboardingLink(long providerId) {
		Provider provider = require(providerId);

		if (provider.stripeAccountId() == null) {
			throw new IllegalStateException("provider " + providerId + " has no stripe account");
		}

		return connect.onboardingLink(provider.stripeAccountId(), returnUrl, refreshUrl);
	}

	/** True when the provider exists. The plan applies to new quotes only. */
	public boolean setPlan(long providerId, String plan) {
		return repository.setPlan(providerId, plan) > 0;
	}

	/**
	 * Step 3. Imports what the salon set up in Cal.
	 *
	 * <p>Event types that cannot be sold safely are skipped rather than imported
	 * and hoped for. An event type requiring confirmation without
	 * {@code requiresConfirmationWillBlockSlot} produces a reservation that holds
	 * nothing — we would charge for a slot another customer can still take, and
	 * nothing in the response says so. Reporting them back is the point: the
	 * salon has to fix them, and can only do that if told which.
	 */
	public ImportResult importServices(long providerId) {
		Provider provider = require(providerId);

		if (provider.calUserId() == null) {
			throw new IllegalStateException("provider " + providerId + " has no cal user");
		}

		List<String> imported = new ArrayList<>();
		List<String> skipped = new ArrayList<>();

		for (var eventType : cal.eventTypesOf(provider.calUserId())) {
			if (!eventType.safeToSell()) {
				skipped.add(eventType.title() + " (requires confirmation but does not hold the slot)");
				continue;
			}

			// Classified from the title rather than defaulted. Every service this
			// system had ever imported was 'har', because this line used to write
			// the default unconditionally -- which is why /massage/{city} and
			// /hudvard/{city} could not exist. See ADR 0013.
			String category = categories.classify(eventType.title())
				.map(Category::slug)
				.orElse(provider.defaultCategory() != null ? provider.defaultCategory() : defaultCategory);

			repository.importService(providerId, eventType.id(), eventType.title(),
				category, eventType.lengthMinutes(),
				eventType.priceMinor(), currencyOf(eventType));
			imported.add(eventType.title());
		}

		boolean activated = repository.activate(providerId) > 0;

		log.info("provider {}: imported {}, skipped {}, active={}",
			providerId, imported.size(), skipped.size(), activated);

		return new ImportResult(imported, skipped, activated);
	}

	/**
	 * Stripe told us the account changed. Called from the webhook.
	 *
	 * <p>Payability is re-read here rather than remembered from onboarding. An
	 * account can be restricted long after it was approved — a document expires,
	 * a review fails — and the first sign is otherwise a failed transfer, which
	 * happens after we have already taken the customer's money.
	 */
	public void accountUpdated(String stripeAccountId) {
		Provider provider = repository.findByStripeAccount(stripeAccountId).orElse(null);

		if (provider == null) {
			log.info("account.updated for unknown account {}", stripeAccountId);
			return;
		}

		var status = connect.status(stripeAccountId);
		repository.recordPayability(provider.id(), status.sellable(), status.disabledReason());

		if (status.sellable()) {
			if (repository.activate(provider.id()) > 0) {
				log.info("provider {} is now sellable", provider.id());
			}
		}
		else if ("active".equals(provider.status())) {
			// Suspended immediately. Continuing to sell for a salon Stripe will
			// not pay means every further booking is money we cannot forward.
			log.warn("provider {} is no longer payable ({}); suspending",
				provider.id(), status.disabledReason());
			repository.deactivate(provider.id(), status.disabledReason());
		}
	}

	private Provider require(long providerId) {
		return repository.find(providerId)
			.orElseThrow(() -> new IllegalArgumentException("no such provider: " + providerId));
	}

	/** Cal stores currency lowercase and sometimes blank; the marketplace wants ISO. */
	private static String currencyOf(CalProvisioningPort.CalEventType eventType) {
		String currency = eventType.currency();
		return currency == null || currency.isBlank() ? "SEK" : currency.toUpperCase();
	}

	public record NewProvider(
		String slug,
		String name,
		String city,
		String addressLine,
		String postalCode,
		String email,
		String calPassword,
		/** Where unmatched services go. Null means the configured default. */
		String defaultCategory,
		Double longitude,
		Double latitude
	) {}

	/**
	 * @param calAccountCreated false when the Cal account already existed and
	 *        this call resumed onboarding rather than starting it. The caller
	 *        needs to know: it decides whether there is a new Cal password to
	 *        pass on, and telling someone their password is one they have never
	 *        seen is worse than telling them nothing
	 */
	public record Onboarded(
		long providerId,
		String calUsername,
		String stripeAccountId,
		String kycUrl,
		boolean calAccountCreated
	) {}

	/**
	 * @param skipped services Cal has that we will not sell, and why. Returned
	 *        rather than logged: only the salon can fix them.
	 */
	public record ImportResult(List<String> imported, List<String> skipped, boolean activated) {}

	public static class AlreadyOnCal extends RuntimeException {
		public AlreadyOnCal(String username) {
			super("cal account " + username + " already exists");
		}
	}

	/** The provider finished onboarding already. Not something to redo. */
	public static class AlreadyOnboarded extends RuntimeException {
		public AlreadyOnboarded(String slug) {
			super("provider " + slug + " has already been onboarded");
		}
	}

}
