package se.marketplace.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The signup copy.
 *
 * <p>Its own class rather than three more methods on {@link OutboxNotifier},
 * because that one is worth being able to read in a single screen: every line
 * of it is what a customer is told at the worst moment of a purchase. These are
 * a different audience — a salon owner setting up a business — and mixing them
 * makes both harder to read.
 *
 * <p>Enqueuing only, like everything else here. A verification email sent inline
 * would put an SMTP round trip inside the request that created the signup, and
 * make a mail outage able to fail a registration that has otherwise succeeded.
 */
@Service
class SignupOutboxNotifier implements SignupNotifier {

	private static final Logger log = LoggerFactory.getLogger(SignupOutboxNotifier.class);

	private final OutboxRepository repository;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	/** Where the salon builds its services. The welcome mail is useless without it. */
	@Value("${marketplace.cal.base-url:http://localhost:3000}")
	private String calUrl;

	SignupOutboxNotifier(OutboxRepository repository) {
		this.repository = repository;
	}

	@Override
	public void verificationRequested(String email, String salonName, String verifyUrl,
		String dedupeKey) {

		enqueue(dedupeKey, "signup_verification", email,
			"Bekräfta din e-postadress för " + salonName,
			"""
			Hej,

			Någon har registrerat %s hos oss med den här e-postadressen.
			Klicka på länken för att bekräfta att adressen är din:

			%s

			Länken gäller i 24 timmar. Ditt konto skapas först när du klickat —
			innan dess finns ingenting registrerat.

			Var det inte du? Då behöver du inte göra någonting alls. Ingen har
			fått tillgång till din adress och inget konto har skapats.
			""".formatted(salonName, verifyUrl));
	}

	@Override
	public void alreadyRegistered(String email, String dedupeKey) {
		// Deliberately not "you already have an account, log in" as a
		// reprimand. The likeliest reader is someone who forgot they had
		// registered, and the second likeliest is someone who did not try at
		// all -- neither has done anything wrong.
		enqueue(dedupeKey, "signup_exists", email,
			"Du har redan ett konto hos oss",
			"""
			Hej,

			Någon försökte registrera en salong med den här e-postadressen, och
			det finns redan ett konto kopplat till den.

			Logga in här:
			%s/logga-in

			Har du glömt lösenordet, kontakta oss så hjälper vi dig.

			Var det inte du som försökte registrera dig? Då har ingenting hänt
			med ditt konto och du behöver inte göra någonting.
			""".formatted(publicUrl));
	}

	@Override
	public void welcome(String email, String salonName, String calUsername, String calPassword,
		String kycUrl, String dedupeKey) {

		// The Cal credentials go out exactly once, here, and are not stored in
		// plain text anywhere on our side. Two logins is the cost of not having
		// a Cal licence (ADR 0010) and the email has to be honest about it
		// rather than leave someone hunting for a second password they were
		// never told about.
		String calSection = calPassword == null
			? """
			  Du har sedan tidigare ett konto i Cal med användarnamnet %s.
			  Använd lösenordet du fick när det skapades."""
				.formatted(calUsername)
			: """
			  Användarnamn: %s
			  Lösenord: %s

			  Spara det här — vi skickar det bara en gång och kan inte visa det igen."""
				.formatted(calUsername, calPassword);

		enqueue(dedupeKey, "signup_welcome", email,
			"Välkommen — så här kommer " + salonName + " igång",
			"""
			Hej,

			%s är registrerad. Två saker återstår, och båda måste göras av dig.

			1. Verifiera dig hos Stripe

			   Vi kan inte betala ut pengar till salongen förrän det är klart.
			   Det tar några minuter och görs på Stripes sidor:

			   %s

			2. Lägg upp dina tjänster i Cal

			   Kalendern och tiderna sköts i Cal, och det är där du lägger upp
			   vad du säljer, hur lång tid det tar och vad det kostar.

			   %s

			%s

			När båda är klara blir salongen sökbar. Du ser hur långt det gått
			när du loggar in:

			%s/konsol
			""".formatted(salonName, kycUrl, calUrl, calSection, publicUrl));
	}

	private void enqueue(String dedupeKey, String kind, String recipient, String subject,
		String body) {

		boolean written = repository.enqueue(new OutboxRepository.Message(
			dedupeKey, kind, recipient, subject, body, null, null, null));

		if (!written) {
			log.debug("notification {} already enqueued", dedupeKey);
		}
	}

}
