package se.marketplace.notifications;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers what the outbox owes.
 *
 * <p>The same shape as the availability reconciler and the checkout sweeper,
 * and for the same reason: a timer that reads state and acts on it is the only
 * mechanism that keeps working when everything else has already gone wrong.
 *
 * <p>Transactional so that {@code FOR UPDATE SKIP LOCKED} means something. The
 * rows are held for the length of the batch, which is why the batch is small —
 * a long transaction holding locks while talking to an SMTP server is how a
 * slow mail host becomes a database problem.
 */
@Component
class OutboxDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

	private final OutboxRepository repository;
	private final MailSender mail;

	@Value("${marketplace.notifications.batch-size:20}")
	private int batchSize;

	@Value("${marketplace.notifications.max-attempts:8}")
	private int maxAttempts;

	OutboxDispatcher(OutboxRepository repository, MailSender mail) {
		this.repository = repository;
		this.mail = mail;
	}

	@Scheduled(fixedDelayString = "${marketplace.notifications.interval-ms:15000}")
	@Transactional
	void run() {
		List<OutboxRepository.Pending> due = repository.claimDue(batchSize);

		if (due.isEmpty()) {
			return;
		}

		int sent = 0;
		int deferred = 0;
		int abandoned = 0;

		for (OutboxRepository.Pending message : due) {
			try {
				mail.send(message.recipient(), message.subject(),
					message.bodyText(), message.bodyHtml());
				repository.markSent(message.id());
				sent++;
			}
			catch (MailSender.Rejected e) {
				// The address is wrong and will be wrong next time. Retrying
				// would burn every attempt on a message that cannot arrive.
				repository.markFailed(message.id(), message.attempts(), e.getMessage(), true);
				abandoned++;
				log.warn("notification {} rejected permanently: {}", message.id(), e.getMessage());
			}
			catch (RuntimeException e) {
				boolean lastChance = message.attempts() + 1 >= maxAttempts;
				repository.markFailed(message.id(), message.attempts(), e.toString(), lastChance);

				if (lastChance) {
					abandoned++;
					// Loud, because this is a customer who paid and was never
					// told. Nothing else in the system will notice.
					log.error("giving up on notification {} after {} attempts",
						message.id(), message.attempts() + 1, e);
				}
				else {
					deferred++;
				}
			}
		}

		log.info("dispatched {} notification(s): {} sent, {} deferred, {} abandoned",
			due.size(), sent, deferred, abandoned);
	}

}
