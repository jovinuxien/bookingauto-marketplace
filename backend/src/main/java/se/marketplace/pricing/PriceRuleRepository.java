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
class PriceRuleRepository {

	private final NamedParameterJdbcTemplate jdbc;

	PriceRuleRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<PriceRule> forService(long serviceId) {
		return jdbc.query("""
			SELECT * FROM service_price_rule WHERE service_id = :id ORDER BY id
			""", new MapSqlParameterSource("id", serviceId), RULE);
	}

	/** True when the service belongs to the provider. Every console write checks this first. */
	boolean serviceOwnedBy(long serviceId, long providerId) {
		Integer n = jdbc.queryForObject(
			"SELECT count(*) FROM service WHERE id = :sid AND provider_id = :pid",
			new MapSqlParameterSource().addValue("sid", serviceId).addValue("pid", providerId),
			Integer.class);
		return n != null && n > 0;
	}

	Optional<PriceRule> find(long ruleId) {
		return jdbc.query("SELECT * FROM service_price_rule WHERE id = :id",
			new MapSqlParameterSource("id", ruleId), RULE).stream().findFirst();
	}

	PriceRule insert(long serviceId, PriceRules.NewRule rule) {
		var keys = new GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO service_price_rule
			  (service_id, make, model_prefix, model_year_from, model_year_to,
			   rim_inches_from, rim_inches_to, price_minor, label)
			VALUES (:sid, :make, :model, :yf, :yt, :rf, :rt, :price, :label)
			""",
			new MapSqlParameterSource()
				.addValue("sid", serviceId)
				.addValue("make", rule.make())
				.addValue("model", rule.modelPrefix())
				.addValue("yf", rule.yearFrom())
				.addValue("yt", rule.yearTo())
				.addValue("rf", rule.rimFrom())
				.addValue("rt", rule.rimTo())
				.addValue("price", rule.priceMinor())
				.addValue("label", rule.label()),
			keys, new String[] { "id" });
		return find(keys.getKey().longValue()).orElseThrow();
	}

	/** Deletes only if the rule's service belongs to the provider. */
	int delete(long ruleId, long providerId) {
		return jdbc.update("""
			DELETE FROM service_price_rule r
			 USING service s
			 WHERE r.id = :rid AND s.id = r.service_id AND s.provider_id = :pid
			""",
			new MapSqlParameterSource().addValue("rid", ruleId).addValue("pid", providerId));
	}

	private static final RowMapper<PriceRule> RULE = (ResultSet rs, int n) -> new PriceRule(
		rs.getLong("id"),
		rs.getLong("service_id"),
		rs.getString("make"),
		rs.getString("model_prefix"),
		(Integer) rs.getObject("model_year_from"),
		(Integer) rs.getObject("model_year_to"),
		(Integer) rs.getObject("rim_inches_from"),
		(Integer) rs.getObject("rim_inches_to"),
		rs.getInt("price_minor"),
		rs.getString("label"));

}
