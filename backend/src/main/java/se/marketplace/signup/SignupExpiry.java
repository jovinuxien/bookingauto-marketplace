package se.marketplace.signup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets go of names nobody came back for.
 *
 * <p>A pending registration holds its slug through a unique index, which is the
 * right behaviour while someone is reading their email and the wrong behaviour
 * forever. Without this, one abandoned form reserves a salon's own name against
 * it permanently, and the salon's second attempt quietly becomes
 * {@code klipp-och-co-2} for a reason nobody could ever find.
 */
@Component
class SignupExpiry {

	private static final Logger log = LoggerFactory.getLogger(SignupExpiry.class);

	private final SignupRepository repository;

	SignupExpiry(SignupRepository repository) {
		this.repository = repository;
	}

	@Scheduled(fixedDelayString = "${marketplace.signup.expiry-sweep-ms:900000}")
	@Transactional
	void run() {
		int expired = repository.expire();

		if (expired > 0) {
			log.info("expired {} unverified signup(s)", expired);
		}
	}

}
