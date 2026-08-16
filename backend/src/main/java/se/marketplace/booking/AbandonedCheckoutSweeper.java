package se.marketplace.booking;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import se.marketplace.booking.BookingRepository.Attempt;

/**
 * Releases slots held by checkouts nobody finished.
 *
 * <p>Abandonment is the ordinary case, not an edge case. People open the Swish
 * app, get distracted, and never come back — and Stripe sends no webhook for a
 * customer who simply stopped. Every one of those attempts is holding a real
 * appointment slot that no automated path would otherwise release.
 *
 * <p>Which makes this the same shape as the availability reconciler: the
 * mechanism, with webhooks as a latency optimisation over it. If Stripe's
 * failure webhooks stopped arriving entirely, slots would still come back —
 * later, but they would come back.
 *
 * <p>The window is a product decision, not a technical one. Too short and a
 * customer who paid slowly finds their slot resold; too long and popular times
 * sit blocked by people who left. It errs long, because reselling a slot
 * somebody has just paid for is the worse failure by a wide margin.
 */
@Component
class AbandonedCheckoutSweeper {

	private static final Logger log = LoggerFactory.getLogger(AbandonedCheckoutSweeper.class);

	private final BookingRepository repository;
	private final BookingFunnel funnel;

	@Value("${marketplace.checkout.abandon-after-seconds:900}")
	private int abandonAfterSeconds;

	@Value("${marketplace.checkout.sweep-batch-size:50}")
	private int batchSize;

	AbandonedCheckoutSweeper(BookingRepository repository, BookingFunnel funnel) {
		this.repository = repository;
		this.funnel = funnel;
	}

	@Scheduled(fixedDelayString = "${marketplace.checkout.sweep-interval-ms:60000}")
	void run() {
		List<Attempt> abandoned = repository.findAbandoned(abandonAfterSeconds, batchSize);

		if (abandoned.isEmpty()) {
			return;
		}

		int released = 0;
		int stuck = 0;

		for (Attempt attempt : abandoned) {
			try {
				// A failed release lands the attempt in NEEDS_ATTENTION rather
				// than being retried forever, because the slot is genuinely
				// stranded and someone has to know.
				var outcome = funnel.releaseAbandoned(attempt,
					"checkout abandoned after " + abandonAfterSeconds + "s");
				if (outcome.needsAttention()) {
					stuck++;
				}
				else {
					released++;
				}
			}
			catch (Exception e) {
				// One bad attempt must not stop the sweep; it stays
				// AWAITING_PAYMENT and is picked up on the next pass.
				stuck++;
				log.warn("could not release abandoned attempt {}: {}", attempt.id(), e.toString());
			}
		}

		log.info("swept {} abandoned checkout(s): {} released, {} stuck", abandoned.size(), released, stuck);
	}

}
