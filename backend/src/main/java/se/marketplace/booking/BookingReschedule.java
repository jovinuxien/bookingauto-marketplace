package se.marketplace.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.notifications.Notifier;
import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.sync.AvailabilityRefreshPort;
import se.marketplace.sync.CalBookingPort;

/**
 * Moving a booking to another time.
 *
 * <p>Same service, same salon, same money. The order is the one that leaves
 * the customer with a booking whatever fails: hold the new time in Cal,
 * check it is the time asked for, confirm it if the event type needs that,
 * and only then release the old one and rewrite the row. A refusal at the
 * first step means the slot went; a failure after it means the new hold is
 * released and the old booking stands. At no point is there a moment with
 * no appointment.
 *
 * <p>The cutoff is the cancellation cutoff. A time that could no longer be
 * given back for free can no longer be moved for free either, and one rule
 * a customer already saw is better than a second one they did not.
 */
@Service
public class BookingReschedule {

	private static final Logger log = LoggerFactory.getLogger(BookingReschedule.class);

	private static final Duration HOUR = Duration.ofHours(1);

	private final BookingRepository repository;
	private final BookingLinks links;
	private final CalBookingPort cal;
	private final AvailabilityRefreshPort availability;
	private final Notifier notifier;
	private final RateLimiter limiter;
	private final BookingCancellation views;

	@Value("${marketplace.booking.lookup-per-ip-per-hour:30}")
	private int lookupPerIpPerHour;

	@Value("${marketplace.booking.time-zone:Europe/Stockholm}")
	private String timeZone;

	/** How many times one booking may be moved. Three is generous; unbounded is a way to hold slots. */
	@Value("${marketplace.booking.max-reschedules:3}")
	private int maxReschedules;

	BookingReschedule(BookingRepository repository, BookingLinks links, CalBookingPort cal,
		AvailabilityRefreshPort availability, Notifier notifier, RateLimiter limiter,
		BookingCancellation views) {
		this.repository = repository;
		this.links = links;
		this.cal = cal;
		this.availability = availability;
		this.notifier = notifier;
		this.limiter = limiter;
		this.views = views;
	}

	public Result move(String token, Instant newStart, String clientIp) {
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
		Instant now = Instant.now();

		if (!booking.confirmed() || !now.isBefore(cutoff(booking))) {
			return new TooLate(views.view(booking, now));
		}
		if (repository.rescheduleCount(booking.id()) >= maxReschedules) {
			return new TooMany(views.view(booking, now));
		}
		if (newStart.equals(booking.startsAt())) {
			// The same time is not a move. Answer as if it had happened.
			return new Moved(views.view(booking, now));
		}

		BookingRepository.ServiceForSale service = repository.findServiceForSale(booking.serviceId())
			.orElse(null);
		if (service == null) {
			return new Unavailable(views.view(booking, now));
		}

		// ------------------------------------------------ hold the new time --
		CalBookingPort.Reservation reservation;
		try {
			reservation = cal.reserve(new CalBookingPort.ReservationRequest(
				service.calEventTypeId(), newStart, booking.customerName(), booking.customerEmail(), timeZone));
		}
		catch (CalBookingPort.CalRefused e) {
			return new SlotTaken(views.view(booking, now));
		}
		catch (CalBookingPort.CalUnavailable e) {
			log.warn("could not hold a new time for booking {}: {}", booking.id(), e.getMessage());
			return new Unavailable(views.view(booking, now));
		}

		if (!reservation.start().equals(newStart) || reservation.calEventTypeId() != service.calEventTypeId()) {
			// Cal held something other than what was asked. Give it back.
			release(reservation.uid(), "reschedule read-back disagreed");
			return new Unavailable(views.view(booking, now));
		}

		if (reservation.awaitingConfirmation()) {
			try {
				cal.confirm(reservation.uid());
			}
			catch (RuntimeException e) {
				release(reservation.uid(), "reschedule confirm failed");
				return new Unavailable(views.view(booking, now));
			}
		}

		// ------------------------------------------------ let go of the old --
		try {
			cal.cancel(booking.calBookingUid(), "rescheduled by the customer");
		}
		catch (RuntimeException e) {
			// Two holds is the one state we refuse to leave behind. The new one
			// goes; the customer keeps the time they had and is told to try
			// again shortly.
			log.error("booking {}: new time {} held but old {} could not be released — releasing the new one",
				booking.id(), reservation.uid(), booking.calBookingUid(), e);
			release(reservation.uid(), "old booking could not be released");
			return new Unavailable(views.view(booking, now));
		}

		int updated = repository.reschedule(booking.id(), booking.calBookingUid(), reservation.uid(),
			reservation.start(), reservation.end());
		if (updated == 0) {
			// Moved or cancelled under us between the read and the write. Cal
			// now holds a time nobody's row points at; give it back and let
			// the row as it stands be the answer.
			release(reservation.uid(), "booking changed during reschedule");
			return repository.findBookingForCustomer(booking.id())
				.<Result>map(current -> new Unavailable(views.view(current, now)))
				.orElseGet(Unknown::new);
		}

		availability.markStale(booking.serviceId());

		BookingRepository.ConsumerBooking moved = repository.findBookingForCustomer(booking.id()).orElseThrow();
		notify(moved, booking.startsAt());

		log.info("booking {} moved from {} to {}", booking.id(), booking.startsAt(), reservation.start());
		return new Moved(views.view(moved, now));
	}

	private void release(String uid, String why) {
		try {
			cal.cancel(uid, why);
		}
		catch (RuntimeException e) {
			log.error("could not release {} after a failed reschedule ({}) — slot is stranded", uid, why, e);
		}
	}

	private void notify(BookingRepository.ConsumerBooking booking, Instant from) {
		Notifier.BookingNotice notice = new Notifier.BookingNotice(
			"booking:" + booking.id() + ":rescheduled:" + booking.startsAt().getEpochSecond(),
			booking.customerEmail(), booking.customerName(), booking.providerName(),
			booking.serviceName(), booking.startsAt(), booking.priceMinor(), booking.currency(),
			booking.id(), booking.providerId(),
			links.urlFor(booking.id(), booking.customerEmail()),
			booking.registrationNumber(), null);

		notifier.bookingRescheduled(notice, from);
		if (booking.providerEmail() != null) {
			notifier.providerBookingRescheduled(notice, booking.providerEmail(), from);
		}
	}

	static Instant cutoff(BookingRepository.ConsumerBooking booking) {
		return booking.startsAt().minus(booking.cancellationCutoffHours(), ChronoUnit.HOURS);
	}

	public sealed interface Result permits Moved, SlotTaken, TooLate, TooMany, Unavailable, Unknown, Throttled {}

	public record Moved(BookingCancellation.ConsumerView booking) implements Result {}

	/** Cal would not hold the new time; the old booking stands. */
	public record SlotTaken(BookingCancellation.ConsumerView booking) implements Result {}

	/** Past the cutoff, or not confirmed. */
	public record TooLate(BookingCancellation.ConsumerView booking) implements Result {}

	public record TooMany(BookingCancellation.ConsumerView booking) implements Result {}

	/** Something upstream failed; the old booking stands. Worth trying again shortly. */
	public record Unavailable(BookingCancellation.ConsumerView booking) implements Result {}

	public record Unknown() implements Result {}

	public record Throttled() implements Result {}

}
