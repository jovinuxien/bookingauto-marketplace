package se.marketplace.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider onboarding.
 *
 * <p>Three steps rather than one endpoint, because the middle of it happens
 * outside this system: the salon completes Stripe KYC on Stripe's pages and
 * builds its services in Cal's UI. A single "create provider" call would have to
 * either block on that or lie about it.
 */
@RestController
@RequestMapping("/api/providers")
class OnboardingController {

	private final ProviderOnboarding onboarding;

	OnboardingController(ProviderOnboarding onboarding) {
		this.onboarding = onboarding;
	}

	@PostMapping
	ResponseEntity<?> create(@RequestBody NewProviderRequest request) {
		try {
			var onboarded = onboarding.start(new ProviderOnboarding.NewProvider(
				request.slug(), request.name(), request.city(), request.addressLine(),
				request.postalCode(), request.email(), request.calPassword(),
				request.defaultCategory(), request.longitude(), request.latitude()));
			return ResponseEntity.status(HttpStatus.CREATED).body(onboarded);
		}
		catch (ProviderOnboarding.AlreadyOnCal | ProviderOnboarding.AlreadyOnboarded e) {
			// 409, not 500. The salon has onboarded before and the right next
			// move is to link the existing account, which is a decision for a
			// human rather than a retry.
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		}
	}

	/** A fresh KYC link. Stripe's expire quickly, so this is not cached. */
	@GetMapping("/{id}/kyc-link")
	ResponseEntity<String> kycLink(@PathVariable long id) {
		return ResponseEntity.ok(onboarding.onboardingLink(id));
	}

	@PostMapping("/{id}/import-services")
	ResponseEntity<ProviderOnboarding.ImportResult> importServices(@PathVariable long id) {
		var result = onboarding.importServices(id);

		// 200 whether or not it activated. Importing succeeded; not being
		// sellable yet is a state, not a failure, and the body says which.
		return ResponseEntity.ok(result);
	}

	record NewProviderRequest(
		String slug,
		String name,
		String city,
		String addressLine,
		String postalCode,
		String email,
		String calPassword,
		/** Optional. Absent means the configured default, as before. */
		String defaultCategory,
		Double longitude,
		Double latitude
	) {}

}
