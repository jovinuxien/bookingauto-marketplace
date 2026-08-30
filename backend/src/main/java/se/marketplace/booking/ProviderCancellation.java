package se.marketplace.booking;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import se.marketplace.notifications.Notifier;
import se.marketplace.payments.PaymentPort;
import se.marketplace.sync.AvailabilityRefreshPort;
import se.marketplace.sync.CalBookingPort;

/**
 * The salon letting go of a time.
 *
 * <p>Mirrors the customer's cancellation with one rule inverted: the refund
 * is unconditional. The cutoff exists to protect the provider from a
 * customer who cancels late; it has no business protecting a provider from
 * its own cancellation, so the customer gets the whole amount back however
 * close to the appointment this happens.
 *
 * <p>Authorisation is the console session: the booking must belong to the
 * signed-in provider, and one that does not is answered as if it did not
 * exist. The same claim guard as the customer's flow makes the two racing
 * cancellations resolve to one.
 */
@Service
public class ProviderCancellation {

	private static final Logger log = LoggerFactory.getLogger(ProviderCancellation.class);

	private final BookingRepository repository;
	private final CalBookingPort cal;
	private final PaymentPort payments;
	private final AvailabilityRefreshPort availability;
	private final Notifier notifier;
	private final BookingLinks links;

	ProviderCancellation(BookingRepository repository, CalBookingPort cal, PaymentPort payments,
		AvailabilityRefreshPort availability, Notifier notifier, BookingLinks links) {
		this.repository = repository;
		this.cal = cal;
		this.payments = payments;
		this.availability = availability;
		this.notifier = notifier;
		this.links = links;
	}

	public Result cancel(long providerId, long bookingId) {
		BookingRepository.ConsumerBooking booking =
			repository.findBookingForCustomer(bookingId).orElse(null);

		// A booking that is not this provider's is answered as if it did not
		// exist: the difference is not this caller's to learn.
		if (booking == null || booking.providerId() != providerId) {
			return new Unknown();
		}

		Instant now = Instant.now();

		if (!booking.confirmed()) {
			return new AlreadyCancelled();
		}
		if (!now.isBefore(booking.startsAt())) {
			// Started or past. Nothing to free and nothing to refuse politely.
			return new TooLate();
		}

		if (!repository.claimForCancellation(booking.id())) {
			// The customer got there first. Their cancellation stands.
			return new AlreadyCancelled();
		}
		repository.markCancelledBy(booking.id(), "provider");

		try {
			cal.cancel(booking.calBookingUid(), "cancelled by the salon");
		}
		catch (RuntimeException e) {
			log.error("could not release {} cancelling booking {} for provider {} — slot still held",
				booking.calBookingUid(), booking.id(), providerId, e);
			repository.settleCancellation(booking.id(), "cancelled", null, true);
			return new Unavailable();
		}

		availability.markStale(booking.serviceId());

		if (booking.paymentRef() == null) {
			// Nothing was charged (a dev booking, or a payment path with no
			// ref). Cancelled is the whole outcome.
			repository.settleCancellation(booking.id(), "cancelled", null, false);
			notify(booking, true);
			return new Cancelled(true);
		}

		try {
			PaymentPort.Refund refund = payments.refund(booking.paymentRef(), "cancelled by the salon");
			repository.settleCancellation(booking.id(), "refunded", refund.reference(), false);
			notify(booking, true);
			log.info("provider {} cancelled booking {} — refunded {}", providerId, booking.id(),
				refund.reference());
			return new Cancelled(true);
		}
		catch (RuntimeException e) {
			// The appointment is off either way; the money is a person's
			// problem now, and the customer is told it is coming rather than
			// that it arrived.
			log.error("could not refund {} for provider-cancelled booking {} — customer is out of pocket",
				booking.paymentRef(), booking.id(), e);
			repository.settleCancellation(booking.id(), "cancelled", null, true);
			notify(booking, false);
			return new RefundStuck();
		}
	}

	private void notify(BookingRepository.ConsumerBooking booking, boolean refunded) {
		notifier.bookingCancelledByProvider(new Notifier.BookingNotice(
			"booking:" + booking.id() + ":cancelled",
			booking.customerEmail(), booking.customerName(), booking.providerName(),
			booking.serviceName(), booking.startsAt(), booking.priceMinor(), booking.currency(),
			booking.id(), booking.providerId(),
			links.urlFor(booking.id(), booking.customerEmail()),
			booking.registrationNumber(), null), refunded);
	}

	public sealed interface Result
		permits Cancelled, RefundStuck, AlreadyCancelled, TooLate, Unavailable, Unknown {}

	/** @param refunded always true today; kept for the day partial charges exist */
	public record Cancelled(boolean refunded) implements Result {}

	/** Cancelled, refund needs a human; the customer is told it is coming. */
	public record RefundStuck() implements Result {}

	public record AlreadyCancelled() implements Result {}

	public record TooLate() implements Result {}

	/** Cal would not release the slot; flagged for a human, nothing sent. */
	public record Unavailable() implements Result {}

	public record Unknown() implements Result {}

}
