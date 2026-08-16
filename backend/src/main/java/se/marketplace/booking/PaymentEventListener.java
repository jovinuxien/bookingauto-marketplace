package se.marketplace.booking;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import se.marketplace.payments.PaymentFailed;
import se.marketplace.payments.PaymentSettled;

/**
 * Resumes a suspended sale when the customer finally pays.
 *
 * <p>The funnel had to stop at {@code AWAITING_PAYMENT} because Swish is a push
 * payment. This is where it picks up again — in a different request, minutes
 * later, possibly on a different instance.
 *
 * <p>Listening rather than being called keeps {@code payments} the only module
 * that understands Stripe's payload, and keeps {@code booking} unaware that
 * Stripe is even the gateway.
 */
@Component
class PaymentEventListener {

	private final BookingFunnel funnel;

	PaymentEventListener(BookingFunnel funnel) {
		this.funnel = funnel;
	}

	@EventListener
	void on(PaymentSettled event) {
		funnel.paymentSucceeded(event.paymentIntentId(), event.chargeReference());
	}

	@EventListener
	void on(PaymentFailed event) {
		funnel.paymentFailed(event.paymentIntentId(), event.reason());
	}

}
