package se.marketplace.vehicles;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The sweep's view of the booking table: plates without answers.
 *
 * <p>Reads and writes the vehicle columns on {@code booking} and nothing
 * else. The booking module owns the row; this module owns six columns of it,
 * the way {@code geo} owns {@code provider.location}.
 */
@Repository
class VehicleLookupRepository {

	private final NamedParameterJdbcTemplate jdbc;

	VehicleLookupRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Confirmed bookings with a plate, no make, and attempts to spare.
	 *
	 * <p>Only confirmed, and only upcoming: a cancelled booking's car is not
	 * coming, and a past one has already come. Oldest attempt first, so one
	 * plate the registry keeps refusing does not starve the rest.
	 */
	List<Pending> needingLookup(int maxAttempts, int limit) {
		return jdbc.query("""
			SELECT id, registration_number
			  FROM booking
			 WHERE registration_number IS NOT NULL
			   AND vehicle_make IS NULL
			   AND status = 'confirmed'
			   AND starts_at > now()
			   AND vehicle_lookup_attempts < :maxAttempts
			 ORDER BY vehicle_lookup_attempted_at NULLS FIRST
			 LIMIT :limit
			""",
			new MapSqlParameterSource()
				.addValue("maxAttempts", maxAttempts)
				.addValue("limit", limit),
			(rs, row) -> new Pending(rs.getLong("id"), rs.getString("registration_number")));
	}

	int record(long bookingId, Vehicle vehicle) {
		return jdbc.update("""
			UPDATE booking
			   SET vehicle_make = :make,
			       vehicle_model = :model,
			       vehicle_model_year = :year,
			       vehicle_tyre_front = :tyreFront,
			       vehicle_tyre_rear = :tyreRear,
			       vehicle_lookup_attempts = vehicle_lookup_attempts + 1,
			       vehicle_lookup_attempted_at = now(),
			       vehicle_lookup_failure = NULL,
			       updated_at = now()
			 WHERE id = :id AND vehicle_make IS NULL
			""",
			new MapSqlParameterSource()
				.addValue("id", bookingId)
				.addValue("make", vehicle.make())
				.addValue("model", vehicle.model())
				.addValue("year", vehicle.modelYear())
				.addValue("tyreFront", vehicle.tyreFront())
				.addValue("tyreRear", vehicle.tyreRear()));
	}

	void recordFailure(long bookingId, String reason) {
		jdbc.update("""
			UPDATE booking
			   SET vehicle_lookup_attempts = vehicle_lookup_attempts + 1,
			       vehicle_lookup_attempted_at = now(),
			       vehicle_lookup_failure = :reason,
			       updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("id", bookingId)
				.addValue("reason", reason));
	}

	record Pending(long bookingId, String registrationNumber) {}

}
