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

/**
 * Two doors, one thread: the token proves the customer, the session scopes
 * the provider, and each post mails the other party.
 */
class BookingMessagesTest {

	private static final String EMAIL = "anna@example.se";
	private static final String IP = "198.51.100.13";

	private MessageRepository messages;
	private FakeBookings bookings;
	private BookingLinks links;
	private Notifier notifier;
	private BookingMessages thread;

	@BeforeEach
	void setUp() {
		messages = mock(MessageRepository.class);
		bookings = new FakeBookings();
		notifier = mock(Notifier.class);

		links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "test-secret");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");

		RateLimiter limiter = mock(RateLimiter.class);
		when(limiter.allow(anyString(), anyInt(), any())).thenReturn(true);

		thread = new BookingMessages(messages, bookings, links, notifier, limiter);
		ReflectionTestUtils.setField(thread, "lookupPerIpPerHour", 30);

		when(messages.insert(anyLong(), anyString(), anyString())).thenAnswer(call ->
			new BookingMessages.Message(1L, call.getArgument(1), call.getArgument(2), Instant.now()));
	}

	private void given(long providerId) {
		Instant starts = Instant.now().plus(Duration.ofDays(2));
		bookings.rows.put(42L, new BookingRepository.ConsumerBooking(
			42L, providerId, 3L, "cal-uid-1", starts, starts.plus(Duration.ofMinutes(45)),
			EMAIL, "Anna Andersson", 60000, "SEK", "confirmed", 24, null, false,
			"Däckcenter", "verkstad@example.se", "Stockholm", "Däckskifte", "ch_1", null));
	}

	@Test
	@DisplayName("the customer writes with the token, and the workshop is mailed an excerpt")
	void customerPosts() {
		given(7L);

		var result = thread.post(links.tokenFor(42L, EMAIL), "  Kan jag lämna hjulen kvällen innan?  ", IP);

		assertThat(result).isInstanceOf(BookingMessages.Posted.class);
		verify(messages).insert(42L, "customer", "Kan jag lämna hjulen kvällen innan?");
		verify(notifier).messageToProvider(any(), eq("verkstad@example.se"),
			eq("Kan jag lämna hjulen kvällen innan?"));
	}

	@Test
	@DisplayName("the workshop replies in its own booking, and the customer is mailed")
	void providerPosts() {
		given(7L);

		var result = thread.postAsProvider(7L, 42L, "Absolut, ställ dem innanför porten.");

		assertThat(result).containsInstanceOf(BookingMessages.Posted.class);
		verify(messages).insert(42L, "provider", "Absolut, ställ dem innanför porten.");
		verify(notifier).messageToCustomer(any(), eq("Absolut, ställ dem innanför porten."));
	}

	@Test
	@DisplayName("someone else's booking is absent, not forbidden")
	void wrongProvider() {
		given(7L);

		assertThat(thread.postAsProvider(8L, 42L, "hej")).isEmpty();
		assertThat(thread.threadFor(8L, 42L)).isEmpty();
		verify(messages, never()).insert(anyLong(), anyString(), anyString());
	}

	@Test
	@DisplayName("a wrong token is unknown, and nothing is stored")
	void wrongToken() {
		given(7L);

		assertThat(thread.post(links.tokenFor(42L, "else@example.se"), "hej", IP))
			.isInstanceOf(BookingMessages.Unknown.class);
		verify(messages, never()).insert(anyLong(), anyString(), anyString());
	}

	@Test
	@DisplayName("blank and oversized messages are refused with a reason")
	void validation() {
		given(7L);
		String token = links.tokenFor(42L, EMAIL);

		assertThat(thread.post(token, "   ", IP)).isInstanceOf(BookingMessages.Invalid.class);
		assertThat(thread.post(token, "x".repeat(2001), IP)).isInstanceOf(BookingMessages.Invalid.class);
		verify(notifier, never()).messageToProvider(any(), anyString(), anyString());
	}

	@Test
	@DisplayName("the mail carries one line and at most 200 characters")
	void excerpt() {
		assertThat(BookingMessages.excerpt("rad ett\n\nrad två")).isEqualTo("rad ett rad två");
		assertThat(BookingMessages.excerpt("x".repeat(300))).hasSize(200).endsWith("…");
	}

	private static final class FakeBookings extends BookingRepository {

		final Map<Long, ConsumerBooking> rows = new HashMap<>();

		FakeBookings() {
			super(null);
		}

		@Override
		Optional<ConsumerBooking> findBookingForCustomer(long id) {
			return Optional.ofNullable(rows.get(id));
		}

	}

}
