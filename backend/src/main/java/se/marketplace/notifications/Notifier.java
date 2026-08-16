package se.marketplace.notifications;

import java.time.Instant;

/**
 * What other modules ask for.
 *
 * <p>Expressed as events that happened, not as emails to send. The caller knows
 * a booking was confirmed; whether that becomes an email, an SMS or nothing at
 * all is this module's decision, and keeping it that way is what allows a
 * channel to be added later without touching the funnel.
 *
 * <p>Every method takes a {@code dedupeKey}. Callers are retried — Stripe
 * redelivers, the sweeper re-runs — and the guarantee that one event produces
 * one message has to survive that.
 */
public interface Notifier {

	/** The sale completed. The one message a customer is actually waiting for. */
	void bookingConfirmed(BookingNotice notice);

	/**
	 * The reservation was let go before any money moved.
	 *
	 * <p>Worth sending even though nothing was charged: the customer clicked
	 * book and may believe they have an appointment. Silence here is how someone
	 * turns up to a salon that is not expecting them.
	 */
	void bookingReleased(BookingNotice notice, String reason);

	/** Money has been returned. */
	void bookingRefunded(BookingNotice notice, String reason);

	/**
	 * Something is unresolved and a person is looking at it.
	 *
	 * <p>Deliberately does not tell the customer to try again — the funnel
	 * reaches this state when a compensation itself failed, and a second attempt
	 * could take a second payment.
	 */
	void bookingNeedsAttention(BookingNotice notice);

	/**
	 * @param providerId nullable: an attempt can fail before it is tied to a
	 *        salon, and a message that cannot be sent because a foreign key is
	 *        missing is the worst possible time to lose one
	 */
	record BookingNotice(
		String dedupeKey,
		String customerEmail,
		String customerName,
		String providerName,
		String serviceName,
		Instant startsAt,
		int priceMinor,
		String currency,
		Long bookingId,
		Long providerId
	) {}

}
