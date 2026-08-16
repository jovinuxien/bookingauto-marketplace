package se.marketplace.onboarding;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import se.marketplace.payments.ConnectedAccountUpdated;

/**
 * Keeps a provider's payability current.
 *
 * <p>Stripe can restrict an account long after approving it — a document
 * expires, a review fails — and the first sign is otherwise a failed transfer,
 * which happens after the customer's money has already been taken. So this
 * re-reads the account rather than trusting what onboarding recorded.
 */
@Component
class ConnectedAccountListener {

	private final ProviderOnboarding onboarding;

	ConnectedAccountListener(ProviderOnboarding onboarding) {
		this.onboarding = onboarding;
	}

	@EventListener
	void on(ConnectedAccountUpdated event) {
		onboarding.accountUpdated(event.stripeAccountId());
	}

}
