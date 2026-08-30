package se.marketplace.pricing;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class AddonRepository {

	private final NamedParameterJdbcTemplate jdbc;

	AddonRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<Addon> activeFor(long serviceId) {
		return jdbc.query("""
			SELECT id, service_id, name, price_minor FROM service_addon
			 WHERE service_id = :id AND active ORDER BY sort_order, id
			""", new MapSqlParameterSource("id", serviceId), ADDON);
	}

	List<Addon> activeByIds(long serviceId, List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.query("""
			SELECT id, service_id, name, price_minor FROM service_addon
			 WHERE service_id = :sid AND active AND id IN (:ids) ORDER BY sort_order, id
			""", new MapSqlParameterSource().addValue("sid", serviceId).addValue("ids", ids), ADDON);
	}

	boolean serviceOwnedBy(long serviceId, long providerId) {
		Integer n = jdbc.queryForObject(
			"SELECT count(*) FROM service WHERE id = :sid AND provider_id = :pid",
			new MapSqlParameterSource().addValue("sid", serviceId).addValue("pid", providerId), Integer.class);
		return n != null && n > 0;
	}

	Addon insert(long serviceId, String name, int priceMinor) {
		var keys = new GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO service_addon (service_id, name, price_minor, sort_order)
			VALUES (:sid, :name, :price,
			        (SELECT COALESCE(max(sort_order), 0) + 1 FROM service_addon WHERE service_id = :sid))
			""",
			new MapSqlParameterSource().addValue("sid", serviceId).addValue("name", name).addValue("price", priceMinor),
			keys, new String[] { "id" });
		long id = keys.getKey().longValue();
		return jdbc.query("SELECT id, service_id, name, price_minor FROM service_addon WHERE id = :id",
			new MapSqlParameterSource("id", id), ADDON).stream().findFirst().orElseThrow();
	}

	/** Retires rather than deletes: bookings that chose it keep their copy either way, but the row stays for history. */
	int retire(long addonId, long providerId) {
		return jdbc.update("""
			UPDATE service_addon a SET active = false
			  FROM service s
			 WHERE a.id = :aid AND s.id = a.service_id AND s.provider_id = :pid AND a.active
			""", new MapSqlParameterSource().addValue("aid", addonId).addValue("pid", providerId));
	}

	Optional<Long> providerOf(long addonId) {
		return jdbc.query("""
			SELECT s.provider_id FROM service_addon a JOIN service s ON s.id = a.service_id WHERE a.id = :id
			""", new MapSqlParameterSource("id", addonId), (rs, n) -> rs.getLong("provider_id")).stream().findFirst();
	}

	private static final RowMapper<Addon> ADDON = (ResultSet rs, int n) -> new Addon(
		rs.getLong("id"), rs.getLong("service_id"), rs.getString("name"), rs.getInt("price_minor"));

}
