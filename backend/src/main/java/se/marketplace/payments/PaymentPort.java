package se.marketplace.payments;

/**
 * Taking and returning money.
 *
 * <p>Small on purpose, and shaped by ADR 0005 rather than by Stripe's API. The
 * decisive constraint is that Swish has <strong>no manual capture</strong>: an
 * authorise-then-capture pair is unavailable for the dominant Swedish payment
 * method, so there is no {@code void} here. The only way to undo a charge is to
 * refund it, and the funnel's compensation table is built on that.
 *
 * <p>Commission is passed per charge rather than read from configuration inside
 * the implementation, because it is part of the quote frozen at stage 5. A rate
 * change must not retroactively alter a sale that is already in flight.
 */
public interface PaymentPort {

	/**
	 * Charge the consumer, keeping {@code commissionMinor} as the application
	 * fee and destining the rest for the provider's connected account.
	 *
	 * <p>May well <em>not</em> have taken any money by the time it returns. Swish
	 * is a push payment: the customer approves in their bank app and Stripe
	 * reports the outcome later over a webhook. The returned {@link Charge} says
	 * which happened, and callers must handle both — treating this as
	 * synchronous works exactly until the first real payment method is plugged
	 * in.
	 *
	 * @throws PaymentRefused if the payment was declined outright — a normal
	 *         outcome
	 * @throws PaymentUnavailable if we do not know whether money moved
	 */
	Charge charge(ChargeRequest request);

	/**
	 * Return the money. The compensating action for a charge.
	 *
	 * @throws PaymentUnavailable if the refund could not be placed, which leaves
	 *         the customer out of pocket and needs a person
	 */
	Refund refund(String chargeRef, String reason);

	/**
	 * @param connectedAccountId where the provider's share is sent. Required for
	 *        a destination charge; without it the whole amount would settle on
	 *        the platform account with nothing recording whose it is.
	 */
	record ChargeRequest(
		String idempotencyKey,
		long providerId,
		String connectedAccountId,
		int amountMinor,
		int commissionMinor,
		String currency,
		String customerEmail,
		String description
	) {}

	/**
	 * @param reference     the settled charge, or the intent while it is pending
	 * @param clientSecret  what the browser needs to complete the payment;
	 *                      null once settled. Safe to hand to the client — it
	 *                      authorises confirming this one intent and nothing else
	 */
	record Charge(
		String reference,
		int amountMinor,
		String currency,
		Status status,
		String clientSecret
	) {

		public static Charge settled(String reference, int amountMinor, String currency) {
			return new Charge(reference, amountMinor, currency, Status.SETTLED, null);
		}

		public boolean settled() {
			return status == Status.SETTLED;
		}
	}

	enum Status {
		/** Money has moved. */
		SETTLED,
		/**
		 * The customer has to do something — approve in Swish, pass 3-D Secure —
		 * and the outcome arrives later on a webhook.
		 */
		REQUIRES_ACTION
	}

	record Refund(String reference, int amountMinor) {}

	/** Declined. Expected, handled, and not an error. */
	class PaymentRefused extends RuntimeException {
		public PaymentRefused(String message) {
			super(message);
		}
	}

	/**
	 * The state of the money is unknown.
	 *
	 * <p>Distinct from a refusal because the funnel must treat them differently:
	 * a refusal means nothing moved and the reservation can be released cleanly,
	 * while this means it might have, and a person has to look.
	 */
	class PaymentUnavailable extends RuntimeException {
		public PaymentUnavailable(String message) {
			super(message);
		}

		public PaymentUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
