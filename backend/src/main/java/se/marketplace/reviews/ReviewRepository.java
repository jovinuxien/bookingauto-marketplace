package se.marketplace.reviews;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReviewRepository {

	private final NamedParameterJdbcTemplate jdbc;

	ReviewRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** False when this booking already has a review. */
	boolean insert(long bookingId, long providerId, int rating, String comment) {
		try {
			jdbc.update("""
				INSERT INTO review (booking_id, provider_id, rating, comment)
				VALUES (:booking, :provider, :rating, :comment)
				""",
				new MapSqlParameterSource()
					.addValue("booking", bookingId)
					.addValue("provider", providerId)
					.addValue("rating", rating)
					.addValue("comment", comment));
			return true;
		}
		catch (DuplicateKeyException e) {
			return false;
		}
	}

	Optional<Review> forBooking(long bookingId) {
		return jdbc.query(SELECT + " WHERE r.booking_id = :id",
			new MapSqlParameterSource("id", bookingId), REVIEW).stream().findFirst();
	}

	List<Review> recentFor(long providerId, int limit) {
		return jdbc.query(SELECT + " WHERE r.provider_id = :id ORDER BY r.created_at DESC LIMIT :limit",
			new MapSqlParameterSource().addValue("id", providerId).addValue("limit", limit), REVIEW);
	}

	RatingSummary summaryFor(long providerId) {
		return jdbc.query("""
			SELECT avg(rating)::float8 AS average, count(*) AS n FROM review WHERE provider_id = :id
			""",
			new MapSqlParameterSource("id", providerId),
			(ResultSet rs, int n) -> {
				int count = rs.getInt("n");
				double avg = rs.getDouble("average");
				return count == 0 ? RatingSummary.NONE : new RatingSummary(avg, count);
			}).stream().findFirst().orElse(RatingSummary.NONE);
	}

	Optional<Long> providerIdOf(String slug) {
		return jdbc.query("SELECT id FROM provider WHERE slug = :slug AND status = 'active'",
			new MapSqlParameterSource("slug", slug), (rs, n) -> rs.getLong("id")).stream().findFirst();
	}

	// The author is derived here, in one place, so no caller can forget to
	// shorten it: "Anna Andersson" becomes "Anna A.".
	private static final String SELECT = """
		SELECT r.booking_id, r.rating, r.comment, r.created_at, b.customer_name
		  FROM review r JOIN booking b ON b.id = r.booking_id
		""";

	private static final RowMapper<Review> REVIEW = (ResultSet rs, int n) -> new Review(
		rs.getLong("booking_id"),
		rs.getInt("rating"),
		rs.getString("comment"),
		rs.getTimestamp("created_at").toInstant(),
		Reviews.author(rs.getString("customer_name")));

}
