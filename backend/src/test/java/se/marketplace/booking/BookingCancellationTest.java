package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.notifications.Notifier;
import se.marketplace.payments.PaymentPort;
import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.sync.CalBookingPort;

/**
 * Undoing a sale.
 *
 * <p>Three claims, and none of them is "cancelling works" — that is visible the
 * first time anyone clicks the button.
 *
 * <p><strong>It cannot pay twice.</strong> Two clicks, two tabs, a phone and a
 * laptop: the second one has to find the cancellation already owned. The guard
 * is a conditional update rather than a status check in Java, and a test is the
 * only thing that keeps it that way — moving the check into Java compiles,
 * reads as equivalent, and refunds twice.
 *
 * <p><strong>Cal is asked before Stripe.</strong> The order decides what a
 * failure leaves behind, and the wrong one is invisible until a salon holds an
 * empty chair for a customer who has their money back and an appointment they
 * believe is cancelled.
 *
 * <p><strong>Nothing is silently kept.</strong> Every path where money does not
 * come back either says so to the customer beforehand or flags the booking
 * afterwards.
 */
class BookingCancellationTest {

	private static final String IP = "198.51.100.7";
	private static final String EMAIL = "anna@example.se";

	private FakeRepository repository;
	private FakeCal cal;
	private FakePayments payments;
	private RecordingNotifier notifier;
	private BookingLinks links;
	private BookingCancellation cancellation;

	/** What each authority was asked, in the order it was asked. */
	private List<String> calls;

	@BeforeEach
	void setUp() {
		calls = new ArrayList<>();

		repository = new FakeRepository();
		cal = new FakeCal(calls);
		payments = new FakePayments(calls);
		notifier = new RecordingNotifier();

		links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		cancellation = new BookingCancellation(repository, links, cal, payments, notifier,
			new PermissiveLimiter(), org.mockito.Mockito.mock(se.marketplace.reviews.Reviews.class));
		ReflectionTestUtils.setField(cancellation, "lookupPerIpPerHour", 120);
	}

	/** A confirmed booking, by default a week away and therefore refundable. */
	private BookingRepository.ConsumerBooking booking(Duration awayFromNow) {
		return new BookingRepository.ConsumerBooking(
			42L, 7L, 3L, "cal-uid-1",
			Instant.now().plus(awayFromNow),
			Instant.now().plus(awayFromNow).plus(Duration.ofMinutes(45)),
			EMAIL, "Anna Andersson",
			60000, "SEK", "confirmed",
			24, null, false,
			"Salong Södermalm", "salong@example.se", "Stockholm",
			"Klippning", "ch_1", null);
	}

	private void given(BookingRepository.ConsumerBooking booking) {
		repository.bookings.put(booking.id(), booking);
	}

	private String token() {
		return links.tokenFor(42L, EMAIL);
	}

	private BookingCancellation.Result cancel() {
		return cancellation.cancel(token(), IP);
	}

	// ------------------------------------------------------ the free window --

	@Test
	@DisplayName("cancelling in time releases the slot and returns the money")
	void inTime() {
		given(booking(Duration.ofDays(7)));

		var result = cancel();

		assertThat(result).isInstanceOf(BookingCancellation.Cancelled.class);
		assertThat(((BookingCancellation.Cancelled) result).refunded()).isTrue();

		assertThat(cal.cancelled).containsExactly("cal-uid-1");
		assertThat(payments.refunded).containsExactly("ch_1");
		assertThat(repository.settled).containsExactly("refunded/re_1/attention=false");
		assertThat(notifier.sent).containsExactly("cancelled-refunded", "provider-cancelled");
	}

	@Test
	@DisplayName("cancelling after the cutoff frees the slot and keeps the money")
	void afterTheCutoff() {
		// Twelve hours away, against a 24 hour cutoff.
		given(booking(Duration.ofHours(12)));

		var result = cancel();

		assertThat(result).isInstanceOf(BookingCancellation.Cancelled.class);
		assertThat(((BookingCancellation.Cancelled) result).refunded()).isFalse();

		// The slot still comes back. A customer who cannot cancel simply does
		// not turn up, and refusing costs the salon the chair.
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
		assertThat(payments.refunded).isEmpty();
		assertThat(repository.settled).containsExactly("cancelled/null/attention=false");
		assertThat(notifier.sent).containsExactly("cancelled", "provider-cancelled");
	}

	@Test
	@DisplayName("the page is told what cancelling would cost before it is clicked")
	void refundabilityIsVisibleUpFront() {
		given(booking(Duration.ofHours(12)));

		var found = (BookingCancellation.Found) cancellation.lookup(token(), IP);

		assertThat(found.booking().cancellable()).isTrue();
		assertThat(found.booking().refundable()).isFalse();
		assertThat(found.booking().cutoffHours()).isEqualTo(24);
		assertThat(found.booking().freeUntil()).isBefore(Instant.now());
	}

	@Test
	@DisplayName("an appointment that has already started is refused")
	void tooLate() {
		given(booking(Duration.ofHours(-1)));

		assertThat(cancel()).isInstanceOf(BookingCancellation.TooLate.class);

		assertThat(cal.cancelled).isEmpty();
		assertThat(payments.refunded).isEmpty();
		assertThat(repository.claims).isZero();
	}

	// -------------------------------------------------------- paying twice --

	@Test
	@DisplayName("a second click refunds nothing and is still a success")
	void cancellingTwice() {
		given(booking(Duration.ofDays(7)));

		assertThat(cancel()).isInstanceOf(BookingCancellation.Cancelled.class);

		// The row now reads cancelled, which is what the second caller sees.
		var second = cancel();

		assertThat(second).isInstanceOf(BookingCancellation.Found.class);
		assertThat(payments.refunded).containsExactly("ch_1");
		assertThat(notifier.sent).containsExactly("cancelled-refunded", "provider-cancelled");
	}

	@Test
	@DisplayName("two clicks at once: only the one that claims the row does the work")
	void concurrentClicks() {
		given(booking(Duration.ofDays(7)));

		// Both callers read a confirmed booking — the state a status check in
		// Java would have let both past — and the claim is what separates them.
		repository.refuseNextClaim = true;

		var loser = cancel();

		assertThat(loser).isInstanceOf(BookingCancellation.Found.class);
		assertThat(cal.cancelled).isEmpty();
		assertThat(payments.refunded).isEmpty();
		assertThat(notifier.sent).isEmpty();
	}

	// ------------------------------------------------------------ the order --

	@Test
	@DisplayName("the slot is released before the money is returned")
	void calBeforeStripe() {
		given(booking(Duration.ofDays(7)));

		cancel();

		// Not an aesthetic preference. Refund-first means a failing Cal leaves a
		// live appointment nobody expects to be kept, and nothing finds out
		// until the salon holds an empty chair.
		assertThat(calls).containsExactly("cal.cancel", "stripe.refund");
	}

	@Test
	@DisplayName("if the slot cannot be released, nothing is refunded and it is flagged")
	void calUnavailable() {
		given(booking(Duration.ofDays(7)));
		cal.fail = true;

		var result = cancel();

		assertThat(result).isInstanceOf(BookingCancellation.Unavailable.class);
		assertThat(payments.refunded).isEmpty();
		assertThat(repository.settled).containsExactly("cancelled/null/attention=true");

		// Not told the money is coming, because it is not.
		assertThat(notifier.sent).isEmpty();
	}

	@Test
	@DisplayName("a failed refund still cancels, and says a person is on it")
	void refundFails() {
		given(booking(Duration.ofDays(7)));
		payments.fail = true;

		var result = cancel();

		assertThat(result).isInstanceOf(BookingCancellation.RefundStuck.class);

		// The appointment is gone, which is what was asked for. The money is not
		// back, which is flagged rather than lost.
		assertThat(cal.cancelled).containsExactly("cal-uid-1");
		assertThat(repository.settled).containsExactly("cancelled/null/attention=true");
		assertThat(((BookingCancellation.RefundStuck) result).booking().needsAttention()).isTrue();
		assertThat(notifier.sent).containsExactly("cancelled", "provider-cancelled");
	}

	@Test
	@DisplayName("a booking that was never charged cancels cleanly")
	void nothingToRefund() {
		var unpaid = booking(Duration.ofDays(7));
		given(new BookingRepository.ConsumerBooking(
			unpaid.id(), unpaid.providerId(), unpaid.serviceId(), unpaid.calBookingUid(),
			unpaid.startsAt(), unpaid.endsAt(), unpaid.customerEmail(), unpaid.customerName(),
			unpaid.priceMinor(), unpaid.currency(), unpaid.status(),
			unpaid.cancellationCutoffHours(), null, false,
			unpaid.providerName(), unpaid.providerEmail(), unpaid.city(),
			unpaid.serviceName(), null, null));

		var result = cancel();

		assertThat(result).isInstanceOf(BookingCancellation.Cancelled.class);
		assertThat(((BookingCancellation.Cancelled) result).refunded()).isFalse();
		assertThat(repository.settled).containsExactly("cancelled/null/attention=false");
	}

	// ------------------------------------------------------- who may cancel --

	@Test
	@DisplayName("a token that does not sign the booking opens nothing")
	void forgedToken() {
		given(booking(Duration.ofDays(7)));

		assertThat(cancellation.cancel("42.notarealsignature", IP))
			.isInstanceOf(BookingCancellation.Unknown.class);
		assertThat(cancellation.lookup("42.notarealsignature", IP))
			.isInstanceOf(BookingCancellation.Unknown.class);

		assertThat(cal.cancelled).isEmpty();
		assertThat(repository.claims).isZero();
	}

	@Test
	@DisplayName("a valid token for one booking does not open the next one")
	void tokensDoNotTravel() {
		given(booking(Duration.ofDays(7)));

		var neighbour = booking(Duration.ofDays(7));
		repository.bookings.put(43L, new BookingRepository.ConsumerBooking(
			43L, neighbour.providerId(), neighbour.serviceId(), "cal-uid-2",
			neighbour.startsAt(), neighbour.endsAt(), "someone.else@example.se", "Berit",
			neighbour.priceMinor(), neighbour.currency(), "confirmed",
			24, null, false, neighbour.providerName(), neighbour.providerEmail(),
			neighbour.city(), neighbour.serviceName(), "ch_2", null));

		// The id is what the caller controls; the signature is what they cannot
		// produce for it.
		String edited = "43." + token().substring(token().indexOf('.') + 1);

		assertThat(cancellation.cancel(edited, IP)).isInstanceOf(BookingCancellation.Unknown.class);
		assertThat(cal.cancelled).isEmpty();
	}

	@Test
	@DisplayName("a booking nobody can name is answered the same as a bad signature")
	void unknownBooking() {
		// No booking 42 at all. Told apart from a forgery nowhere the caller can
		// see, or the endpoint becomes a way to ask which ids were ever sold.
		assertThat(cancellation.lookup(token(), IP)).isInstanceOf(BookingCancellation.Unknown.class);
	}

	@Test
	@DisplayName("over the limit is refused before the booking is read")
	void throttled() {
		given(booking(Duration.ofDays(7)));
		cancellation = new BookingCancellation(repository, links, cal, payments, notifier,
			new RefusingLimiter(),
			org.mockito.Mockito.mock(se.marketplace.reviews.Reviews.class));
		ReflectionTestUtils.setField(cancellation, "lookupPerIpPerHour", 120);

		assertThat(cancellation.lookup(token(), IP)).isInstanceOf(BookingCancellation.Throttled.class);
		assertThat(cancellation.cancel(token(), IP)).isInstanceOf(BookingCancellation.Throttled.class);

		assertThat(repository.reads).isZero();
		assertThat(cal.cancelled).isEmpty();
	}

	// -------------------------------------------------------- who is told --

	@Test
	@DisplayName("the salon hears about it too")
	void salonIsNotified() {
		given(booking(Duration.ofDays(7)));

		cancel();

		// The salon is the other party to the sale and the only one who can
		// still sell the slot. Telling only the customer leaves the chair empty.
		assertThat(notifier.recipients).contains("salong@example.se");
		assertThat(notifier.manageUrls).allMatch(url -> url != null && url.contains("/bokning?token="));
	}

	@Test
	@DisplayName("a salon with no address on file does not stop the cancellation")
	void salonWithoutEmail() {
		var b = booking(Duration.ofDays(7));
		given(new BookingRepository.ConsumerBooking(
			b.id(), b.providerId(), b.serviceId(), b.calBookingUid(), b.startsAt(), b.endsAt(),
			b.customerEmail(), b.customerName(), b.priceMinor(), b.currency(), b.status(),
			b.cancellationCutoffHours(), null, false, b.providerName(), null, b.city(),
			b.serviceName(), b.paymentRef(), null));

		assertThat(cancel()).isInstanceOf(BookingCancellation.Cancelled.class);
		assertThat(notifier.sent).containsExactly("cancelled-refunded");
	}

	// ----------------------------------------------------------- the fakes --

	private static final class FakeRepository extends BookingRepository {

		final Map<Long, ConsumerBooking> bookings = new HashMap<>();
		final List<String> settled = new ArrayList<>();
		int claims;
		int reads;
		boolean refuseNextClaim;

		private FakeRepository() {
			super(null);
		}

		@Override
		List<BookingCancellation.Extra> addonsOf(long bookingId) {
			return List.of();
		}

		@Override
		Optional<ConsumerBooking> findBookingForCustomer(long id) {
			reads++;
			return Optional.ofNullable(bookings.get(id));
		}

		@Override
		void markCancelledBy(long id, String who) {
			// recorded nowhere: who cancelled is asserted through the notices
		}

		@Override
		boolean claimForCancellation(long id) {
			claims++;

			if (refuseNextClaim) {
				refuseNextClaim = false;
				return false;
			}

			ConsumerBooking current = bookings.get(id);

			// The conditional update, faithfully: it only succeeds from
			// 'confirmed', which is the whole guarantee the real one provides.
			if (current == null || !current.confirmed()) {
				return false;
			}

			bookings.put(id, withStatus(current, "cancelled", true));
			return true;
		}

		@Override
		void settleCancellation(long id, String status, String refundRef, boolean needsAttention) {
			settled.add(status + "/" + refundRef + "/attention=" + needsAttention);
			bookings.computeIfPresent(id, (key, current) ->
				withStatus(current, status, needsAttention));
		}

		private static ConsumerBooking withStatus(ConsumerBooking b, String status,
			boolean needsAttention) {

			return new ConsumerBooking(b.id(), b.providerId(), b.serviceId(), b.calBookingUid(),
				b.startsAt(), b.endsAt(), b.customerEmail(), b.customerName(), b.priceMinor(),
				b.currency(), status, b.cancellationCutoffHours(), Instant.now(), needsAttention,
				b.providerName(), b.providerEmail(), b.city(), b.serviceName(), b.paymentRef(),
				b.registrationNumber());
		}
	}

	private static final class FakeCal implements CalBookingPort {

		private final List<String> calls;
		final List<String> cancelled = new ArrayList<>();
		boolean fail;

		private FakeCal(List<String> calls) {
			this.calls = calls;
		}

		@Override
		public Reservation reserve(ReservationRequest request) {
			throw new UnsupportedOperationException("not part of cancelling");
		}

		@Override
		public void confirm(String calBookingUid) {
			throw new UnsupportedOperationException("not part of cancelling");
		}

		@Override
		public void cancel(String calBookingUid, String reason) {
			calls.add("cal.cancel");

			if (fail) {
				throw new IllegalStateException("cal is unreachable");
			}

			cancelled.add(calBookingUid);
		}
	}

	private static final class FakePayments implements PaymentPort {

		private final List<String> calls;
		final List<String> refunded = new ArrayList<>();
		boolean fail;

		private FakePayments(List<String> calls) {
			this.calls = calls;
		}

		@Override
		public Charge charge(ChargeRequest request) {
			throw new UnsupportedOperationException("not part of cancelling");
		}

		@Override
		public Refund refund(String chargeRef, String reason) {
			calls.add("stripe.refund");

			if (fail) {
				throw new PaymentUnavailable("stripe is unreachable");
			}

			refunded.add(chargeRef);
			return new Refund("re_1", 60000);
		}
	}

	private static final class RecordingNotifier implements Notifier {

		final List<String> sent = new ArrayList<>();
		final List<String> recipients = new ArrayList<>();
		final List<String> manageUrls = new ArrayList<>();

		@Override
		public void bookingCancelled(BookingNotice notice, boolean refunded, int cutoffHours) {
			sent.add(refunded ? "cancelled-refunded" : "cancelled");
			recipients.add(notice.customerEmail());
			manageUrls.add(notice.manageUrl());
		}

		@Override
		public void providerBookingConfirmed(BookingNotice notice, String providerEmail) {
			sent.add("provider-confirmed");
		}

		@Override
		public void providerBookingCancelled(BookingNotice notice, String providerEmail) {
			sent.add("provider-cancelled");
			recipients.add(providerEmail);
			manageUrls.add(notice.manageUrl());
		}

		@Override
		public void bookingConfirmed(BookingNotice notice) {
		}

		@Override
		public void bookingReleased(BookingNotice notice, String reason) {
		}

		@Override
		public void bookingRefunded(BookingNotice notice, String reason) {
		}

		@Override
		public void bookingCancelledByProvider(BookingNotice notice, boolean refunded) {
			// not exercised here
		}

		@Override
		public void bookingRescheduled(BookingNotice notice, java.time.Instant from) {
			// not exercised here
		}

		@Override
		public void providerBookingRescheduled(BookingNotice notice, String providerEmail,
			java.time.Instant from) {
			// not exercised here
		}

		@Override
		public void messageToProvider(BookingNotice notice, String providerEmail, String excerpt) {
			// not exercised here
		}

		@Override
		public void messageToCustomer(BookingNotice notice, String excerpt) {
			// not exercised here
		}

		@Override
		public void reviewRequested(BookingNotice notice) {
			// not exercised here
		}

		@Override
		public void bookingNeedsAttention(BookingNotice notice) {
		}
	}

	private static class PermissiveLimiter extends RateLimiter {

		private PermissiveLimiter() {
			super(null);
		}

		@Override
		public boolean allow(String bucket, int limit, Duration window) {
			return true;
		}
	}

	private static final class RefusingLimiter extends PermissiveLimiter {

		@Override
		public boolean allow(String bucket, int limit, Duration window) {
			return false;
		}
	}

}
