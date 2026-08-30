package se.marketplace.signup;

import org.springframework.stereotype.Component;

import java.util.Optional;

import se.marketplace.categories.Categories;
import se.marketplace.categories.Category;
import se.marketplace.console.ProviderLogins;
import se.marketplace.onboarding.ProviderOnboarding;

/**
 * The port, wired to the modules that actually do the work.
 *
 * <p>Deliberately thin. Everything here is translation, and the moment a
 * decision appears in this class it belongs in {@link SelfServeSignup} instead —
 * a rule worth stating because an adapter is where logic goes to become
 * untested.
 */
@Component
class OnboardingProvisioning implements SalonProvisioning {

	private final ProviderOnboarding onboarding;
	private final ProviderLogins logins;
	private final Categories categories;

	OnboardingProvisioning(ProviderOnboarding onboarding, ProviderLogins logins,
		Categories categories) {
		this.onboarding = onboarding;
		this.logins = logins;
		this.categories = categories;
	}

	@Override
	public boolean loginExists(String email) {
		return logins.exists(email);
	}

	@Override
	public String hashPassword(String rawPassword) {
		return logins.hash(rawPassword);
	}

	@Override
	public boolean slugAvailable(String slug) {
		return onboarding.slugAvailable(slug);
	}

	@Override
	public Optional<String> knownCategory(String slug) {
		return slug == null ? Optional.empty() : categories.bySlug(slug).map(Category::slug);
	}

	@Override
	public Provisioned provision(NewSalon salon) {
		try {
			var onboarded = onboarding.start(new ProviderOnboarding.NewProvider(
				salon.slug(), salon.salonName(), salon.city(), salon.addressLine(),
				salon.postalCode(), salon.email(), salon.calPassword(), salon.category(),
				// No coordinates. Nothing here geocodes an address yet, so a
				// self-serve salon is reachable from its city page and invisible
				// to a radius search until someone places it. Left null rather
				// than guessed at the town centre, which would put it on the map
				// in the wrong place and look correct.
				null, null));

			return new Provisioned(onboarded.providerId(), onboarded.calUsername(),
				onboarded.calAccountCreated(), onboarded.kycUrl());
		}
		catch (ProviderOnboarding.AlreadyOnCal e) {
			throw new NameTaken(e.getMessage());
		}
	}

	@Override
	public void createLogin(long providerId, String email, String passwordHash,
		String displayName) {
		logins.createOwner(providerId, email, passwordHash, displayName);
	}

}
