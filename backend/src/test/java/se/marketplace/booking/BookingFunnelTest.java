package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import se.marketplace.notifications.Notifier;
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
	private List<Long> refreshed;
	private RecordingNotifier notifier;
	private BookingFunnel funnel;

	@BeforeEach
	void setUp() {
		cal = new FakeCal();
		payments = new FakePayments();
		repository = new RecordingRepository();
		refreshed = new ArrayList<>();
		notifier = new RecordingNotifier();

		BookingLinks links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		funnel = new BookingFunnel(repository, cal, payments,
			serviceId -> refreshed.add(serviceId), notifier, links);
		ReflectionTestUtils.setField(funnel, "commissionBps", 1500);
		ReflectionTestUtils.setField(funnel, "timeZone", "Europe/Stockholm");
		ReflectionTestUtils.setField(funnel, "cancellationCutoffHours", 24);
	}

	private BookingFunnel.Outcome book() {
		return funnel.book(new BookingFunnel.BookingRequest(
			"key-1", 1L, SLOT, "Testkund", "test@example.se", null));
	}

	private BookingFunnel.Outcome bookWithPlate(String plate) {
		return funnel.book(new BookingFunnel.BookingRequest(
			"key-1", 1L, SLOT, "Testkund", "test@example.se", plate));
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

	// ---------------------------------------------------------- the vehicle --

	@Test
	@DisplayName("a workshop's service will not sell without a registration number")
	void plateRequiredWhenTheCategoryAsks() {
		repository.asksVehicle = true;

		assertThatThrownBy(() -> bookWithPlate(" "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("registration number");
		// Refused before anything was frozen or reserved.
		assertThat(repository.started).isNull();
		assertThat(cal.reserved).isZero();
	}

	@Test
	@DisplayName("the plate is normalised onto the attempt, and nothing is looked up")
	void plateIsFrozenAsTyped() {
		repository.asksVehicle = true;

		BookingFunnel.Outcome outcome = bookWithPlate("abc 123");

		assertThat(outcome.sold()).isTrue();
		assertThat(repository.started.registrationNumber()).isEqualTo("ABC123");
	}

	@Test
	@DisplayName("a salon's service ignores a plate rather than storing one")
	void plateIgnoredWhenTheCategoryDoesNotAsk() {
		bookWithPlate("ABC123");

		assertThat(repository.started.registrationNumber()).isNull();
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
	@DisplayName("a completed sale marks the index stale immediately")
	void marksIndexStale() {
		book();
		// Not left to Cal's webhook: the slot we just sold would stay advertised
		// for as long as delivery takes, and webhooks are missed.
		assertThat(refreshed).containsExactly(1L);
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

	// ------------------------------------------- payments that do not settle --

	@Test
	@DisplayName("a payment awaiting the customer does not complete the sale")
	void awaitingPaymentIsNotASale() {
		payments.requiresAction = true;

		BookingFunnel.Outcome outcome = book();

		assertThat(outcome.awaitingPayment()).isTrue();
		assertThat(outcome.sold()).isFalse();
		// The slot stays held and nothing is confirmed. Reporting success here
		// is a lie a Swish customer discovers at the salon door.
		assertThat(cal.confirmed).isEmpty();
		assertThat(cal.cancelled).isEmpty();
		assertThat(outcome.clientSecret()).isEqualTo("secret_1");
	}

	@Test
	@DisplayName("the webhook finishes the sale the request could not")
	void webhookCompletesTheSale() {
		payments.requiresAction = true;
		book();

		BookingFunnel.Outcome outcome = funnel.paymentSucceeded("pi_1", "ch_live_1");

		assertThat(outcome.sold()).isTrue();
		assertThat(refreshed).containsExactly(1L);
	}

	@Test
	@DisplayName("a redelivered success does not sell twice")
	void redeliveredSuccessIsIgnored() {
		payments.requiresAction = true;
		book();
		funnel.paymentSucceeded("pi_1", "ch_live_1");

		BookingFunnel.Outcome again = funnel.paymentSucceeded("pi_1", "ch_live_1");

		// Stripe redelivers on any non-2xx and reorders freely, so this has to be
		// a normal answer rather than a second booking.
		assertThat(again.state()).isEqualTo(AttemptState.CONFIRMED);
		assertThat(refreshed).containsExactly(1L);
	}

	@Test
	@DisplayName("a failed payment releases the slot")
	void webhookFailureReleasesTheSlot() {
		payments.requiresAction = true;
		book();

		BookingFunnel.Outcome outcome = funnel.paymentFailed("pi_1", "customer declined in app");

		assertThat(outcome.state()).isEqualTo(AttemptState.CHARGE_FAILED);
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
	}

	@Test
	@DisplayName("an abandoned checkout releases the slot without any webhook")
	void abandonedCheckoutIsSwept() {
		payments.requiresAction = true;
		book();

		// Nobody ever tells us about a customer who simply walked away, which is
		// why the sweeper is the mechanism and the webhook is the optimisation.
		BookingFunnel.Outcome outcome =
			funnel.releaseAbandoned(repository.current, "checkout abandoned");

		assertThat(outcome.state()).isEqualTo(AttemptState.CHARGE_FAILED);
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
	}

	@Test
	@DisplayName("an unknown payment intent is ignored, not an error")
	void unknownIntentIsIgnored() {
		assertThat(funnel.paymentSucceeded("pi_not_ours", "ch")).isNull();
	}

	// -------------------------------------------------------- what we tell --

	@Test
	@DisplayName("a completed sale tells the customer")
	void confirmedSaleNotifies() {
		book();
		assertThat(notifier.sent).containsExactly("confirmed", "provider-confirmed");
		// With a link to the booking that was just written -- not "null",
		// which is what the in-memory attempt would have said.
		assertThat(notifier.manageUrls.get(0)).contains("/bokning?token=");
	}

	@Test
	@DisplayName("the workshop's copy carries the plate")
	void providerCopyCarriesThePlate() {
		repository.asksVehicle = true;

		bookWithPlate("abc 123");

		assertThat(notifier.sent).containsExactly("confirmed", "provider-confirmed");
		assertThat(notifier.providerRecipients).containsExactly("salong@example.se");
		assertThat(notifier.plates).containsExactly("ABC123", "ABC123");
	}

	@Test
	@DisplayName("a released slot is told about, even though nothing was charged")
	void releasedSlotNotifies() {
		payments.refuse = true;

		book();

		// Silence here is how someone turns up to a salon that is not expecting
		// them: they clicked book and heard nothing.
		assertThat(notifier.sent).containsExactly("released");
	}

	@Test
	@DisplayName("an unresolved attempt is told about, and not asked to retry")
	void needsAttentionNotifies() {
		payments.refuse = true;
        cal.cancelFails = true;

		book();

		assertThat(notifier.sent).containsExactly("attention");
	}

	@Test
	@DisplayName("nothing is sent twice for one event")
	void oneEventOneMessage() {
		payments.requiresAction = true;
		book();
		funnel.paymentSucceeded("pi_1", "ch_1");
		funnel.paymentSucceeded("pi_1", "ch_1");

		// The dedupe key is enforced in the database, but a caller that sends
		// twice still costs an insert and a race; the funnel should not try.
		assertThat(notifier.sent).containsExactly("confirmed", "provider-confirmed");
		assertThat(notifier.keys).containsExactly("attempt:1:confirmed", "attempt:1:confirmed");
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

		/** The cancellation terms each sale was written with. */
		final List<Integer> cutoffs = new ArrayList<>();

		private final List<AttemptState> transitions = new ArrayList<>();
		private Attempt started;
		private boolean asksVehicle;
		Attempt current;
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
			return Optional.of(new ServiceForSale(
				1L, 1L, 1L, 60000, "SEK", 45, true, true, "acct_test", true, asksVehicle));
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
				request.customerName(), AttemptState.STARTED, null, null, null, null, null, null,
				request.registrationNumber());
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
			// Mirrors the real UPDATE, which sets the state column and nothing
			// else. Rebuilding from the caller's copy instead dropped fields
			// recorded since — the uid among them — and made the webhook resume
			// look broken when only the fake was.
			current = current.withState(to);
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
		void recordReservation(long attemptId, String uid, Instant end, String status) {
			current = new Attempt(current.id(), current.idempotencyKey(), current.providerId(),
				current.serviceId(), current.slotStart(), current.priceMinor(),
				current.commissionMinor(), current.currency(), current.customerEmail(),
				current.customerName(), current.state(), uid, current.paymentRef(), null, null,
				end, status, current.registrationNumber());
		}

		@Override
		void recordPaymentIntent(long attemptId, String intentId) {
			intents.put(intentId, attemptId);
		}

		@Override
		Optional<Attempt> findByPaymentIntent(String intentId) {
			return intents.containsKey(intentId) ? Optional.of(current) : Optional.empty();
		}

		private final java.util.Map<String, Long> intents = new java.util.HashMap<>();

		@Override
		void recordPayment(long attemptId, String reference) {
			// not asserted
		}

		@Override
		long createBooking(Attempt attempt, String calBookingUid, Instant startsAt, Instant endsAt,
			int cancellationCutoffHours) {
			// Asserted, not ignored. The first version of this fake accepted
			// anything and so missed a null uid that the real NOT NULL
			// constraint rejected at runtime. A fake laxer than the schema tests
			// nothing.
			if (calBookingUid == null || calBookingUid.isBlank()) {
				throw new IllegalStateException("booking created without a cal uid");
			}
			cutoffs.add(cancellationCutoffHours);
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

		@Override
		String providerName(long providerId) {
			return "Salong Test";
		}

		@Override
		String providerEmail(long providerId) {
			return "salong@example.se";
		}

		@Override
		String serviceName(long serviceId) {
			return "Klippning";
		}
	}

	/** Records what the customer would be told, and under which key. */
	private static final class RecordingNotifier implements Notifier {

		final List<String> providerRecipients = new ArrayList<>();
		final List<String> plates = new ArrayList<>();

		final List<String> sent = new ArrayList<>();
		final List<String> keys = new ArrayList<>();
		final List<String> manageUrls = new ArrayList<>();

		private void record(String kind, BookingNotice notice) {
			sent.add(kind);
			keys.add(notice.dedupeKey());
			manageUrls.add(notice.manageUrl());
			plates.add(notice.registrationNumber());
		}

		@Override
		public void bookingConfirmed(BookingNotice notice) {
			record("confirmed", notice);
		}

		@Override
		public void bookingReleased(BookingNotice notice, String reason) {
			record("released", notice);
		}

		@Override
		public void bookingRefunded(BookingNotice notice, String reason) {
			record("refunded", notice);
		}

		@Override
		public void bookingCancelled(BookingNotice notice, boolean refunded, int cutoffHours) {
			record(refunded ? "cancelled-refunded" : "cancelled", notice);
		}

		@Override
		public void providerBookingCancelled(BookingNotice notice, String providerEmail) {
			record("provider-cancelled", notice);
		}

		@Override
		public void providerBookingConfirmed(BookingNotice notice, String providerEmail) {
			providerRecipients.add(providerEmail);
			record("provider-confirmed", notice);
		}

		@Override
		public void bookingNeedsAttention(BookingNotice notice) {
			record("attention", notice);
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
		boolean requiresAction;

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
			if (requiresAction) {
				return new Charge("pi_1", request.amountMinor(), request.currency(),
					Status.REQUIRES_ACTION, "secret_1");
			}
			return Charge.settled("charge-1", request.amountMinor(), request.currency());
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
