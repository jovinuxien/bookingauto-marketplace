package se.marketplace.payments;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Connect onboarding without Stripe.
 *
 * <p>Grants everything immediately, which is exactly what real KYC does not do.
 * Useful for developing the flow; actively misleading about how long a salon
 * waits and how often verification comes back incomplete.
 */
@Component
@ConditionalOnProperty(name = "marketplace.payments.gateway", havingValue = "dev", matchIfMissing = true)
class DevStripeConnect implements StripeConnectPort {

	private static final Logger log = LoggerFactory.getLogger(DevStripeConnect.class);

	private final AtomicLong sequence = new AtomicLong();

	@Override
	public ConnectedAccount createAccount(NewAccount request) {
		String id = "acct_dev_" + request.providerId() + "_" + sequence.incrementAndGet();
		log.info("dev connect account {} for provider {}", id, request.providerId());
		return new ConnectedAccount(id);
	}

	@Override
	public String onboardingLink(String accountId, String returnUrl, String refreshUrl) {
		return returnUrl + "?dev_onboarding=" + accountId;
	}

	@Override
	public AccountStatus status(String accountId) {
		return new AccountStatus(true, true, true, null);
	}

}
