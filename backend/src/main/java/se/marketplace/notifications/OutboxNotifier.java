package se.marketplace.notifications;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes the message, and stops there.
 *
 * <p>Enqueuing only. It is called from inside the booking saga, and sending an
 * email in that path would put an SMTP round trip between a customer and their
 * confirmation page — and worse, make a mail outage able to fail a sale that
 * has already taken money.
 *
 * <p>The copy lives here rather than in templates because there is very little
 * of it and every line is a decision about what a person is told at the worst
 * moment of their interaction with us. It is worth being able to read all of it
 * at once.
 */
@Service
class OutboxNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(OutboxNotifier.class);

	private static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");
	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("EEEE d MMMM 'kl' HH:mm", Locale.forLanguageTag("sv-SE"));

	private final OutboxRepository repository;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	OutboxNotifier(OutboxRepository repository) {
		this.repository = repository;
	}

	@Override
	public void bookingConfirmed(BookingNotice notice) {
		String when = format(notice);
		enqueue(notice, "booking_confirmed",
			"Din tid hos " + notice.providerName() + " är bokad",
			"""
			Hej %s,

			Din tid är bokad.

			  %s
			  %s
			  %s
			  %s

			Behöver du avboka, svara på det här mejlet så hjälper salongen dig.

			%s
			""".formatted(
				notice.customerName(), notice.providerName(), notice.serviceName(),
				when, price(notice), publicUrl));
	}

	@Override
	public void bookingReleased(BookingNotice notice, String reason) {
		// No money moved, and saying so plainly is the whole message. Someone
		// who clicked "book" and got silence will otherwise assume they have an
		// appointment.
		enqueue(notice, "booking_released",
			"Din bokning hos " + notice.providerName() + " blev inte klar",
			"""
			Hej %s,

			Tiden %s hos %s kunde tyvärr inte bokas, och den är nu ledig igen.

			Ingen betalning har genomförts.

			Du kan välja en annan tid här:
			%s
			""".formatted(notice.customerName(), format(notice), notice.providerName(), publicUrl));
	}

	@Override
	public void bookingRefunded(BookingNotice notice, String reason) {
		enqueue(notice, "booking_refunded",
			"Återbetalning för din bokning hos " + notice.providerName(),
			"""
			Hej %s,

			Din bokning %s hos %s kunde inte slutföras, och vi har betalat
			tillbaka %s.

			Återbetalningen syns normalt inom några minuter, beroende på bank.

			%s
			""".formatted(notice.customerName(), format(notice), notice.providerName(),
				price(notice), publicUrl));
	}

	@Override
	public void bookingNeedsAttention(BookingNotice notice) {
		// Deliberately no "try again". This state means a compensation itself
		// failed, so a second attempt could take a second payment.
		enqueue(notice, "booking_needs_attention",
			"Vi kontrollerar din bokning hos " + notice.providerName(),
			"""
			Hej %s,

			Något gick fel när din bokning %s hos %s skulle slutföras, och vi
			kontrollerar den manuellt just nu.

			Boka inte om samma tid än — vi hör av oss så snart vi vet mer.
			""".formatted(notice.customerName(), format(notice), notice.providerName()));
	}

	private void enqueue(BookingNotice notice, String kind, String subject, String body) {
		boolean written = repository.enqueue(new OutboxRepository.Message(
			notice.dedupeKey(), kind, notice.customerEmail(), subject, body, null,
			notice.bookingId(), notice.providerId()));

		if (!written) {
			log.debug("notification {} already enqueued", notice.dedupeKey());
		}
	}

	private static String format(BookingNotice notice) {
		return WHEN.format(notice.startsAt().atZone(ZONE));
	}

	private static String price(BookingNotice notice) {
		return "%d %s".formatted(notice.priceMinor() / 100, notice.currency());
	}

}
