package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.notifications.Notifier;
import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.reviews.Reviews;
import se.marketplace.sync.CalBookingPort;

/**
 * The one property that matters: whatever fails, the customer holds exactly
 * one appointment afterwards.
 */
class BookingRescheduleTest {

	private static final String EMAIL = "anna@example.se";
	private static final String IP = "198.51.100.11";
	private static final Instant OLD = Instant.now().plus(Duration.ofDays(7));
	private static final Instant NEW = OLD.plus(Duration.ofHours(3));

	private FakeRepository repository;
	private BookingLinks links;
	private CalBookingPort cal;
	private Notifier notifier;
	private List<Long> refreshed;
	private BookingReschedule reschedule;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		cal = mock(CalBookingPort.class);
		notifier = mock(Notifier.class);
		refreshed = new ArrayList<>();

		links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		RateLimiter limiter = mock(RateLimiter.class);
		when(limiter.allow(anyString(), anyInt(), any())).thenReturn(true);

		BookingCancellation views = new BookingCancellation(repository, links,
			mock(CalBookingPort.class), mock(se.marketplace.payments.PaymentPort.class),
			notifier, limiter, mock(Reviews.class));

		reschedule = new BookingReschedule(repository, links, cal,
			refreshed::add, notifier, limiter, views);
		ReflectionTestUtils.setField(reschedule, "lookupPerIpPerHour", 30);
		ReflectionTestUtils.setField(reschedule, "timeZone", "Europe/Stockholm");
		ReflectionTestUtils.setField(reschedule, "maxReschedules", 3);
	}

	private void given(Instant startsAt, String status) {
		repository.bookings.put(42L, new BookingRepository.ConsumerBooking(
			42L, 7L, 3L, "old-uid", startsAt, startsAt.plus(Duration.ofMinutes(45)),
			EMAIL, "Anna Andersson", 60000, "SEK", status, 24, null, false,
			"Salong Södermalm", "salong@example.se", "Stockholm", "Klippning", "ch_1", null));
	}

	private CalBookingPort.Reservation held(Instant start) {
		return new CalBookingPort.Reservation("new-uid", start,
			start.plus(Duration.ofMinutes(45)), 3L, "accepted");
	}

	@Test
	@DisplayName("hold the new, release the old, rewrite the row, tell both parties")
	void moved() {
		given(OLD, "confirmed");
		when(cal.reserve(any())).thenReturn(held(NEW));

		var result = reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP);

		assertThat(result).isInstanceOf(BookingReschedule.Moved.class);
		verify(cal).cancel("old-uid", "rescheduled by the customer");
		assertThat(repository.rescheduled).containsExactly("42:old-uid->new-uid");
		assertThat(refreshed).containsExactly(3L);
		verify(notifier).bookingRescheduled(any(), eq(OLD));
		verify(notifier).providerBookingRescheduled(any(), eq("salong@example.se"), eq(OLD));
	}

	@Test
	@DisplayName("a slot that went is a refusal, and the old booking is untouched")
	void slotTaken() {
		given(OLD, "confirmed");
		when(cal.reserve(any())).thenThrow(new CalBookingPort.CalRefused("taken"));

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.SlotTaken.class);
		verify(cal, never()).cancel(anyString(), anyString());
		assertThat(repository.rescheduled).isEmpty();
	}

	@Test
	@DisplayName("if the old hold cannot be released, the new one is given back — never two")
	void oldStuck() {
		given(OLD, "confirmed");
		when(cal.reserve(any())).thenReturn(held(NEW));
		org.mockito.Mockito.doThrow(new RuntimeException("cal down"))
			.when(cal).cancel(eq("old-uid"), anyString());

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.Unavailable.class);
		verify(cal).cancel(eq("new-uid"), anyString());
		assertThat(repository.rescheduled).isEmpty();
	}

	@Test
	@DisplayName("Cal holding a different time than asked is handed back")
	void wrongSlotHeld() {
		given(OLD, "confirmed");
		when(cal.reserve(any())).thenReturn(held(NEW.plus(Duration.ofMinutes(30))));

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.Unavailable.class);
		verify(cal).cancel(eq("new-uid"), anyString());
	}

	@Test
	@DisplayName("past the cutoff the time can no longer be moved")
	void tooLate() {
		given(Instant.now().plus(Duration.ofHours(2)), "confirmed");

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.TooLate.class);
		verify(cal, never()).reserve(any());
	}

	@Test
	@DisplayName("a cancelled booking cannot be moved")
	void cancelled() {
		given(OLD, "cancelled");

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.TooLate.class);
	}

	@Test
	@DisplayName("the fourth move is refused")
	void tooMany() {
		given(OLD, "confirmed");
		repository.rescheduleCount = 3;

		assertThat(reschedule.move(links.tokenFor(42L, EMAIL), NEW, IP))
			.isInstanceOf(BookingReschedule.TooMany.class);
		verify(cal, never()).reserve(any());
	}

	@Test
	@DisplayName("someone else's token is unknown, not forbidden")
	void wrongToken() {
		given(OLD, "confirmed");

		assertThat(reschedule.move(links.tokenFor(42L, "else@example.se"), NEW, IP))
			.isInstanceOf(BookingReschedule.Unknown.class);
	}

	private static final class FakeRepository extends BookingRepository {

		final Map<Long, ConsumerBooking> bookings = new HashMap<>();
		final List<String> rescheduled = new ArrayList<>();
		int rescheduleCount;

		FakeRepository() {
			super(null);
		}

		@Override
		Optional<ConsumerBooking> findBookingForCustomer(long id) {
			return Optional.ofNullable(bookings.get(id));
		}

		@Override
		List<BookingCancellation.Extra> addonsOf(long bookingId) {
			return List.of();
		}

		@Override
		int rescheduleCount(long id) {
			return rescheduleCount;
		}

		@Override
		int reschedule(long id, String oldUid, String newUid, Instant startsAt, Instant endsAt) {
			ConsumerBooking b = bookings.get(id);
			if (b == null || !"confirmed".equals(b.status()) || !b.calBookingUid().equals(oldUid)) {
				return 0;
			}
			rescheduled.add(id + ":" + oldUid + "->" + newUid);
			bookings.put(id, new ConsumerBooking(b.id(), b.providerId(), b.serviceId(), newUid,
				startsAt, endsAt, b.customerEmail(), b.customerName(), b.priceMinor(), b.currency(),
				b.status(), b.cancellationCutoffHours(), b.cancelledAt(), b.needsAttention(),
				b.providerName(), b.providerEmail(), b.city(), b.serviceName(), b.paymentRef(),
				b.registrationNumber()));
			return 1;
		}

		@Override
		Optional<ServiceForSale> findServiceForSale(long serviceId) {
			return Optional.of(new ServiceForSale(3L, 7L, 3L, 60000, "SEK", 45,
				true, true, "bas", "acct_test", true, false));
		}

	}

}
