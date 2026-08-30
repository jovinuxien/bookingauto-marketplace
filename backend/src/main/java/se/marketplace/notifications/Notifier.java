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
	 * The customer cancelled, and this is what it cost them.
	 *
	 * <p>One method with a flag rather than two, because the caller knows the
	 * commercial fact — money came back or it did not — and which of those
	 * becomes which words is this module's business, exactly as it is for every
	 * other event here.
	 *
	 * @param refunded whether the cancellation was inside the free window
	 */
	void bookingCancelled(BookingNotice notice, boolean refunded, int cutoffHours);

	/**
	 * The salon's slot is free again.
	 *
	 * <p>The first message this system sends to a salon rather than to a
	 * customer, which is why it takes the recipient explicitly: every other
	 * notice here goes to {@code notice.customerEmail()}, and quietly widening
	 * that field to mean "whoever this is about" is how a customer eventually
	 * receives a salon's copy.
	 */
	void providerBookingCancelled(BookingNotice notice, String providerEmail);

	/**
	 * The provider's copy of a sale: who, what, when — and which car, when the
	 * service asked for one. A work order rather than a receipt, which is why
	 * it is its own message and not a second recipient on the customer's.
	 */
	void providerBookingConfirmed(BookingNotice notice, String providerEmail);

	/**
	 * Something is unresolved and a person is looking at it.
	 *
	 * <p>Deliberately does not tell the customer to try again — the funnel
	 * reaches this state when a compensation itself failed, and a second attempt
	 * could take a second payment.
	 */
	void bookingNeedsAttention(BookingNotice notice);

	/**
	 * The salon cancelled — an apology, and the money. {@code refunded} false
	 * means the refund needs a human and the customer is told it is coming.
	 */
	void bookingCancelledByProvider(BookingNotice notice, boolean refunded);

	/** The customer moved the time. The notice carries the new time; {@code from} is the old one. */
	void bookingRescheduled(BookingNotice notice, java.time.Instant from);

	/** The salon's copy of the move — the old work slot is free, the new one is taken. */
	void providerBookingRescheduled(BookingNotice notice, String providerEmail, java.time.Instant from);

	/** "Hur var det?" — sent once, a while after the appointment, with the link to rate it. */
	void reviewRequested(BookingNotice notice);

	/**
	 * @param providerId nullable: an attempt can fail before it is tied to a
	 *        salon, and a message that cannot be sent because a foreign key is
	 *        missing is the worst possible time to lose one
	 * @param manageUrl  where the customer reaches this booking again. Null for
	 *        every event about a sale that did not complete, because there is
	 *        nothing there to reach — the field is not optional decoration, it
	 *        is absent exactly when a booking is
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
		Long providerId,
		String manageUrl,
		/** Normalised plate, or null when the service did not ask for one. */
		String registrationNumber,
		/** The add-ons chosen, "Spolarvätska, Däckhotell", or null. */
		String extras
	) {}

}
