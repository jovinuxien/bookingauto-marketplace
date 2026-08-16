package se.marketplace.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends over SMTP.
 *
 * <p>Conditional on an explicit {@code transport=smtp}, never on the presence of
 * a mail host. A development machine that starts emailing real customers
 * because a config file was copied is a worse accident than one that sends
 * nothing, so sending has to be chosen.
 */
@Component
@ConditionalOnProperty(name = "marketplace.notifications.transport", havingValue = "smtp")
class SmtpMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

	private final JavaMailSender sender;

	@Value("${marketplace.notifications.from:no-reply@example.se}")
	private String from;

	SmtpMailSender(JavaMailSender sender) {
		this.sender = sender;
		log.info("notifications will be sent over SMTP");
	}

	@Override
	public void send(String to, String subject, String bodyText, String bodyHtml) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(bodyText);

		try {
			sender.send(message);
		}
		catch (MailSendException e) {
			// A 5xx from the server means this address will be rejected again
			// tomorrow; a 4xx or a connection failure will not. Telling them
			// apart is what stops eight retries being spent on a typo.
			String detail = String.valueOf(e.getMessage());
			if (detail.contains("550") || detail.contains("553") || detail.contains("5.1.1")) {
				throw new MailSender.Rejected("address rejected: " + detail);
			}
			throw new MailSender.Unavailable("could not send mail", e);
		}
		catch (RuntimeException e) {
			throw new MailSender.Unavailable("could not send mail", e);
		}
	}

}
