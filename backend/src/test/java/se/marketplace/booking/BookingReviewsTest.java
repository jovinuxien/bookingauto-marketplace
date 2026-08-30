package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.reviews.Reviews;

/**
 * Who may rate what. The token proves the customer; this class decides
 * whether there was a visit to rate.
 */
class BookingReviewsTest {

	private static final String EMAIL = "anna@example.se";
	private static final String IP = "198.51.100.9";

	private FakeRepository repository;
	private BookingLinks links;
	private Reviews reviews;
	private BookingReviews bookingReviews;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		reviews = mock(Reviews.class);
		when(reviews.submit(anyLong(), anyLong(), anyInt(), anyString())).thenReturn(true);
		when(reviews.submit(anyLong(), anyLong(), anyInt(), org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

		links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		RateLimiter limiter = mock(RateLimiter.class);
		when(limiter.allow(anyString(), anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(true);

		bookingReviews = new BookingReviews(repository, links, reviews, limiter);
		ReflectionTestUtils.setField(bookingReviews, "lookupPerIpPerHour", 30);
	}

	private void given(Duration endedAgo, String status) {
		Instant end = Instant.now().minus(endedAgo);
		repository.bookings.put(42L, new BookingRepository.ConsumerBooking(
			42L, 7L, 3L, "cal-uid-1", end.minus(Duration.ofMinutes(45)), end,
			EMAIL, "Anna Andersson", 60000, "SEK", status, 24, null, false,
			"Salong Södermalm", "salong@example.se", "Stockholm", "Klippning", "ch_1", null));
	}

	@Test
	@DisplayName("a visit that happened can be rated, once")
	void happened() {
		given(Duration.ofHours(3), "confirmed");

		var result = bookingReviews.submit(links.tokenFor(42L, EMAIL), 5, "Toppen!", IP);

		assertThat(result).isEqualTo(new BookingReviews.Saved(5));
		verify(reviews).submit(42L, 7L, 5, "Toppen!");
	}

	@Test
	@DisplayName("a visit that has not happened yet cannot")
	void notYet() {
		given(Duration.ofHours(-3), "confirmed");

		assertThat(bookingReviews.submit(links.tokenFor(42L, EMAIL), 5, null, IP))
			.isInstanceOf(BookingReviews.NotYet.class);
		verify(reviews, never()).submit(anyLong(), anyLong(), anyInt(), anyString());
	}

	@Test
	@DisplayName("a cancelled appointment is not an experience of the salon")
	void cancelled() {
		given(Duration.ofHours(3), "cancelled");

		assertThat(bookingReviews.submit(links.tokenFor(42L, EMAIL), 1, "kom aldrig", IP))
			.isInstanceOf(BookingReviews.NotYet.class);
	}

	@Test
	@DisplayName("a token for someone else's booking is unknown, not forbidden")
	void wrongToken() {
		given(Duration.ofHours(3), "confirmed");

		assertThat(bookingReviews.submit(links.tokenFor(42L, "someone@else.se"), 5, null, IP))
			.isInstanceOf(BookingReviews.Unknown.class);
	}

	@Test
	@DisplayName("a second rating of the same visit is a conflict, not an overwrite")
	void twice() {
		given(Duration.ofHours(3), "confirmed");
		when(reviews.submit(anyLong(), anyLong(), anyInt(), anyString())).thenReturn(false);

		assertThat(bookingReviews.submit(links.tokenFor(42L, EMAIL), 4, "bra", IP))
			.isInstanceOf(BookingReviews.AlreadyReviewed.class);
	}

	@Test
	@DisplayName("what the reviews module refuses comes back as the reason")
	void invalid() {
		given(Duration.ofHours(3), "confirmed");
		when(reviews.submit(anyLong(), anyLong(), anyInt(), anyString()))
			.thenThrow(new IllegalArgumentException("Betyget är 1 till 5."));

		assertThat(bookingReviews.submit(links.tokenFor(42L, EMAIL), 9, "x", IP))
			.isEqualTo(new BookingReviews.Invalid("Betyget är 1 till 5."));
	}

	private static final class FakeRepository extends BookingRepository {

		final Map<Long, ConsumerBooking> bookings = new HashMap<>();

		FakeRepository() {
			super(null);
		}

		@Override
		Optional<ConsumerBooking> findBookingForCustomer(long id) {
			return Optional.ofNullable(bookings.get(id));
		}

	}

}
