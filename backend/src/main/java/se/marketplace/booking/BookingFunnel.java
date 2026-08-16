package se.marketplace.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.booking.BookingRepository.Attempt;
import se.marketplace.booking.BookingRepository.NewAttempt;
import se.marketplace.booking.BookingRepository.ServiceForSale;
import se.marketplace.payments.PaymentPort;
import se.marketplace.sync.AvailabilityRefreshPort;
import se.marketplace.sync.CalBookingPort;

/**
 * The funnel.
 *
 * <p>Where the marketplace stops being allowed to be wrong. Upstream of here,
 * search reads a deliberately stale-tolerant index and being wrong costs a
 * wasted click. Here, three authorities that cannot be updated atomically have
 * to end up agreeing — Cal owns time, Stripe owns money, we own the commercial
 * record — and their disagreement costs a customer a real appointment or real
 * money.
 *
 * <p>There is no distributed transaction available. What replaces one:
 *
 * <ol>
 *   <li>An ordering in which nothing irreversible happens until the slot is
 *       actually held. Reserve, then charge — never the reverse. Swish has no
 *       manual capture, so a failed sale is undone by refunding, not voiding,
 *       and that makes charge-first indefensible. See ADR 0005.</li>
 *   <li>Exactly one compensating action per way of failing, declared in
 *       {@link AttemptState} so this class cannot quietly disagree with it.</li>
 *   <li>A written trail at every step, so a customer saying "I was charged and
 *       have no appointment" is answered from a table rather than a guess.</li>
 * </ol>
 *
 * <p>Deliberately not transactional at the method level. A database transaction
 * spanning the Cal and Stripe calls would roll back our record of what we asked
 * them to do while leaving what they did untouched — losing precisely the
 * evidence the trail exists to keep.
 */
@Service
public class BookingFunnel {

	private static final Logger log = LoggerFactory.getLogger(BookingFunnel.class);

	private final BookingRepository repository;
	private final CalBookingPort cal;
	private final PaymentPort payments;
	private final AvailabilityRefreshPort availability;

	/** Basis points. 1500 = 15%. Frozen onto the attempt at stage 5. */
	@Value("${marketplace.commission-bps:1500}")
	private int commissionBps;

	@Value("${marketplace.cal.timezone:Europe/Stockholm}")
	private String timeZone;

	BookingFunnel(BookingRepository repository, CalBookingPort cal,
		PaymentPort payments, AvailabilityRefreshPort availability) {
		this.repository = repository;
		this.cal = cal;
		this.payments = payments;
		this.availability = availability;
	}

	/**
	 * Stages 5 through 8.
	 *
	 * <p>Returns an outcome rather than throwing, because almost everything that
	 * can go wrong here is a normal handled path with a defined result. Cal
	 * refusing the slot in particular is not an error — it is what ADR 0002 buys
	 * by letting search be approximate, and it is the moment an availability miss
	 * gets recorded.
	 */
	public Outcome book(BookingRequest request) {
		// Idempotency first. A double-clicked checkout must not become two
		// reservations and two charges, and the guard has to come before
		// anything else touches an authority.
		Optional<Attempt> existing = repository.findByIdempotencyKey(request.idempotencyKey());
		if (existing.isPresent()) {
			Attempt attempt = existing.get();
			log.info("replaying attempt {} in state {}", attempt.id(), attempt.state());
			return outcomeOf(attempt);
		}

		ServiceForSale service = repository.findServiceForSale(request.serviceId())
			.orElseThrow(() -> new IllegalArgumentException("no such service: " + request.serviceId()));

		if (!service.active() || !service.providerActive()) {
			throw new IllegalStateException("service " + service.serviceId() + " is not on sale");
		}

		// Stage 5: freeze the quote. Copied onto the attempt, never re-read.
		int commission = Math.round(service.priceMinor() * commissionBps / 10_000f);

		Attempt attempt = repository.start(new NewAttempt(
			request.idempotencyKey(),
			service.providerId(),
			service.serviceId(),
			request.slotStart(),
			service.priceMinor(),
			commission,
			service.currency(),
			request.customerEmail(),
			request.customerName()));

		return runSaga(attempt, service, request);
	}

	private Outcome runSaga(Attempt attempt, ServiceForSale service, BookingRequest request) {
		// ---------------------------------------------------------- stage 6 --
		CalBookingPort.Reservation reservation;
		try {
			reservation = cal.reserve(new CalBookingPort.ReservationRequest(
				service.calEventTypeId(),
				request.slotStart(),
				request.customerName(),
				request.customerEmail(),
				timeZone));
		}
		catch (CalBookingPort.CalRefused e) {
			// The index said yes and Cal said no. Expected, and the only place
			// in the system where both answers are in hand.
			repository.recordMiss(service.providerId(), service.serviceId(),
				request.slotStart(),
				repository.indexAgeSeconds(service.serviceId(), request.slotStart()));
			repository.transition(attempt, AttemptState.REFUSED, "cal", "refused", e.getMessage(), false);
			return new Outcome(attempt.id(), AttemptState.REFUSED, null, e.getMessage());
		}
		catch (CalBookingPort.CalUnavailable e) {
			// We do not know whether a booking was created, and we have no uid
			// with which to cancel one. Nothing may be charged, and a person has
			// to reconcile against Cal.
			repository.transition(attempt, AttemptState.NEEDS_ATTENTION, "cal", "error",
				"reserve outcome unknown: " + e.getMessage(), false);
			return new Outcome(attempt.id(), AttemptState.NEEDS_ATTENTION, null, e.getMessage());
		}

		repository.recordReservation(attempt.id(), reservation.uid(),
			reservation.end(), reservation.status());
		repository.transition(attempt, AttemptState.RESERVED, "cal", "ok",
			"uid=" + reservation.uid() + " status=" + reservation.status(), false);
		attempt = attempt.withState(AttemptState.RESERVED);

		// ------------------------------------------------ the read-back gate --
		// ADR 0007: an internal API changing shape on a write means charging for
		// an appointment that does not exist. So the write is not trusted; what
		// Cal returned is checked against what was asked for before money moves.
		String disagreement = disagreement(reservation, service, request);
		if (disagreement != null) {
			return compensateAndStop(attempt, reservation.uid(), AttemptState.VERIFY_FAILED,
				"read-back disagreed: " + disagreement);
		}

		repository.transition(attempt, AttemptState.VERIFIED, "cal", "ok",
			"read-back agrees", false);
		attempt = attempt.withState(AttemptState.VERIFIED);

		// ---------------------------------------------------------- stage 7 --
		PaymentPort.Charge charge;
		try {
			charge = payments.charge(new PaymentPort.ChargeRequest(
				attempt.idempotencyKey(),
				attempt.providerId(),
				service.stripeAccountId(),
				attempt.priceMinor(),
				attempt.commissionMinor(),
				attempt.currency(),
				attempt.customerEmail(),
				"Booking " + reservation.uid()));
		}
		catch (PaymentPort.PaymentRefused e) {
			// Nothing moved. The reservation can be released cleanly.
			return compensateAndStop(attempt, reservation.uid(), AttemptState.CHARGE_FAILED,
				"payment refused: " + e.getMessage());
		}
		catch (PaymentPort.PaymentUnavailable e) {
			// Money may or may not have moved. Release the slot so it is not
			// stranded, but the attempt still needs a person either way.
			releaseQuietly(attempt, reservation.uid(), "payment outcome unknown");
			repository.transition(attempt, AttemptState.NEEDS_ATTENTION, "stripe", "error",
				"charge outcome unknown: " + e.getMessage(), false);
			return new Outcome(attempt.id(), AttemptState.NEEDS_ATTENTION, reservation.uid(), e.getMessage());
		}

		// The customer may still have to approve this in their bank app. If so
		// the saga stops here and resumes on a webhook — see StripeWebhookController.
		// Returning "sold" now would be a lie that a Swish user discovers at the
		// salon door.
		if (!charge.settled()) {
			repository.recordPaymentIntent(attempt.id(), charge.reference());
			repository.transition(attempt, AttemptState.AWAITING_PAYMENT, "stripe", "ok",
				"awaiting customer action on " + charge.reference(), false);
			return new Outcome(attempt.id(), AttemptState.AWAITING_PAYMENT,
				reservation.uid(), null, charge.clientSecret());
		}

		repository.recordPayment(attempt.id(), charge.reference());
		repository.transition(attempt, AttemptState.CHARGED, "stripe", "ok",
			"charge=" + charge.reference(), false);
		attempt = attempt.withState(AttemptState.CHARGED);

		// ---------------------------------------------------------- stage 8 --
		// Only if there is anything to confirm. An auto-accepting event type
		// already returned ACCEPTED and is already holding the slot, so calling
		// confirm would be asking Cal to repeat itself — and it is the one call
		// in this funnel that needs an authenticated api-v2, which needs a paid
		// Cal licence. Skipping it when it is redundant is what keeps the
		// licence off the critical path.
		if (reservation.awaitingConfirmation()) {
			try {
				cal.confirm(reservation.uid());
			}
			catch (RuntimeException e) {
				// Money has moved against an appointment that is not confirmed.
				// There is no void available, so the compensation is a refund.
				return refundAndStop(attempt, charge.reference(), reservation.uid(), e.getMessage());
			}
		}

		long bookingId = repository.createBooking(
			attempt, reservation.uid(), reservation.start(), reservation.end());
		repository.transition(attempt, AttemptState.CONFIRMED, "cal", "ok",
			"booking=" + bookingId, false);

		// The index is now certainly wrong for this service; say so rather than
		// waiting for Cal's webhook, which is a latency optimisation and not a
		// guarantee.
		availability.markStale(attempt.serviceId());

		return new Outcome(attempt.id(), AttemptState.CONFIRMED, reservation.uid(), null);
	}

	// ------------------------------------------------------------- resuming --

	/**
	 * The customer approved the payment. Finishes the saga.
	 *
	 * <p>Called from a webhook, in a different request from the one that started
	 * this attempt and possibly on a different instance. Idempotent on purpose:
	 * Stripe redelivers on any non-2xx and reorders freely, so "I have already
	 * seen this" has to be a normal answer rather than an error.
	 */
	public Outcome paymentSucceeded(String paymentIntentId, String chargeReference) {
		Attempt attempt = repository.findByPaymentIntent(paymentIntentId).orElse(null);

		if (attempt == null) {
			// Not ours, or ours and already garbage collected. Either way there
			// is nothing to do and nothing wrong.
			log.info("no attempt for payment intent {}", paymentIntentId);
			return null;
		}

		if (attempt.state() != AttemptState.AWAITING_PAYMENT) {
			log.info("attempt {} already in {}, ignoring duplicate success for {}",
				attempt.id(), attempt.state(), paymentIntentId);
			return outcomeOf(attempt);
		}

		repository.recordPayment(attempt.id(), chargeReference);
		repository.transition(attempt, AttemptState.CHARGED, "stripe", "ok",
			"charge=" + chargeReference, false);
		attempt = attempt.withState(AttemptState.CHARGED);

		if (attempt.awaitingConfirmation()) {
			try {
				cal.confirm(attempt.calBookingUid());
			}
			catch (RuntimeException e) {
				return refundAndStop(attempt, chargeReference, attempt.calBookingUid(), e.getMessage());
			}
		}

		long bookingId = repository.createBooking(attempt, attempt.calBookingUid(),
			attempt.slotStart(), attempt.reservedEnd());
		repository.transition(attempt, AttemptState.CONFIRMED, "cal", "ok",
			"booking=" + bookingId, false);

		availability.markStale(attempt.serviceId());

		return new Outcome(attempt.id(), AttemptState.CONFIRMED, attempt.calBookingUid(), null);
	}

	/**
	 * The payment failed or the customer walked away. Releases the slot.
	 *
	 * <p>Also the path the sweeper uses for a checkout nobody ever finished,
	 * which is the common case rather than the exceptional one.
	 */
	public Outcome paymentFailed(String paymentIntentId, String reason) {
		Attempt attempt = repository.findByPaymentIntent(paymentIntentId).orElse(null);

		if (attempt == null || attempt.state() != AttemptState.AWAITING_PAYMENT) {
			return attempt == null ? null : outcomeOf(attempt);
		}

		return releaseAbandoned(attempt, reason);
	}

	/** Shared by the failure webhook and the sweeper. */
	Outcome releaseAbandoned(Attempt attempt, String reason) {
		boolean released = releaseQuietly(attempt, attempt.calBookingUid(), reason);

		AttemptState end = released ? AttemptState.CHARGE_FAILED : AttemptState.NEEDS_ATTENTION;
		repository.transition(attempt, end, "stripe", released ? "refused" : "error", reason, false);

		return new Outcome(attempt.id(), end, attempt.calBookingUid(), reason);
	}

	/**
	 * Checks what Cal returned against what was asked for.
	 *
	 * <p>A 200 is not agreement. Cal could return a booking at a different time,
	 * for a different event type, or in a status that does not hold the slot —
	 * and the last of those is not hypothetical: without
	 * {@code requiresConfirmationWillBlockSlot} a pending booking blocks nothing,
	 * which is invisible in the response body. See ADR 0005.
	 */
	private String disagreement(CalBookingPort.Reservation reservation,
		ServiceForSale service, BookingRequest request) {

		if (!reservation.start().equals(request.slotStart())) {
			return "start " + reservation.start() + " != requested " + request.slotStart();
		}
		if (reservation.calEventTypeId() != service.calEventTypeId()) {
			return "event type " + reservation.calEventTypeId()
				+ " != requested " + service.calEventTypeId();
		}
		if (!Duration.between(reservation.start(), reservation.end())
				.equals(Duration.ofMinutes(service.durationMinutes()))) {
			return "duration is not " + service.durationMinutes() + " minutes";
		}
		String status = reservation.status() == null ? "" : reservation.status().toLowerCase();
		if (!status.equals("pending") && !status.equals("accepted")) {
			return "unexpected status " + reservation.status();
		}
		return null;
	}

	/**
	 * Cancels the reservation and stops.
	 *
	 * <p>If the cancellation itself fails the attempt becomes
	 * {@link AttemptState#NEEDS_ATTENTION} rather than the tidier failure state,
	 * because a slot is now held for a sale that will never complete and no
	 * automated path exists to release it. Reporting the tidy state would be a
	 * lie that hides a blocked calendar.
	 */
	private Outcome compensateAndStop(Attempt attempt, String uid, AttemptState onSuccess, String why) {
		boolean released = releaseQuietly(attempt, uid, why);

		AttemptState end = released ? onSuccess : AttemptState.NEEDS_ATTENTION;
		repository.transition(attempt, end, "cal", released ? "refused" : "error", why, false);

		return new Outcome(attempt.id(), end, uid, why);
	}

	private boolean releaseQuietly(Attempt attempt, String uid, String reason) {
		try {
			cal.cancel(uid, reason);
			repository.note(attempt.id(), attempt.state(), "cal", "ok",
				"reservation released: " + reason, true);
			return true;
		}
		catch (RuntimeException e) {
			log.error("could not release reservation {} for attempt {} — slot is stranded",
				uid, attempt.id(), e);
			repository.note(attempt.id(), attempt.state(), "cal", "error",
				"could not release reservation: " + e.getMessage(), true);
			return false;
		}
	}

	private Outcome refundAndStop(Attempt attempt, String chargeRef, String uid, String why) {
		try {
			payments.refund(chargeRef, "booking could not be confirmed");
			repository.note(attempt.id(), attempt.state(), "stripe", "ok", "refunded", true);
		}
		catch (RuntimeException e) {
			log.error("could not refund {} for attempt {} — customer is out of pocket",
				chargeRef, attempt.id(), e);
			repository.note(attempt.id(), attempt.state(), "stripe", "error",
				"refund failed: " + e.getMessage(), true);
			repository.transition(attempt, AttemptState.NEEDS_ATTENTION, "stripe", "error",
				"confirm failed and refund failed: " + why, false);
			return new Outcome(attempt.id(), AttemptState.NEEDS_ATTENTION, uid, why);
		}

		// The slot is also released; a refunded customer must not leave a
		// reservation behind. Whether that worked decides the ending: the money
		// is back either way, but a stranded reservation still blocks a real
		// slot, and reporting the tidy failure would hide it.
		boolean released = releaseQuietly(attempt, uid, "booking could not be confirmed");

		AttemptState end = released ? AttemptState.CONFIRM_FAILED : AttemptState.NEEDS_ATTENTION;
		repository.transition(attempt, end, "cal", "error", why, false);
		return new Outcome(attempt.id(), end, uid, why);
	}

	private Outcome outcomeOf(Attempt attempt) {
		return new Outcome(attempt.id(), attempt.state(), attempt.calBookingUid(), attempt.failure());
	}

	public record BookingRequest(
		String idempotencyKey,
		long serviceId,
		Instant slotStart,
		String customerName,
		String customerEmail
	) {}

	/**
	 * @param clientSecret present only while the customer still has to approve
	 *        the payment. Safe to return: it authorises confirming this one
	 *        intent and nothing else.
	 */
	public record Outcome(
		long attemptId,
		AttemptState state,
		String calBookingUid,
		String failure,
		String clientSecret
	) {

		public Outcome(long attemptId, AttemptState state, String calBookingUid, String failure) {
			this(attemptId, state, calBookingUid, failure, null);
		}

		public boolean sold() {
			return state == AttemptState.CONFIRMED;
		}

		public boolean awaitingPayment() {
			return state == AttemptState.AWAITING_PAYMENT;
		}

		public boolean needsAttention() {
			return state == AttemptState.NEEDS_ATTENTION;
		}
	}

}
