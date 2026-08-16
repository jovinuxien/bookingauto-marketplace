package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.booking.BookingRepository.Attempt;
import se.marketplace.booking.BookingRepository.NewAttempt;
import se.marketplace.booking.BookingRepository.ServiceForSale;
import se.marketplace.payments.PaymentPort;
import se.marketplace.sync.CalBookingPort;

/**
 * The saga, and specifically the paths that only run once something has already
 * gone wrong.
 *
 * <p>The happy path is the easy half and mostly proves the wiring. What matters
 * here is that every partial failure ends somewhere defined, that each one
 * attempts exactly the compensation the design says it should, and — the case
 * that actually costs money — that a <em>failed compensation</em> escalates
 * instead of being reported as a tidy failure. A charge that could not be
 * refunded reported as CHARGE_FAILED is a customer out of pocket and a dashboard
 * that says everything is fine.
 */
class BookingFunnelTest {

	private static final Instant SLOT = Instant.parse("2026-08-17T07:00:00Z");

	private FakeCal cal;
	private FakePayments payments;
	private RecordingRepository repository;
	private List<Object> published;
	private BookingFunnel funnel;

	@BeforeEach
	void setUp() {
		cal = new FakeCal();
		payments = new FakePayments();
		repository = new RecordingRepository();
		published = new ArrayList<>();

		funnel = new BookingFunnel(repository, cal, payments, event -> published.add(event));
		ReflectionTestUtils.setField(funnel, "commissionBps", 1500);
		ReflectionTestUtils.setField(funnel, "timeZone", "Europe/Stockholm");
	}

	private BookingFunnel.Outcome book() {
		return funnel.book(new BookingFunnel.BookingRequest(
			"key-1", 1L, SLOT, "Testkund", "test@example.se"));
	}

	// ------------------------------------------------------------ happy path --

	@Test
	@DisplayName("a sale reserves, verifies, charges, confirms — in that order")
	void happyPath() {
		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.sold()).isTrue();
		assertThat(repository.states())
			.containsExactly(AttemptState.RESERVED, AttemptState.VERIFIED,
				AttemptState.CHARGED, AttemptState.CONFIRMED);
		assertThat(cal.reserved).isEqualTo(1);
		assertThat(cal.cancelled).isEmpty();
		assertThat(payments.charged).isEqualTo(1);
		assertThat(payments.refunded).isEmpty();
	}

	@Test
	@DisplayName("commission is frozen onto the attempt, not read later")
	void commissionIsFrozen() {
		book();
		// 15% of 60000 minor units.
		assertThat(repository.started.commissionMinor()).isEqualTo(9000);
		assertThat(repository.started.priceMinor()).isEqualTo(60000);
	}

	@Test
	@DisplayName("a completed sale announces itself so the index can be refreshed")
	void publishesConfirmation() {
		book();
		assertThat(published).hasSize(1);
		assertThat(published.get(0)).isInstanceOf(BookingFunnel.BookingConfirmed.class);
	}

	// ------------------------------------------------------- stage 6 refusal --

	@Test
	@DisplayName("Cal refusing the slot is a handled path, and records an availability miss")
	void calRefusesTheSlot() {
		cal.refuse = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.state()).isEqualTo(AttemptState.REFUSED);
		assertThat(repository.misses).isEqualTo(1);
		assertThat(payments.charged).isZero();
	}

	@Test
	@DisplayName("an unknown reserve outcome never leads to a charge")
	void unknownReserveOutcomeDoesNotCharge() {
		cal.unavailable = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.needsAttention()).isTrue();
		assertThat(payments.charged).isZero();
		// No uid came back, so there is nothing to cancel — which is exactly why
		// this needs a person rather than a compensation.
		assertThat(cal.cancelled).isEmpty();
	}

	// --------------------------------------------------- the read-back gate --

	@Test
	@DisplayName("a reservation at the wrong time is cancelled, not paid for")
	void readBackDisagreementStopsTheSale() {
		cal.startsAt = SLOT.plus(Duration.ofHours(1));

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.state()).isEqualTo(AttemptState.VERIFY_FAILED);
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
		assertThat(payments.charged).isZero();
	}

	@Test
	@DisplayName("a status that does not hold the slot is treated as disagreement")
	void unexpectedStatusStopsTheSale() {
		cal.status = "cancelled";

		assertThat(book().state()).isEqualTo(AttemptState.VERIFY_FAILED);
		assertThat(payments.charged).isZero();
	}

	// ------------------------------------------------------ stage 7 failures --

	@Test
	@DisplayName("a declined payment releases the slot")
	void declinedPaymentReleasesTheSlot() {
		payments.refuse = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.state()).isEqualTo(AttemptState.CHARGE_FAILED);
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
	}

	@Test
	@DisplayName("a declined payment whose slot cannot be released escalates")
	void failedCompensationEscalates() {
		payments.refuse = true;
		cal.cancelFails = true;

		BookingFunnel.Outcome outcome = book();

		// The tidy answer would be CHARGE_FAILED. It would also be a lie: a real
		// slot is now held for a sale that will never happen, and nothing else
		// will release it.
		assertThat(outcome.needsAttention()).isTrue();
	}

	@Test
	@DisplayName("an unknown payment outcome escalates even though the slot is released")
	void unknownPaymentOutcomeEscalates() {
		payments.unavailable = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.needsAttention()).isTrue();
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
	}

	// ---------------------------------------------- confirmation is optional --

	@Test
	@DisplayName("an already-accepted reservation is not confirmed again")
	void acceptedReservationSkipsConfirm() {
		cal.status = "ACCEPTED";

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.sold()).isTrue();
		// Confirming is the one call that needs an authenticated api-v2, which
		// needs a paid Cal licence. An auto-accepting event type already holds
		// the slot, so making this call would buy nothing and cost that.
		assertThat(cal.confirmed).isEmpty();
	}

	@Test
	@DisplayName("a pending reservation must still be confirmed")
	void pendingReservationIsConfirmed() {
		cal.status = "PENDING";

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.sold()).isTrue();
		assertThat(cal.confirmed).containsExactly("cal-uid-1");
	}

	// ------------------------------------------------------ stage 8 failures --

	@Test
	@DisplayName("a failed confirmation refunds and releases")
	void failedConfirmRefunds() {
		cal.confirmFails = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.state()).isEqualTo(AttemptState.CONFIRM_FAILED);
		assertThat(payments.refunded).containsExactly("charge-1");
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
	}

	@Test
	@DisplayName("a refunded customer whose slot stays stranded still escalates")
	void refundedButStrandedEscalates() {
		cal.confirmFails = true;
		cal.cancelFails = true;

		BookingFunnel.Outcome outcome = book();

		// The money is back, so CONFIRM_FAILED looks defensible. It is not: a
		// real slot is still held for a sale that will never happen.
		assertThat(payments.refunded).containsExactly("charge-1");
		assertThat(outcome.needsAttention()).isTrue();
	}

	@Test
	@DisplayName("a failed confirmation whose refund also fails escalates")
	void failedRefundEscalates() {
		cal.confirmFails = true;
		payments.refundFails = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.needsAttention()).isTrue();
	}

	// ----------------------------------------------------------- idempotency --

	@Test
	@DisplayName("replaying a key returns the first outcome without touching Cal or Stripe")
	void replayIsIdempotent() {
		book();
		int reservesAfterFirst = cal.reserved;
		int chargesAfterFirst = payments.charged;

		BookingFunnel.Outcome replayed = book();

		assertThat(replayed.state()).isEqualTo(AttemptState.CONFIRMED);
		assertThat(cal.reserved).isEqualTo(reservesAfterFirst);
		assertThat(payments.charged).isEqualTo(chargesAfterFirst);
	}

	// ---------------------------------------------------------------- fakes --

	/**
	 * Records transitions and enforces the same legality rule as the real
	 * repository, so an illegal move fails the test rather than passing quietly.
	 */
	private static final class RecordingRepository extends BookingRepository {

		private final List<AttemptState> transitions = new ArrayList<>();
		private Attempt started;
		private Attempt current;
		private int misses;
		private boolean sealed;

		RecordingRepository() {
			super(null);
		}

		List<AttemptState> states() {
			return transitions;
		}

		@Override
		Optional<ServiceForSale> findServiceForSale(long serviceId) {
			return Optional.of(new ServiceForSale(1L, 1L, 1L, 60000, "SEK", 45, true, true));
		}

		@Override
		Optional<Attempt> findByIdempotencyKey(String key) {
			return sealed ? Optional.of(current) : Optional.empty();
		}

		@Override
		Attempt start(NewAttempt request) {
			started = new Attempt(1L, request.idempotencyKey(), request.providerId(),
				request.serviceId(), request.slotStart(), request.priceMinor(),
				request.commissionMinor(), request.currency(), request.customerEmail(),
				request.customerName(), AttemptState.STARTED, null, null, null, null);
			current = started;
			return started;
		}

		@Override
		void transition(Attempt attempt, AttemptState to, String authority,
			String outcome, String detail, boolean compensating) {

			if (!attempt.state().canMoveTo(to)) {
				throw new IllegalStateException("illegal transition " + attempt.state() + " -> " + to);
			}
			transitions.add(to);
			current = attempt.withState(to);
			if (to.isTerminal()) {
				sealed = true;
			}
		}

		@Override
		void note(long attemptId, AttemptState state, String authority,
			String outcome, String detail, boolean compensating) {
			// trail only
		}

		@Override
		void recordCalUid(long attemptId, String uid) {
			current = new Attempt(current.id(), current.idempotencyKey(), current.providerId(),
				current.serviceId(), current.slotStart(), current.priceMinor(),
				current.commissionMinor(), current.currency(), current.customerEmail(),
				current.customerName(), current.state(), uid, current.paymentRef(), null, null);
		}

		@Override
		void recordPayment(long attemptId, String reference) {
			// not asserted
		}

		@Override
		long createBooking(Attempt attempt, String calBookingUid, Instant startsAt, Instant endsAt) {
			// Asserted, not ignored. The first version of this fake accepted
			// anything and so missed a null uid that the real NOT NULL
			// constraint rejected at runtime. A fake laxer than the schema tests
			// nothing.
			if (calBookingUid == null || calBookingUid.isBlank()) {
				throw new IllegalStateException("booking created without a cal uid");
			}
			return 99L;
		}

		@Override
		void recordMiss(long providerId, long serviceId, Instant requestedAt, Integer indexAgeSeconds) {
			misses++;
		}

		@Override
		Integer indexAgeSeconds(long serviceId, Instant slotStart) {
			return 42;
		}
	}

	private static final class FakeCal implements CalBookingPort {

		boolean refuse;
		boolean unavailable;
		boolean cancelFails;
		boolean confirmFails;
		Instant startsAt = SLOT;
		String status = "pending";

		int reserved;
		final List<String> cancelled = new ArrayList<>();

		@Override
		public Reservation reserve(ReservationRequest request) {
			if (refuse) {
				throw new CalRefused("no_available_users_found_error");
			}
			if (unavailable) {
				throw new CalUnavailable("connection reset");
			}
			reserved++;
			return new Reservation("cal-uid-1", startsAt,
				startsAt.plus(Duration.ofMinutes(45)), 1L, status);
		}

		final List<String> confirmed = new ArrayList<>();

		@Override
		public void confirm(String calBookingUid) {
			if (confirmFails) {
				throw new CalUnavailable("api-v2 not deployed");
			}
			confirmed.add(calBookingUid);
		}

		@Override
		public void cancel(String calBookingUid, String reason) {
			if (cancelFails) {
				throw new CalUnavailable("api-v2 not deployed");
			}
			cancelled.add(calBookingUid);
		}
	}

	private static final class FakePayments implements PaymentPort {

		boolean refuse;
		boolean unavailable;
		boolean refundFails;

		int charged;
		final List<String> refunded = new ArrayList<>();

		@Override
		public Charge charge(ChargeRequest request) {
			if (refuse) {
				throw new PaymentRefused("declined");
			}
			if (unavailable) {
				throw new PaymentUnavailable("gateway timeout");
			}
			charged++;
			return new Charge("charge-1", request.amountMinor(), request.currency());
		}

		@Override
		public Refund refund(String chargeRef, String reason) {
			if (refundFails) {
				throw new PaymentUnavailable("refund failed");
			}
			refunded.add(chargeRef);
			return new Refund("refund-1", 0);
		}
	}

}
