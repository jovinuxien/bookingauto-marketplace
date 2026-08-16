package se.marketplace.sync;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import se.marketplace.sync.CalPort.DayAvailability;

/**
 * Reads and writes the availability index.
 */
@Repository
class AvailabilityIndexRepository {

	private final NamedParameterJdbcTemplate jdbc;

	AvailabilityIndexRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The services whose index rows are oldest.
	 *
	 * <p>Oldest first, and bounded. A reconciler that tries to refresh
	 * everything it finds stale will, on a bad morning, try to refresh the whole
	 * catalogue at once and take Cal down with it. The batch size is the
	 * back pressure.
	 *
	 * <p>A service with no rows at all counts as maximally stale, which is what
	 * makes this double as the backfill path for a newly onboarded salon.
	 */
	List<StaleService> findStale(int olderThanSeconds, int limit) {
		String sql = """
			SELECT s.id AS service_id, s.cal_event_type_id,
			       COALESCE(MAX(a.computed_at), TIMESTAMPTZ '-infinity') AS newest
			  FROM service s
			  LEFT JOIN availability_day a ON a.service_id = s.id
			 WHERE s.active
			 GROUP BY s.id, s.cal_event_type_id
			HAVING COALESCE(MAX(a.computed_at), TIMESTAMPTZ '-infinity')
			       < now() - make_interval(secs => :age)
			 ORDER BY newest ASC
			 LIMIT :limit
			""";

		return jdbc.query(sql,
			new MapSqlParameterSource()
				.addValue("age", olderThanSeconds)
				.addValue("limit", limit),
			(rs, n) -> new StaleService(rs.getLong("service_id"), rs.getLong("cal_event_type_id")));
	}

	/**
	 * Writes one computed day.
	 *
	 * <p>Upsert, because the reconciler and a webhook can race for the same row
	 * and neither is wrong — the later write simply wins. {@code computed_at}
	 * and {@code source} are what make that traceable afterwards.
	 */
	void upsert(long providerId, long serviceId, DayAvailability day, String source) {
		String sql = """
			INSERT INTO availability_day
			  (provider_id, service_id, day, has_capacity, first_free_at, free_slots,
			   free_morning, free_afternoon, free_evening, computed_at, source)
			VALUES
			  (:providerId, :serviceId, :day, :hasCapacity, :firstFreeAt, :freeSlots,
			   :morning, :afternoon, :evening, now(), :source)
			ON CONFLICT (service_id, day) DO UPDATE SET
			  has_capacity   = EXCLUDED.has_capacity,
			  first_free_at  = EXCLUDED.first_free_at,
			  free_slots     = EXCLUDED.free_slots,
			  free_morning   = EXCLUDED.free_morning,
			  free_afternoon = EXCLUDED.free_afternoon,
			  free_evening   = EXCLUDED.free_evening,
			  computed_at    = now(),
			  source         = EXCLUDED.source
			""";

		jdbc.update(sql, new MapSqlParameterSource()
			.addValue("providerId", providerId)
			.addValue("serviceId", serviceId)
			.addValue("day", day.day())
			.addValue("hasCapacity", day.hasCapacity())
			.addValue("firstFreeAt", day.firstFreeAt() == null ? null : java.sql.Timestamp.from(day.firstFreeAt()))
			.addValue("freeSlots", day.freeSlots())
			.addValue("morning", day.freeMorning())
			.addValue("afternoon", day.freeAfternoon())
			.addValue("evening", day.freeEvening())
			.addValue("source", source));
	}

	Long providerIdOfService(long serviceId) {
		var ids = jdbc.queryForList(
			"SELECT provider_id FROM service WHERE id = :id",
			new MapSqlParameterSource("id", serviceId), Long.class);
		return ids.isEmpty() ? null : ids.get(0);
	}

	Long serviceIdOfCalEventType(long calEventTypeId) {
		var ids = jdbc.queryForList(
			"SELECT id FROM service WHERE cal_event_type_id = :id",
			new MapSqlParameterSource("id", calEventTypeId), Long.class);
		return ids.isEmpty() ? null : ids.get(0);
	}

	/**
	 * Forces a service to look stale so the next reconciler pass picks it up.
	 *
	 * <p>This is what a webhook does. It does <em>not</em> recompute inline:
	 * a burst of bookings would otherwise become a burst of calls to Cal, and
	 * the work is idempotent anyway, so marking and letting the timer coalesce
	 * is both cheaper and calmer.
	 */
	int markStale(long serviceId) {
		return jdbc.update(
			"UPDATE availability_day SET computed_at = TIMESTAMPTZ '-infinity' WHERE service_id = :id",
			new MapSqlParameterSource("id", serviceId));
	}

	record StaleService(long serviceId, long calEventTypeId) {}

}
