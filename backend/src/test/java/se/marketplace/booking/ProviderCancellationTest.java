package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import se.marketplace.notifications.Notifier;
import se.marketplace.payments.PaymentPort;
import se.marketplace.sync.CalBookingPort;

/**
 * The inverted rule: when the salon lets go, the customer is made whole,
 * cutoff or no cutoff.
 */
class ProviderCancellationTest {

	private FakeRepository repository;
	private CalBookingPort cal;
	private PaymentPort payments;
	private Notifier notifier;
	private List<Long> refreshed;
	private ProviderCancellation cancellation;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		cal = mock(CalBookingPort.class);
		payments = mock(PaymentPort.class);
		notifier = mock(Notifier.class);
		refreshed = new ArrayList<>();

		BookingLinks links = new BookingLinks();
		org.springframework.test.util.ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		org.springframework.test.util.ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		cancellation = new ProviderCancellation(repository, cal, payments,
			refreshed::add, notifier, links);
	}

	private void given(Instant startsAt, String status, String paymentRef) {
		repository.bookings.put(42L, new BookingRepository.ConsumerBooking(
			42L, 7L, 3L, "cal-uid-1", startsAt, startsAt.plus(Duration.ofMinutes(45)),
			"anna@example.se", "Anna Andersson", 60000, "SEK", status, 24, null, false,
			"Salong Södermalm", "salong@example.se", "Stockholm", "Klippning", paymentRef, null));
	}

	@Test
	@DisplayName("inside the cutoff the customer is still refunded in full")
	void refundIsUnconditional() {
		// Two hours away: a customer cancelling now would get nothing back.
		given(Instant.now().plus(Duration.ofHours(2)), "confirmed", "ch_1");
		when(payments.refund("ch_1", "cancelled by the salon"))
			.thenReturn(new PaymentPort.Refund("re_1", 60000));

		var result = cancellation.cancel(7L, 42L);

		assertThat(result).isEqualTo(new ProviderCancellation.Cancelled(true));
		verify(cal).cancel("cal-uid-1", "cancelled by the salon");
		assertThat(repository.settled).containsExactly("42:refunded:re_1:false");
		assertThat(repository.cancelledBy).containsExactly("42:provider");
		assertThat(refreshed).containsExactly(3L);
		verify(notifier).bookingCancelledByProvider(any(), eq(true));
	}

	@Test
	@DisplayName("someone else's booking is answered as if it did not exist")
	void wrongProvider() {
		given(Instant.now().plus(Duration.ofDays(2)), "confirmed", "ch_1");

		assertThat(cancellation.cancel(8L, 42L)).isInstanceOf(ProviderCancellation.Unknown.class);
		verify(cal, never()).cancel(anyString(), anyString());
	}

	@Test
	@DisplayName("a started appointment cannot be cancelled from the console")
	void tooLate() {
		given(Instant.now().minus(Duration.ofMinutes(5)), "confirmed", "ch_1");

		assertThat(cancellation.cancel(7L, 42L)).isInstanceOf(ProviderCancellation.TooLate.class);
	}

	@Test
	@DisplayName("racing the customer's own cancellation resolves to one")
	void alreadyClaimed() {
		given(Instant.now().plus(Duration.ofDays(2)), "confirmed", "ch_1");
		repository.claimAnswers = false;

		assertThat(cancellation.cancel(7L, 42L)).isInstanceOf(ProviderCancellation.AlreadyCancelled.class);
		verify(cal, never()).cancel(anyString(), anyString());
	}

	@Test
	@DisplayName("a stuck refund still cancels, flags a human, and tells the customer the money is coming")
	void refundStuck() {
		given(Instant.now().plus(Duration.ofDays(2)), "confirmed", "ch_1");
		when(payments.refund(anyString(), anyString())).thenThrow(new RuntimeException("stripe down"));

		assertThat(cancellation.cancel(7L, 42L)).isInstanceOf(ProviderCancellation.RefundStuck.class);
		assertThat(repository.settled).containsExactly("42:cancelled:null:true");
		verify(notifier).bookingCancelledByProvider(any(), eq(false));
	}

	@Test
	@DisplayName("a slot Cal will not release is flagged and nothing is sent")
	void calStuck() {
		given(Instant.now().plus(Duration.ofDays(2)), "confirmed", "ch_1");
		doThrow(new RuntimeException("cal down")).when(cal).cancel(anyString(), anyString());

		assertThat(cancellation.cancel(7L, 42L)).isInstanceOf(ProviderCancellation.Unavailable.class);
		assertThat(repository.settled).containsExactly("42:cancelled:null:true");
		verify(notifier, never()).bookingCancelledByProvider(any(), anyBoolean());
		verify(payments, never()).refund(anyString(), anyString());
	}

	private static final class FakeRepository extends BookingRepository {

		final Map<Long, ConsumerBooking> bookings = new HashMap<>();
		final List<String> settled = new ArrayList<>();
		final List<String> cancelledBy = new ArrayList<>();
		boolean claimAnswers = true;

		FakeRepository() {
			super(null);
		}

		@Override
		Optional<ConsumerBooking> findBookingForCustomer(long id) {
			return Optional.ofNullable(bookings.get(id));
		}

		@Override
		boolean claimForCancellation(long id) {
			return claimAnswers;
		}

		@Override
		void markCancelledBy(long id, String who) {
			cancelledBy.add(id + ":" + who);
		}

		@Override
		void settleCancellation(long id, String status, String refundRef, boolean needsAttention) {
			settled.add(id + ":" + status + ":" + refundRef + ":" + needsAttention);
		}

	}

}
