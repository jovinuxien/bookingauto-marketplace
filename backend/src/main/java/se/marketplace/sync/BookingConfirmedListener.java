package se.marketplace.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import se.marketplace.booking.BookingFunnel.BookingConfirmed;

/**
 * Refreshes the index when a sale completes.
 *
 * <p>A confirmed booking makes the index certainly wrong for that service, and
 * we know it here immediately. Waiting for Cal's webhook to say so would leave
 * the just-sold slot advertised for as long as the delivery takes — and webhooks
 * are missed, which is why the reconciler exists at all.
 *
 * <p>The listener marks the service stale rather than recomputing inline. A busy
 * salon taking bookings in quick succession would otherwise mean one round trip
 * to Cal per sale for an answer that is identical after the last one.
 */
@Component
class BookingConfirmedListener {

	private static final Logger log = LoggerFactory.getLogger(BookingConfirmedListener.class);

	private final AvailabilityIndexRepository repository;

	BookingConfirmedListener(AvailabilityIndexRepository repository) {
		this.repository = repository;
	}

	@EventListener
	void on(BookingConfirmed event) {
		repository.markStale(event.serviceId());
		log.debug("booking {} confirmed — marked service {} stale",
			event.bookingId(), event.serviceId());
	}

}
