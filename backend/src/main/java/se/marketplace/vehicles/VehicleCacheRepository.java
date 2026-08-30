package se.marketplace.vehicles;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code vehicle} table: one row per plate ever asked about. */
@Repository
class VehicleCacheRepository {

	private final NamedParameterJdbcTemplate jdbc;

	VehicleCacheRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Cached> find(RegistrationNumber plate) {
		return jdbc.query("""
			SELECT known, make, model, model_year, tyre_front, tyre_rear, source, looked_up_at
			  FROM vehicle
			 WHERE registration_number = :plate
			""",
			new MapSqlParameterSource("plate", plate.value()),
			(ResultSet rs, int n) -> {
				int year = rs.getInt("model_year");
				Integer modelYear = rs.wasNull() ? null : year;
				Vehicle vehicle = rs.getBoolean("known")
					? new Vehicle(rs.getString("make"), rs.getString("model"), modelYear,
						rs.getString("tyre_front"), rs.getString("tyre_rear"))
					: null;
				return new Cached(vehicle, rs.getString("source"),
					rs.getTimestamp("looked_up_at").toInstant());
			}).stream().findFirst();
	}

	/** Writes the answer, found or not, replacing whatever was there. */
	void put(RegistrationNumber plate, Optional<Vehicle> answer, String source) {
		Vehicle vehicle = answer.orElse(null);
		jdbc.update("""
			INSERT INTO vehicle
			  (registration_number, known, make, model, model_year, tyre_front, tyre_rear,
			   source, looked_up_at)
			VALUES (:plate, :known, :make, :model, :year, :front, :rear, :source, now())
			ON CONFLICT (registration_number) DO UPDATE
			   SET known = EXCLUDED.known, make = EXCLUDED.make, model = EXCLUDED.model,
			       model_year = EXCLUDED.model_year, tyre_front = EXCLUDED.tyre_front,
			       tyre_rear = EXCLUDED.tyre_rear, source = EXCLUDED.source,
			       looked_up_at = now()
			""",
			new MapSqlParameterSource()
				.addValue("plate", plate.value())
				.addValue("known", vehicle != null)
				.addValue("make", vehicle == null ? null : vehicle.make())
				.addValue("model", vehicle == null ? null : vehicle.model())
				.addValue("year", vehicle == null ? null : vehicle.modelYear())
				.addValue("front", vehicle == null ? null : vehicle.tyreFront())
				.addValue("rear", vehicle == null ? null : vehicle.tyreRear())
				.addValue("source", source));
	}

	/** @param vehicle null when the registry was asked and did not know the plate */
	record Cached(Vehicle vehicle, String source, Instant lookedUpAt) {

		boolean known() {
			return vehicle != null;
		}

	}

}
