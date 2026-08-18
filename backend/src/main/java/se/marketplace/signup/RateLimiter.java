package se.marketplace.signup;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How often one caller may do one thing.
 *
 * <p>A fixed-window counter in the database. In memory would be faster and
 * would reset on every deploy, which on a machine that deploys often is
 * indistinguishable from not having one — and this is the surface where the
 * cost of being wrong is paid to Stripe and to Cal rather than to us.
 *
 * <p>The window is fixed rather than sliding, which means a caller who waits
 * for a boundary can get two windows' worth back to back. That is understood.
 * The job here is to stop a script creating a thousand accounts, not to smooth
 * a burst of six, and a sliding window costs a row per request to buy precision
 * nothing here needs.
 */
@Component
class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final NamedParameterJdbcTemplate jdbc;

	RateLimiter(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Counts an attempt and says whether it is allowed.
	 *
	 * <p>Counts first and asks afterwards, so a caller who is over the limit
	 * still increments. Someone hammering the endpoint should not reset their
	 * own window by being refused.
	 *
	 * <p><strong>Its own transaction.</strong> The caller's transaction rolls
	 * back on any failure, and a rate limit that unwinds with the request it was
	 * counting is a rate limit an attacker gets for free by making every request
	 * fail.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean allow(String bucket, int limit, Duration window) {
		Instant windowStart = Instant.ofEpochSecond(
			Instant.now().getEpochSecond() / window.toSeconds() * window.toSeconds());

		Integer count = jdbc.queryForObject("""
			INSERT INTO rate_limit (bucket, window_start, count)
			VALUES (:bucket, :window, 1)
			ON CONFLICT (bucket, window_start)
			DO UPDATE SET count = rate_limit.count + 1
			RETURNING count
			""",
			new MapSqlParameterSource()
				.addValue("bucket", bucket)
				.addValue("window", java.sql.Timestamp.from(windowStart)),
			Integer.class);

		boolean allowed = count != null && count <= limit;

		if (!allowed) {
			log.warn("rate limit hit: {} ({} in this window, limit {})", bucket, count, limit);
		}

		return allowed;
	}

	/**
	 * Removes windows that have closed.
	 *
	 * <p>The table is written on every attempt and read by nothing once its
	 * window passes, so without this it is the fastest growing table in the
	 * database and the least interesting.
	 */
	@Scheduled(fixedDelayString = "${marketplace.signup.rate-sweep-ms:3600000}")
	@Transactional
	void sweep() {
		int removed = jdbc.update(
			"DELETE FROM rate_limit WHERE window_start < now() - interval '2 hours'",
			new MapSqlParameterSource());

		if (removed > 0) {
			log.debug("swept {} closed rate limit window(s)", removed);
		}
	}

}
