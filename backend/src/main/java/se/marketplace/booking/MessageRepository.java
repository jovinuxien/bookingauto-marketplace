package se.marketplace.booking;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** The thread, oldest first, as one table. */
@Repository
class MessageRepository {

	private final NamedParameterJdbcTemplate jdbc;

	MessageRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<BookingMessages.Message> thread(long bookingId) {
		return jdbc.query(
			"SELECT id, sender, body, created_at FROM booking_message WHERE booking_id = :id ORDER BY id",
			new MapSqlParameterSource("id", bookingId), MESSAGE);
	}

	BookingMessages.Message insert(long bookingId, String sender, String body) {
		var keys = new GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO booking_message (booking_id, sender, body) VALUES (:id, :sender, :body)
			""",
			new MapSqlParameterSource().addValue("id", bookingId)
				.addValue("sender", sender).addValue("body", body),
			keys, new String[] { "id" });
		long id = keys.getKey().longValue();
		return jdbc.query("SELECT id, sender, body, created_at FROM booking_message WHERE id = :id",
			new MapSqlParameterSource("id", id), MESSAGE).get(0);
	}

	private static final RowMapper<BookingMessages.Message> MESSAGE = (ResultSet rs, int n) ->
		new BookingMessages.Message(
			rs.getLong("id"),
			rs.getString("sender"),
			rs.getString("body"),
			rs.getTimestamp("created_at").toInstant());

}
