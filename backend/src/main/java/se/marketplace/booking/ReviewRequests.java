package se.marketplace.booking;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import se.marketplace.notifications.Notifier;

/**
 * "Hur var det?" — asked once, a little while after.
 *
 * <p>A sweep, like everything else that happens after the sale. Two hours
 * after the appointment ended, not immediately: someone still in the chair
 * cannot rate the cut. Not later than two weeks: the mail would land as
 * spam about a visit they have forgotten. Marked on the booking the moment
 * it is enqueued, so a redelivered pass never asks twice.
 */
@Component
class ReviewRequests {

	private static final Logger log = LoggerFactory.getLogger(ReviewRequests.class);

	private final BookingRepository repository;
	private final Notifier notifier;
	private final BookingLinks links;

	@Value("${marketplace.reviews.ask-after-hours:2}")
	private int askAfterHours;

	@Value("${marketplace.reviews.ask-within-days:14}")
	private int askWithinDays;

	@Value("${marketplace.reviews.batch-size:50}")
	private int batchSize;

	ReviewRequests(BookingRepository repository, Notifier notifier, BookingLinks links) {
		this.repository = repository;
		this.notifier = notifier;
		this.links = links;
	}

	@Scheduled(
		fixedDelayString = "${marketplace.reviews.interval-ms:600000}",
		initialDelayString = "${marketplace.reviews.initial-delay-ms:45000}")
	void ask() {
		List<BookingRepository.ConsumerBooking> due =
			repository.needingReviewRequest(askAfterHours, askWithinDays, batchSize);

		for (BookingRepository.ConsumerBooking booking : due) {
			if (repository.markReviewRequested(booking.id()) == 0) {
				continue; // another node got there first
			}
			notifier.reviewRequested(new Notifier.BookingNotice(
				"booking:" + booking.id() + ":review",
				booking.customerEmail(), booking.customerName(), booking.providerName(),
				booking.serviceName(), booking.startsAt(), booking.priceMinor(), booking.currency(),
				booking.id(), booking.providerId(),
				links.urlFor(booking.id(), booking.customerEmail()),
				booking.registrationNumber(), null));
		}

		if (!due.isEmpty()) {
			log.info("asked {} customer(s) how it was", due.size());
		}
	}

}
