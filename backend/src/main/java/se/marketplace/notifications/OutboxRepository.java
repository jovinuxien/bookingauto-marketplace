package se.marketplace.notifications;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OutboxRepository {

	private final NamedParameterJdbcTemplate jdbc;

	OutboxRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return false if this message was already enqueued
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} rather than a prior existence check.
	 * The duplicate this guards against is two concurrent deliveries of the same
	 * webhook, and a read-then-write loses exactly that race.
	 */
	boolean enqueue(Message message) {
		int written = jdbc.update("""
			INSERT INTO notification_outbox
			  (dedupe_key, kind, recipient, subject, body_text, body_html, booking_id, provider_id)
			VALUES (:key, :kind, :to, :subject, :text, :html, :bookingId, :providerId)
			ON CONFLICT (dedupe_key) DO NOTHING
			""",
			new MapSqlParameterSource()
				.addValue("key", message.dedupeKey())
				.addValue("kind", message.kind())
				.addValue("to", message.recipient())
				.addValue("subject", message.subject())
				.addValue("text", message.bodyText())
				.addValue("html", message.bodyHtml())
				.addValue("bookingId", message.bookingId())
				.addValue("providerId", message.providerId()));
		return written > 0;
	}

	/**
	 * Messages due for delivery.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} so more than one instance can dispatch
	 * without sending the same message twice. Without it, two dispatchers on a
	 * timer will eventually overlap and someone gets their confirmation twice.
	 */
	List<Pending> claimDue(int limit) {
		return jdbc.query("""
			SELECT id, kind, recipient, subject, body_text, body_html, attempts
			  FROM notification_outbox
			 WHERE sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= now()
			 ORDER BY next_attempt_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""",
			new MapSqlParameterSource("limit", limit),
			(ResultSet rs, int n) -> new Pending(
				rs.getLong("id"), rs.getString("kind"), rs.getString("recipient"),
				rs.getString("subject"), rs.getString("body_text"), rs.getString("body_html"),
				rs.getInt("attempts")));
	}

	void markSent(long id) {
		jdbc.update("UPDATE notification_outbox SET sent_at = now(), attempts = attempts + 1 "
			+ "WHERE id = :id", new MapSqlParameterSource("id", id));
	}

	/**
	 * Records a failure and schedules the retry.
	 *
	 * <p>Backoff is exponential and capped. A mail server that is down stays
	 * down for minutes, not milliseconds, and retrying every second turns one
	 * outage into a second problem.
	 */
	void markFailed(long id, int attempts, String error, boolean terminal) {
		long delaySeconds = Math.min(3600, (long) Math.pow(3, Math.min(attempts, 7)));

		jdbc.update("""
			UPDATE notification_outbox
			   SET attempts = attempts + 1,
			       last_error = :error,
			       next_attempt_at = now() + make_interval(secs => :delay),
			       failed_at = CASE WHEN :terminal THEN now() ELSE NULL END
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("error", error)
				.addValue("delay", delaySeconds)
				.addValue("terminal", terminal));
	}

	record Message(
		String dedupeKey, String kind, String recipient, String subject,
		String bodyText, String bodyHtml, Long bookingId, Long providerId
	) {}

	record Pending(
		long id, String kind, String recipient, String subject,
		String bodyText, String bodyHtml, int attempts
	) {}

}
