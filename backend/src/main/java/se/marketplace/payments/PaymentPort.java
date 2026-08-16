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
	 * @throws PaymentRefused if the payment was declined — a normal outcome
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

	record ChargeRequest(
		String idempotencyKey,
		long providerId,
		int amountMinor,
		int commissionMinor,
		String currency,
		String customerEmail,
		String description
	) {}

	record Charge(String reference, int amountMinor, String currency) {}

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
