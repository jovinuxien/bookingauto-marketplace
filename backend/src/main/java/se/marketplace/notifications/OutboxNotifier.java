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
			%s
			Behöver du avboka gör du det själv här:
			%s

			%s
			""".formatted(
				notice.customerName(), notice.providerName(), notice.serviceName(),
				when, price(notice), extrasLine(notice), notice.manageUrl(), publicUrl));
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
	public void bookingCancelled(BookingNotice notice, boolean refunded, int cutoffHours) {
		if (refunded) {
			enqueue(notice, "booking_cancelled_refunded",
				"Din bokning hos " + notice.providerName() + " är avbokad",
				"""
				Hej %s,

				Din tid %s hos %s är avbokad, och vi betalar tillbaka %s.

				Återbetalningen syns normalt inom några minuter, beroende på bank.

				Välkommen tillbaka när det passar:
				%s
				""".formatted(notice.customerName(), format(notice), notice.providerName(),
					price(notice), publicUrl));
			return;
		}

		// The money is not coming back, and the message says so in the first
		// sentence rather than at the bottom. Someone who has to read to the end
		// to find that out reads it as having been hidden.
		enqueue(notice, "booking_cancelled",
			"Din bokning hos " + notice.providerName() + " är avbokad",
			"""
			Hej %s,

			Din tid %s hos %s är avbokad.

			Avbokningen skedde senare än %d timmar före besöket, så beloppet
			%s betalas inte tillbaka.

			Välkommen tillbaka när det passar:
			%s
			""".formatted(notice.customerName(), format(notice), notice.providerName(),
				cutoffHours, price(notice), publicUrl));
	}

	@Override
	public void providerBookingCancelled(BookingNotice notice, String providerEmail) {
		// Addressed to the salon, so the times are the salon's and the money is
		// described as what they will not now be paid rather than as a refund.
		enqueueTo(providerEmail, notice, "provider_booking_cancelled",
			"Avbokad tid: " + format(notice),
			"""
			Hej,

			%s har avbokat %s.

			Tiden är ledig igen och kan bokas av någon annan.

			Bokning: %s, %s
			""".formatted(notice.customerName(), format(notice),
				notice.serviceName(), price(notice)));
	}

	@Override
	public void providerBookingConfirmed(BookingNotice notice, String providerEmail) {
		// The plate is the line a workshop reads first, so it is its own line
		// and absent -- not blank -- when the service never asked for one.
		String vehicle = notice.registrationNumber() == null
			? ""
			: "  Fordon: " + plate(notice.registrationNumber()) + "\n";
		// The extras are what the person in the workshop fetches from the shelf.
		vehicle = vehicle + extrasLine(notice);

		enqueueTo(providerEmail, notice, "provider_booking_confirmed",
			"Ny bokning: " + format(notice),
			"""
			Hej,

			%s har bokat %s.

			  %s
			  %s
			%s
			Kund: %s, %s
			""".formatted(notice.customerName(), format(notice),
				notice.serviceName(), price(notice), vehicle,
				notice.customerName(), notice.customerEmail()));
	}

	@Override
	public void bookingCancelledByProvider(BookingNotice notice, boolean refunded) {
		enqueue(notice, "booking_cancelled_by_provider",
			notice.providerName() + " har tyvärr avbokat din tid",
			"""
			Hej %s,

			%s har tyvärr behövt avboka din tid:

			  %s
			  %s

			%s

			Vi beklagar. Boka gärna en ny tid när det passar:
			%s
			""".formatted(notice.customerName(), notice.providerName(),
				notice.serviceName(), format(notice),
				refunded
					? "Hela beloppet, " + price(notice) + ", betalas tillbaka till ditt kort."
					: "Återbetalningen av " + price(notice) + " behandlas manuellt och är på väg.",
				publicUrl));
	}

	@Override
	public void bookingRescheduled(BookingNotice notice, java.time.Instant from) {
		enqueue(notice, "booking_rescheduled",
			"Din tid hos " + notice.providerName() + " är flyttad",
			"""
			Hej %s,

			Din tid är flyttad.

			  %s
			  %s
			  Ny tid: %s
			  (tidigare %s)

			Behöver du ändra något mer gör du det här:
			%s
			""".formatted(notice.customerName(), notice.providerName(), notice.serviceName(),
				format(notice), WHEN.format(from.atZone(ZONE)), notice.manageUrl()));
	}

	@Override
	public void providerBookingRescheduled(BookingNotice notice, String providerEmail,
		java.time.Instant from) {
		enqueueTo(providerEmail, notice, "provider_booking_rescheduled",
			"Flyttad tid: " + format(notice),
			"""
			Hej,

			%s har flyttat sin tid.

			  %s
			  Ny tid: %s
			  Tidigare: %s
			%s
			Den gamla tiden är ledig igen och kan bokas av någon annan.
			""".formatted(notice.customerName(), notice.serviceName(), format(notice),
				WHEN.format(from.atZone(ZONE)), extrasLine(notice)));
	}

	@Override
	public void reviewRequested(BookingNotice notice) {
		enqueue(notice, "review_requested",
			"Hur var det hos " + notice.providerName() + "?",
			"""
			Hej %s,

			Du var hos %s %s. Hur blev det?

			Ge ett betyg -- det tar tio sekunder och hjälper nästa kund att välja:
			%s#omdome

			Bara den som har varit där kan lämna ett omdöme, och det syns med
			ditt förnamn och en initial.
			""".formatted(notice.customerName(), notice.providerName(), format(notice),
				notice.manageUrl()));
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
		enqueueTo(notice.customerEmail(), notice, kind, subject, body);
	}

	/**
	 * The dedupe key is per kind as well as per event, so the salon's copy and
	 * the customer's do not collide — they describe one cancellation and are two
	 * messages, and a shared key would silently deliver whichever was written
	 * first.
	 */
	private void enqueueTo(String recipient, BookingNotice notice, String kind,
		String subject, String body) {

		boolean written = repository.enqueue(new OutboxRepository.Message(
			notice.dedupeKey() + ":" + kind, kind, recipient, subject, body, null,
			notice.bookingId(), notice.providerId()));

		if (!written) {
			log.debug("notification {} already enqueued", notice.dedupeKey());
		}
	}

	private static String format(BookingNotice notice) {
		return WHEN.format(notice.startsAt().atZone(ZONE));
	}

	/** "  Tillval: Spolarvätska, Däckhotell" on its own line, or nothing. */
	private static String extrasLine(BookingNotice notice) {
		return notice.extras() == null || notice.extras().isBlank() ? "" : "  Tillval: " + notice.extras() + "\n";
	}

	/** ABC123 as ABC 123 -- the way it is on the car. Foreign plates as stored. */
	private static String plate(String stored) {
		return stored.matches("[A-Z]{3}[0-9]{2}[0-9A-Z]")
			? stored.substring(0, 3) + " " + stored.substring(3)
			: stored;
	}

	private static String price(BookingNotice notice) {
		return "%d %s".formatted(notice.priceMinor() / 100, notice.currency());
	}

}
