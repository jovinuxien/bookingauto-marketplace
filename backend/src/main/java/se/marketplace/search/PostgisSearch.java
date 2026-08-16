package se.marketplace.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Search over the availability index, in PostGIS.
 *
 * <p>This answers the filter problem — near me, free on a day, in a category —
 * and nothing else. It reads {@code availability_day}, never Cal: answering one
 * search by asking Cal would mean one availability computation per provider.
 *
 * <p>The result is a shortlist, not a promise. Cal is asked the real question
 * for the handful of providers that come back, and only Cal confirms a booking.
 */
@Component
class PostgisSearch implements SearchPort {

	private static final String SQL = """
		SELECT p.id, p.slug, p.name, p.city,
		       ST_Distance(p.location, ST_MakePoint(:lon, :lat)::geography)::int AS distance_m,
		       s.name AS service_name, s.duration_minutes, s.price_minor, s.currency,
		       a.free_slots, a.first_free_at,
		       EXTRACT(EPOCH FROM (now() - a.computed_at))::bigint AS index_age_s
		  FROM availability_day a
		  JOIN service  s ON s.id = a.service_id
		  JOIN provider p ON p.id = a.provider_id
		 WHERE a.day = :day
		   AND a.has_capacity
		   AND s.active
		   AND p.status = 'active'
		   -- Cast is load-bearing. Without it, an omitted category reaches
		   -- Postgres as an untyped NULL in "$n IS NULL", which it cannot
		   -- infer a type for, and the whole query fails at prepare time with
		   -- "could not determine data type of parameter". The bug only
		   -- appears when the filter is left out, so it hides from any test
		   -- that always passes a category.
		   AND (CAST(:category AS text) IS NULL OR s.category_slug = :category)
		   AND (:anyTime OR
		        (:morning   AND a.free_morning)   OR
		        (:afternoon AND a.free_afternoon) OR
		        (:evening   AND a.free_evening))
		   AND ST_DWithin(p.location, ST_MakePoint(:lon, :lat)::geography, :radius)
		 ORDER BY distance_m
		 LIMIT :limit
		""";

	private final NamedParameterJdbcTemplate jdbc;

	PostgisSearch(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<SearchHit> nearby(SearchRequest request) {
		PartOfDay part = request.partOfDay() == null ? PartOfDay.ANY : request.partOfDay();
		LocalDate day = request.day() == null ? LocalDate.now() : request.day();

		MapSqlParameterSource params = new MapSqlParameterSource()
			.addValue("lat", request.latitude())
			.addValue("lon", request.longitude())
			.addValue("radius", request.radiusMetres())
			.addValue("category", request.categorySlug())
			.addValue("day", day)
			.addValue("anyTime", part == PartOfDay.ANY)
			.addValue("morning", part == PartOfDay.MORNING)
			.addValue("afternoon", part == PartOfDay.AFTERNOON)
			.addValue("evening", part == PartOfDay.EVENING)
			.addValue("limit", request.limit() <= 0 ? 20 : request.limit());

		return jdbc.query(SQL, params, MAPPER);
	}

	private static final RowMapper<SearchHit> MAPPER = (ResultSet rs, int rowNum) -> new SearchHit(
		rs.getLong("id"),
		rs.getString("slug"),
		rs.getString("name"),
		rs.getString("city"),
		rs.getInt("distance_m"),
		rs.getString("service_name"),
		rs.getInt("duration_minutes"),
		rs.getInt("price_minor"),
		rs.getString("currency"),
		rs.getInt("free_slots"),
		toInstant(rs),
		rs.getLong("index_age_s")
	);

	private static java.time.Instant toInstant(ResultSet rs) throws SQLException {
		var ts = rs.getTimestamp("first_free_at");
		return ts == null ? null : ts.toInstant();
	}

}
