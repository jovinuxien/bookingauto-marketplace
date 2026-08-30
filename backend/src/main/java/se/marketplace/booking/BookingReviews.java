package se.marketplace.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.reviews.Reviews;

/**
 * A customer rating the appointment they had.
 *
 * <p>Lives here rather than in {@code reviews} because the proof is here:
 * the signed link from the confirmation mail names the booking and the
 * address, and this module is the one that can check it. What is proved is
 * "this person held this booking"; what is then required is that the
 * booking happened — confirmed, and past its end. A cancelled appointment
 * is not an experience of the salon and gets no rating.
 */
@Service
public class BookingReviews {

	private static final Logger log = LoggerFactory.getLogger(BookingReviews.class);

	private static final Duration HOUR = Duration.ofHours(1);

	private final BookingRepository repository;
	private final BookingLinks links;
	private final Reviews reviews;
	private final RateLimiter limiter;

	@Value("${marketplace.booking.lookup-per-ip-per-hour:30}")
	private int lookupPerIpPerHour;

	BookingReviews(BookingRepository repository, BookingLinks links, Reviews reviews,
		RateLimiter limiter) {
		this.repository = repository;
		this.links = links;
		this.reviews = reviews;
		this.limiter = limiter;
	}

	public Result submit(String token, int rating, String comment, String clientIp) {
		if (!limiter.allow("booking:lookup:" + clientIp, lookupPerIpPerHour, HOUR)) {
			return new Throttled();
		}

		Optional<BookingRepository.ConsumerBooking> found = BookingLinks.claimedBooking(token)
			.flatMap(repository::findBookingForCustomer)
			.filter(booking -> links.verify(token, booking.id(), booking.customerEmail()));

		if (found.isEmpty()) {
			return new Unknown();
		}

		BookingRepository.ConsumerBooking booking = found.get();

		if (!booking.confirmed() || booking.endsAt().isAfter(Instant.now())) {
			// Not yet, or not at all. The page knows which from the booking it
			// already shows, so one answer is enough here.
			return new NotYet();
		}

		try {
			boolean saved = reviews.submit(booking.id(), booking.providerId(), rating, comment);
			if (!saved) {
				return new AlreadyReviewed();
			}
		}
		catch (IllegalArgumentException e) {
			return new Invalid(e.getMessage());
		}

		log.info("booking {} reviewed: {}", booking.id(), rating);
		return new Saved(rating);
	}

	public sealed interface Result permits Saved, AlreadyReviewed, NotYet, Invalid, Unknown, Throttled {}

	public record Saved(int rating) implements Result {}

	public record AlreadyReviewed() implements Result {}

	/** Confirmed and in the future, or cancelled. */
	public record NotYet() implements Result {}

	public record Invalid(String message) implements Result {}

	public record Unknown() implements Result {}

	public record Throttled() implements Result {}

}
