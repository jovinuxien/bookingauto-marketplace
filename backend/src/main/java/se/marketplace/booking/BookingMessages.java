package se.marketplace.booking;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.notifications.Notifier;
import se.marketplace.ratelimit.RateLimiter;

/**
 * The conversation a booking carries (ADR 0019).
 *
 * <p>Two doors, one thread. The customer's door is the signed link — the
 * token in the body is the proof, as for cancel and review — and the
 * provider's is the console session. Each message enqueues one mail to the
 * other party, because both sides live in their mail and the thread's
 * natural cadence is hours, not seconds.
 */
@Service
public class BookingMessages {

	private static final Duration HOUR = Duration.ofHours(1);

	private final MessageRepository messages;
	private final BookingRepository bookings;
	private final BookingLinks links;
	private final Notifier notifier;
	private final RateLimiter limiter;

	@Value("${marketplace.booking.lookup-per-ip-per-hour:30}")
	private int lookupPerIpPerHour;

	BookingMessages(MessageRepository messages, BookingRepository bookings, BookingLinks links,
		Notifier notifier, RateLimiter limiter) {
		this.messages = messages;
		this.bookings = bookings;
		this.links = links;
		this.notifier = notifier;
		this.limiter = limiter;
	}

	// ------------------------------------------------------- the customer --

	public Result thread(String token, String clientIp) {
		if (!limiter.allow("booking:lookup:" + clientIp, lookupPerIpPerHour, HOUR)) {
			return new Throttled();
		}
		return resolve(token)
			.<Result>map(booking -> new Thread(messages.thread(booking.id())))
			.orElseGet(Unknown::new);
	}

	public Result post(String token, String body, String clientIp) {
		if (!limiter.allow("booking:lookup:" + clientIp, lookupPerIpPerHour, HOUR)) {
			return new Throttled();
		}

		BookingRepository.ConsumerBooking booking = resolve(token).orElse(null);
		if (booking == null) {
			return new Unknown();
		}

		String trimmed = validated(body);
		if (trimmed == null) {
			return new Invalid("Skriv något — högst 2 000 tecken.");
		}

		Message message = messages.insert(booking.id(), "customer", trimmed);
		if (booking.providerEmail() != null) {
			notifier.messageToProvider(notice(message, booking), booking.providerEmail(), excerpt(trimmed));
		}
		return new Posted(message);
	}

	// ------------------------------------------------------- the provider --

	/** Empty when the booking is not this provider's — indistinguishable from absent. */
	public Optional<List<Message>> threadFor(long providerId, long bookingId) {
		return owned(providerId, bookingId).map(booking -> messages.thread(bookingId));
	}

	public Optional<Result> postAsProvider(long providerId, long bookingId, String body) {
		return owned(providerId, bookingId).map(booking -> {
			String trimmed = validated(body);
			if (trimmed == null) {
				return new Invalid("Skriv något — högst 2 000 tecken.");
			}
			Message message = messages.insert(bookingId, "provider", trimmed);
			notifier.messageToCustomer(notice(message, booking), excerpt(trimmed));
			return new Posted(message);
		});
	}

	// ------------------------------------------------------------ plumbing --

	private Optional<BookingRepository.ConsumerBooking> resolve(String token) {
		return BookingLinks.claimedBooking(token)
			.flatMap(bookings::findBookingForCustomer)
			.filter(booking -> links.verify(token, booking.id(), booking.customerEmail()));
	}

	private Optional<BookingRepository.ConsumerBooking> owned(long providerId, long bookingId) {
		return bookings.findBookingForCustomer(bookingId)
			.filter(booking -> booking.providerId() == providerId);
	}

	private static String validated(String body) {
		if (body == null) {
			return null;
		}
		String trimmed = body.strip();
		return trimmed.isEmpty() || trimmed.length() > 2000 ? null : trimmed;
	}

	/** The first line and a bit, for the notification mail. The page has the rest. */
	static String excerpt(String body) {
		String oneLine = body.replaceAll("\\s+", " ").strip();
		return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 199).strip() + "…";
	}

	private Notifier.BookingNotice notice(Message message, BookingRepository.ConsumerBooking booking) {
		return new Notifier.BookingNotice(
			"message:" + message.id(),
			booking.customerEmail(), booking.customerName(), booking.providerName(),
			booking.serviceName(), booking.startsAt(), booking.priceMinor(), booking.currency(),
			booking.id(), booking.providerId(),
			links.urlFor(booking.id(), booking.customerEmail()),
			booking.registrationNumber(), null);
	}

	public record Message(long id, String sender, String body, java.time.Instant sentAt) {

		/** For the page: "Du" or the counterparty, decided by the reader's side. */
		public boolean fromCustomer() {
			return "customer".equals(sender.toLowerCase(Locale.ROOT));
		}

	}

	public sealed interface Result permits Thread, Posted, Invalid, Unknown, Throttled {}

	public record Thread(List<Message> messages) implements Result {}

	public record Posted(Message message) implements Result {}

	public record Invalid(String message) implements Result {}

	public record Unknown() implements Result {}

	public record Throttled() implements Result {}

}
