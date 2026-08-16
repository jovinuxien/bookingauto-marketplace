package se.marketplace.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes messages to the log instead of sending them.
 *
 * <p>Opt-in via {@code transport=log}. It was briefly the default, which made
 * notifications look broken: they were enqueued and dispatched correctly and
 * simply never arrived anywhere anyone would look.
 */
@Component
@ConditionalOnProperty(name = "marketplace.notifications.transport", havingValue = "log")
class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	LoggingMailSender() {
		log.warn("notifications go to the log — nothing will be delivered");
	}

	@Override
	public void send(String to, String subject, String bodyText, String bodyHtml) {
		log.info("MAIL to={} subject={}\n{}", to, subject, bodyText);
	}

}
