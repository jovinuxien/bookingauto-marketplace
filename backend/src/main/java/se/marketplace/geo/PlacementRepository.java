package se.marketplace.geo;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes the provider's place on the map, and nothing else about it. */
@Repository
class PlacementRepository {

	private final NamedParameterJdbcTemplate jdbc;

	PlacementRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Salons waiting to be placed, oldest attempt first.
	 *
	 * <p>Ordered so a row that has never been tried goes before one already
	 * refused — {@code NULLS FIRST} on the attempt time — and bounded, so a
	 * backlog is worked through over several passes instead of in one burst
	 * against someone else's rate limit.
	 */
	List<Unplaced> needingLocation(int maxAttempts, int limit) {
		return jdbc.query("""
			SELECT id, address_line, postal_code, city, country
			  FROM provider
			 WHERE location IS NULL
			   AND address_line IS NOT NULL
			   AND geocode_attempts < :maxAttempts
			 ORDER BY geocode_attempted_at NULLS FIRST
			 LIMIT :limit
			""",
			new MapSqlParameterSource()
				.addValue("maxAttempts", maxAttempts)
				.addValue("limit", limit),
			(rs, row) -> new Unplaced(
				rs.getLong("id"),
				rs.getString("address_line"),
				rs.getString("postal_code"),
				rs.getString("city"),
				rs.getString("country")));
	}

	/**
	 * Writes the point.
	 *
	 * <p>Guarded on {@code location IS NULL} for operator placements to win:
	 * a person who has placed a salon by hand has looked at a map, and a sweep
	 * that later finds a plausible street match must not quietly move it.
	 */
	int place(long providerId, double latitude, double longitude, String source) {
		return jdbc.update("""
			UPDATE provider
			   SET location = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
			       location_source = :source,
			       located_at = now(),
			       geocode_failure = NULL,
			       updated_at = now()
			 WHERE id = :id
			   AND (location IS NULL OR :source = 'operator')
			""",
			new MapSqlParameterSource()
				.addValue("id", providerId)
				.addValue("lat", latitude)
				.addValue("lon", longitude)
				.addValue("source", source));
	}

	/**
	 * Records that we asked and did not get a usable answer.
	 *
	 * <p>The attempt counter is what eventually stops the sweep and hands the
	 * address to a person, so it is incremented for a refusal and not for an
	 * outage — being unable to reach the geocoder says nothing about the address.
	 */
	void recordFailure(long providerId, String failure) {
		jdbc.update("""
			UPDATE provider
			   SET geocode_attempts = geocode_attempts + 1,
			       geocode_attempted_at = now(),
			       geocode_failure = :failure,
			       updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("id", providerId)
				.addValue("failure", failure));
	}

	/** Everything still waiting on a person, for the operator's list. */
	List<Stranded> stranded(int maxAttempts) {
		return jdbc.query("""
			SELECT id, slug, name, address_line, postal_code, city,
			       geocode_attempts, geocode_failure
			  FROM provider
			 WHERE location IS NULL
			 ORDER BY (geocode_attempts >= :maxAttempts) DESC, id
			""",
			new MapSqlParameterSource("maxAttempts", maxAttempts),
			(rs, row) -> new Stranded(
				rs.getLong("id"),
				rs.getString("slug"),
				rs.getString("name"),
				rs.getString("address_line"),
				rs.getString("postal_code"),
				rs.getString("city"),
				rs.getInt("geocode_attempts"),
				rs.getString("geocode_failure")));
	}

	record Unplaced(long id, String addressLine, String postalCode, String city, String country) {}

	record Stranded(long id, String slug, String name, String addressLine, String postalCode,
		String city, int attempts, String failure) {}

}
