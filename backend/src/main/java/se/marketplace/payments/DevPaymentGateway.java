package se.marketplace.payments;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A stand-in for Stripe, for developing the funnel before Connect exists.
 *
 * <p>It moves no money and must never be reachable in production, which is why
 * it is conditional on an explicit property rather than on the absence of Stripe
 * credentials. A gateway that silently activates when configuration is missing
 * is a gateway that will one day take a real customer's booking for free.
 *
 * <p>It can be told to fail, because the compensation paths are the part of the
 * funnel worth exercising and they are unreachable if payment always succeeds.
 * Refusing on a magic email address rather than a random rate keeps the failure
 * reproducible: {@code refuse@example.se} is always declined,
 * {@code unknown@example.se} always returns an unknown outcome.
 */
@Component
@ConditionalOnProperty(name = "marketplace.payments.gateway", havingValue = "dev", matchIfMissing = true)
class DevPaymentGateway implements PaymentPort {

	private static final Logger log = LoggerFactory.getLogger(DevPaymentGateway.class);

	private final AtomicLong sequence = new AtomicLong();

	@Value("${marketplace.payments.refuse-email:refuse@example.se}")
	private String refuseEmail;

	@Value("${marketplace.payments.unavailable-email:unknown@example.se}")
	private String unavailableEmail;

	/** Set to make refunds fail, to reach NEEDS_ATTENTION in a test. */
	@Value("${marketplace.payments.break-refunds:false}")
	private boolean breakRefunds;

	/**
	 * Exercises the asynchronous path without Stripe.
	 *
	 * <p>Worth having even though it is a fiction: a gateway that always settles
	 * inline lets the whole AWAITING_PAYMENT branch rot untested until the day
	 * real Swish traffic arrives.
	 */
	@Value("${marketplace.payments.pending-email:pending@example.se}")
	private String pendingEmail;

	DevPaymentGateway() {
		log.warn("dev payment gateway active — no money will move");
	}

	@Override
	public Charge charge(ChargeRequest request) {
		if (refuseEmail.equalsIgnoreCase(request.customerEmail())) {
			throw new PaymentRefused("card declined (dev gateway)");
		}
		if (unavailableEmail.equalsIgnoreCase(request.customerEmail())) {
			throw new PaymentUnavailable("gateway timeout (dev gateway)");
		}

		String ref = "dev_ch_" + sequence.incrementAndGet();

		if (pendingEmail.equalsIgnoreCase(request.customerEmail())) {
			log.info("dev charge {} left awaiting customer action", ref);
			return new Charge(ref, request.amountMinor(), request.currency(),
				Status.REQUIRES_ACTION, "dev_secret_" + ref);
		}

		log.info("dev charge {} of {} {} for provider {} (commission {})",
			ref, request.amountMinor(), request.currency(),
			request.providerId(), request.commissionMinor());

		return Charge.settled(ref, request.amountMinor(), request.currency());
	}

	@Override
	public Refund refund(String chargeRef, String reason) {
		if (breakRefunds) {
			throw new PaymentUnavailable("refund failed (dev gateway, deliberately broken)");
		}

		String ref = "dev_re_" + sequence.incrementAndGet();
		log.info("dev refund {} of charge {} because: {}", ref, chargeRef, reason);
		return new Refund(ref, 0);
	}

}
