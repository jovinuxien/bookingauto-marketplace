package se.marketplace.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.notifications.Notifier;
import se.marketplace.payments.PaymentPort;
import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.reviews.Review;
import se.marketplace.reviews.Reviews;
import se.marketplace.sync.CalBookingPort;

/**
 * A customer reaching their own booking, and undoing it.
 *
 * <p>The other half of the funnel, and the one that was missing: {@code booking}
 * has allowed {@code cancelled} and {@code refunded} since db/002 and nothing
 * ever wrote them. Everything mechanical this needs already existed — the funnel
 * releases slots and refunds charges as compensations — so what is actually new
 * here is who is permitted to trigger those, and on what terms.
 *
 * <h2>The terms</h2>
 *
 * <p>Free until {@code cancellation_cutoff_hours} before the appointment, and
 * after that the slot still comes back but the money does not. One number,
 * shown on the page before the customer commits, and frozen onto the booking at
 * sale time so a later change to the default cannot rewrite what somebody
 * already agreed to.
 *
 * <p>Cancelling late is still allowed. A customer who cannot cancel simply does
 * not turn up, and a salon that knows at eight in the morning can sell the slot
 * to somebody else — so refusing the cancellation costs the salon the chair and
 * gains nobody anything.
 *
 * <h2>The order, which is the whole design</h2>
 *
 * <p>Claim the row, then release the slot, then return the money.
 *
 * <p><strong>Claim first</strong> because two clicks must not refund twice; see
 * {@link BookingRepository#claimForCancellation}.
 *
 * <p><strong>Cal before Stripe</strong> because of what each failure leaves
 * behind. If the refund fails after the slot is released, the customer has what
 * they asked for and is owed money, which is flagged, logged and fixable by a
 * person. If it were the other way round and Cal failed after the refund, the
 * customer would have their money and a live appointment they believe is
 * cancelled — and nobody would find out until the salon held an empty chair.
 * The funnel makes the opposite choice in {@code refundAndStop}, correctly:
 * there the sale never completed, so there was no appointment to protect.
 */
@Service
public class BookingCancellation {

	private static final Logger log = LoggerFactory.getLogger(BookingCancellation.class);

	private static final Duration HOUR = Duration.ofHours(1);

	private final BookingRepository repository;
	private final BookingLinks links;
	private final CalBookingPort cal;
	private final PaymentPort payments;
	private final Notifier notifier;
	private final RateLimiter limiter;
	private final Reviews reviews;

	/**
	 * Lookups per hour from one address.
	 *
	 * <p>Guessing a token is not a strategy — it is an HMAC-SHA256 — so this is
	 * not defending the booking. It is there because the endpoint is anonymous
	 * and does a database join per call, which is the shape ADR 0011 says to
	 * count before it is worth someone's while.
	 */
	@Value("${marketplace.booking.lookup-per-ip-per-hour:120}")
	private int lookupPerIpPerHour;

	BookingCancellation(BookingRepository repository, BookingLinks links, CalBookingPort cal,
		PaymentPort payments, Notifier notifier, RateLimiter limiter, Reviews reviews) {

		this.reviews = reviews;
		this.repository = repository;
		this.links = links;
		this.cal = cal;
		this.payments = payments;
		this.notifier = notifier;
		this.limiter = limiter;
	}

	// ------------------------------------------------------------ reading --

	public Result lookup(String token, String clientIp) {
		if (!limiter.allow("booking:lookup:" + clientIp, lookupPerIpPerHour, HOUR)) {
			return new Throttled();
		}

		return resolve(token)
			.<Result>map(booking -> new Found(view(booking, Instant.now())))
			.orElseGet(Unknown::new);
	}

	// ------------------------------------------------------- cancelling --

	public Result cancel(String token, String clientIp) {
		if (!limiter.allow("booking:lookup:" + clientIp, lookupPerIpPerHour, HOUR)) {
			return new Throttled();
		}

		BookingRepository.ConsumerBooking booking = resolve(token).orElse(null);

		if (booking == null) {
			return new Unknown();
		}

		Instant now = Instant.now();

		// Already cancelled is a success, not an error. The customer clicked
		// twice, or came back to check; either way the booking is in the state
		// they wanted and telling them something went wrong would be a lie.
		if (!booking.confirmed()) {
			return new Found(view(booking, now));
		}

		if (!now.isBefore(booking.startsAt())) {
			return new TooLate(view(booking, now));
		}

		if (!repository.claimForCancellation(booking.id())) {
			// Lost the race to another click. The winner is doing the work; this
			// caller is owed the answer, not a second attempt at it.
			return repository.findBookingForCustomer(booking.id())
				.<Result>map(fresh -> new Found(view(fresh, now)))
				.orElseGet(Unknown::new);
		}

		repository.markCancelledBy(booking.id(), "customer");
		return finish(booking, now);
	}

	/**
	 * Everything after the row has been claimed.
	 *
	 * <p>From here the booking already reads {@code cancelled} and is flagged,
	 * so every path out of this method has to settle it — including the ones
	 * that fail. A return that leaves the flag set is a return that has said why
	 * in the log.
	 */
	private Result finish(BookingRepository.ConsumerBooking booking, Instant now) {
		try {
			cal.cancel(booking.calBookingUid(), "cancelled by the customer");
		}
		catch (RuntimeException e) {
			// The slot is still Cal's, and the booking already says cancelled.
			// Left flagged deliberately: this is a real inconsistency between us
			// and the authority that owns time, and the salon is about to be
			// holding a chair for someone who is not coming.
			log.error("could not release {} cancelling booking {} — the slot is still held",
				booking.calBookingUid(), booking.id(), e);
			repository.settleCancellation(booking.id(), "cancelled", null, true);
			return new Unavailable(view(cancelled(booking, "cancelled", true), now));
		}

		if (!refundDue(booking, now)) {
			repository.settleCancellation(booking.id(), "cancelled", null, false);
			BookingRepository.ConsumerBooking settled = cancelled(booking, "cancelled", false);
			notify(settled, false);
			return new Cancelled(view(settled, now), false);
		}

		if (booking.paymentRef() == null) {
			// Nothing was ever charged — a booking from before payments reached
			// anything real, or a free service. Cancelling it is complete.
			repository.settleCancellation(booking.id(), "cancelled", null, false);
			BookingRepository.ConsumerBooking settled = cancelled(booking, "cancelled", false);
			notify(settled, false);
			return new Cancelled(view(settled, now), false);
		}

		try {
			PaymentPort.Refund refund =
				payments.refund(booking.paymentRef(), "cancelled by the customer");

			repository.settleCancellation(booking.id(), "refunded", refund.reference(), false);
			BookingRepository.ConsumerBooking settled = cancelled(booking, "refunded", false);
			notify(settled, true);
			return new Cancelled(view(settled, now), true);
		}
		catch (RuntimeException e) {
			// The appointment is gone, which is what was asked for, and the
			// money is not back, which is not. Same wording as the funnel's own
			// failed refund, because it is the same fact: a person has to act.
			log.error("could not refund {} cancelling booking {} — customer is out of pocket",
				booking.paymentRef(), booking.id(), e);
			repository.settleCancellation(booking.id(), "cancelled", null, true);
			BookingRepository.ConsumerBooking settled = cancelled(booking, "cancelled", true);
			notify(settled, false);
			return new RefundStuck(view(settled, now));
		}
	}

	// ---------------------------------------------------------- the terms --

	private static boolean refundDue(BookingRepository.ConsumerBooking booking, Instant now) {
		return now.isBefore(freeUntil(booking));
	}

	private static Instant freeUntil(BookingRepository.ConsumerBooking booking) {
		return booking.startsAt().minus(booking.cancellationCutoffHours(), ChronoUnit.HOURS);
	}

	// ------------------------------------------------------------ plumbing --

	/**
	 * The booking a token names, or nothing.
	 *
	 * <p>Reads by the id the token claims and then checks the signature against
	 * <em>that booking's</em> address. An edited id therefore fails the check
	 * rather than returning a stranger's appointment, and a token for a booking
	 * that no longer exists is indistinguishable from one that was never valid.
	 */
	private Optional<BookingRepository.ConsumerBooking> resolve(String token) {
		return BookingLinks.claimedBooking(token)
			.flatMap(repository::findBookingForCustomer)
			.filter(booking -> links.verify(token, booking.id(), booking.customerEmail()));
	}

	private void notify(BookingRepository.ConsumerBooking booking, boolean refunded) {
		Notifier.BookingNotice notice = new Notifier.BookingNotice(
			"booking:" + booking.id() + ":cancelled",
			booking.customerEmail(),
			booking.customerName(),
			booking.providerName(),
			booking.serviceName(),
			booking.startsAt(),
			booking.priceMinor(),
			booking.currency(),
			booking.id(),
			booking.providerId(),
			links.urlFor(booking.id(), booking.customerEmail()),
			booking.registrationNumber(),
			null);

		notifier.bookingCancelled(notice, refunded, booking.cancellationCutoffHours());

		// The salon is the other party to this sale and is the one who can
		// still sell the slot. The first message this system has ever sent to
		// one; a marketplace that tells only the customer is a marketplace where
		// the chair stays empty.
		if (booking.providerEmail() != null && !booking.providerEmail().isBlank()) {
			notifier.providerBookingCancelled(notice, booking.providerEmail());
		}
	}

	ConsumerView view(BookingRepository.ConsumerBooking booking, Instant now) {
		boolean confirmed = booking.confirmed();

		Optional<Review> review = confirmed ? reviews.forBooking(booking.id()) : Optional.empty();
		return new ConsumerView(
			booking.serviceId(),
			booking.providerName(),
			booking.city(),
			booking.serviceName(),
			booking.startsAt(),
			booking.endsAt(),
			booking.priceMinor(),
			booking.currency(),
			booking.status(),
			booking.customerName(),
			confirmed && now.isBefore(booking.startsAt()),
			confirmed && refundDue(booking, now),
			confirmed && refundDue(booking, now),
			freeUntil(booking),
			booking.cancellationCutoffHours(),
			booking.needsAttention(),
			booking.registrationNumber(),
			confirmed && !now.isBefore(booking.endsAt()),
			review.map(Review::rating).orElse(null),
			review.map(Review::comment).orElse(null),
			repository.addonsOf(booking.id()));
	}

	/** The row as it now reads, without going back to the database to find out. */
	private static BookingRepository.ConsumerBooking cancelled(
		BookingRepository.ConsumerBooking booking, String status, boolean needsAttention) {

		return new BookingRepository.ConsumerBooking(
			booking.id(), booking.providerId(), booking.serviceId(), booking.calBookingUid(),
			booking.startsAt(), booking.endsAt(), booking.customerEmail(), booking.customerName(),
			booking.priceMinor(), booking.currency(), status,
			booking.cancellationCutoffHours(), Instant.now(), needsAttention,
			booking.providerName(), booking.providerEmail(), booking.city(),
			booking.serviceName(), booking.paymentRef(), booking.registrationNumber());
	}

	// ------------------------------------------------------------ outcomes --

	/**
	 * What a page needs to render, and nothing that identifies anyone else.
	 *
	 * <p>No booking id and no Cal uid. The caller already holds a token naming
	 * the booking, so an id would add nothing it could not already do, and both
	 * are values that turn up in support conversations and screenshots.
	 */
	public record ConsumerView(
		/** The service, so the page can fetch other free times to move to. */
		long serviceId,
		String providerName,
		String city,
		String serviceName,
		Instant startsAt,
		Instant endsAt,
		int priceMinor,
		String currency,
		String status,
		String customerName,
		boolean cancellable,
		boolean refundable,
		/** Confirmed, and before the cutoff: the time can still be moved. */
		boolean reschedulable,
		Instant freeUntil,
		int cutoffHours,
		boolean needsAttention,
		/** Null unless the service asked for one. */
		String registrationNumber,
		/** The appointment has happened: confirmed and past its end. A rating may be given. */
		boolean reviewable,
		/** What this customer already said, or null. */
		Integer reviewRating,
		String reviewComment,
		/** Add-ons chosen at checkout, as sold. */
		List<Extra> extras
	) {}

	public record Extra(String name, int priceMinor) {}

	public sealed interface Result {}

	/** The booking, as it stands. Also the answer to cancelling one twice. */
	public record Found(ConsumerView booking) implements Result {}

	/** Cancelled by this call. */
	public record Cancelled(ConsumerView booking, boolean refunded) implements Result {}

	/**
	 * No such booking, or a token that does not sign it.
	 *
	 * <p>One answer for both, deliberately. Telling a caller that a booking
	 * exists but their token is wrong would make the endpoint a way to ask
	 * whether a given id was ever sold.
	 */
	public record Unknown() implements Result {}

	/** The appointment has already started or passed. */
	public record TooLate(ConsumerView booking) implements Result {}

	/** The slot could not be released; the booking is flagged and nothing is settled. */
	public record Unavailable(ConsumerView booking) implements Result {}

	/** Cancelled, and the refund did not go through. A person has to act. */
	public record RefundStuck(ConsumerView booking) implements Result {}

	public record Throttled() implements Result {}

}
