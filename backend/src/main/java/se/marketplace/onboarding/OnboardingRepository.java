package se.marketplace.onboarding;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class OnboardingRepository {

	private final NamedParameterJdbcTemplate jdbc;

	OnboardingRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	long create(String slug, String name, String city, String addressLine, String postalCode,
		String email, String defaultCategory, Double longitude, Double latitude) {

		var keys = new GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO provider (slug, name, city, address_line, postal_code, contact_email,
			                      default_category_slug, status, onboarding_state, location)
			VALUES (:slug, :name, :city, :address, :postal, :email, :category, 'draft', 'started',
			        -- Cast, because Postgres cannot infer a parameter's type from
			        -- "IS NULL" alone and refuses the whole statement with "could
			        -- not determine data type of parameter $7". It only bites when
			        -- the coordinates are actually null, which no operator-typed
			        -- provider ever was and every self-serve one is.
			        CASE WHEN CAST(:lon AS double precision) IS NULL THEN NULL
			             ELSE ST_MakePoint(CAST(:lon AS double precision),
			                               CAST(:lat AS double precision))::geography END)
			""",
			new MapSqlParameterSource()
				.addValue("slug", slug)
				.addValue("name", name)
				.addValue("city", city)
				.addValue("address", addressLine)
				.addValue("postal", postalCode)
				.addValue("email", email)
				.addValue("category", defaultCategory)
				.addValue("lon", longitude)
				.addValue("lat", latitude),
			keys, new String[] { "id" });
		return keys.getKey().longValue();
	}

	int setPlan(long providerId, String plan) {
		return jdbc.update("UPDATE provider SET plan = :plan, updated_at = now() WHERE id = :id",
			new MapSqlParameterSource().addValue("id", providerId).addValue("plan", plan));
	}

	Optional<Provider> find(long id) {
		return jdbc.query("SELECT * FROM provider WHERE id = :id",
			new MapSqlParameterSource("id", id), PROVIDER).stream().findFirst();
	}

	Optional<Provider> findBySlug(String slug) {
		return jdbc.query("SELECT * FROM provider WHERE slug = :s",
			new MapSqlParameterSource("s", slug), PROVIDER).stream().findFirst();
	}

	Optional<Provider> findByStripeAccount(String accountId) {
		return jdbc.query("SELECT * FROM provider WHERE stripe_account_id = :a",
			new MapSqlParameterSource("a", accountId), PROVIDER).stream().findFirst();
	}

	void recordCalUser(long providerId, long calUserId, String username) {
		jdbc.update("""
			UPDATE provider
			   SET cal_user_id = :uid, cal_username = :name,
			       onboarding_state = 'cal_created', updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("uid", calUserId).addValue("name", username).addValue("id", providerId));
	}

	void recordStripeAccount(long providerId, String accountId) {
		jdbc.update("""
			UPDATE provider
			   SET stripe_account_id = :a, onboarding_state = 'awaiting_kyc', updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource().addValue("a", accountId).addValue("id", providerId));
	}

	void recordPayability(long providerId, boolean payoutsEnabled, String blockedReason) {
		jdbc.update("""
			UPDATE provider
			   SET payouts_enabled = :ok,
			       onboarding_state = CASE
			           WHEN :ok THEN onboarding_state
			           WHEN :reason IS NOT NULL THEN 'blocked'
			           ELSE 'awaiting_kyc' END,
			       updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("ok", payoutsEnabled)
				.addValue("reason", blockedReason)
				.addValue("id", providerId));
	}

	/**
	 * Makes the provider sellable.
	 *
	 * <p>Guarded in SQL rather than only in the service, because this is the one
	 * update that can put the table into its worst state. The database refuses an
	 * active provider without payouts anyway; this makes the attempt a no-op
	 * instead of an exception.
	 */
	int activate(long providerId) {
		return jdbc.update("""
			UPDATE provider
			   SET status = 'active', onboarding_state = 'ready', updated_at = now()
			 WHERE id = :id
			   AND stripe_account_id IS NOT NULL
			   AND payouts_enabled
			   AND EXISTS (SELECT 1 FROM service s WHERE s.provider_id = provider.id AND s.active)
			""",
			new MapSqlParameterSource("id", providerId));
	}

	void deactivate(long providerId, String reason) {
		jdbc.update("""
			UPDATE provider SET status = 'suspended', onboarding_state = 'blocked', updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource("id", providerId));
	}

	/** Upsert on cal_event_type_id, so re-importing is safe and cheap. */
	int importService(long providerId, long calEventTypeId, String name,
		String categorySlug, int durationMinutes, int priceMinor, String currency) {
		return jdbc.update("""
			INSERT INTO service
			  (provider_id, cal_event_type_id, name, category_slug, duration_minutes, price_minor, currency)
			VALUES (:pid, :etid, :name, :cat, :dur, :price, :cur)
			ON CONFLICT (cal_event_type_id) DO UPDATE
			   SET name = EXCLUDED.name,
			       duration_minutes = EXCLUDED.duration_minutes,
			       price_minor = EXCLUDED.price_minor,
			       updated_at = now()
			""",
			new MapSqlParameterSource()
				.addValue("pid", providerId)
				.addValue("etid", calEventTypeId)
				.addValue("name", name)
				.addValue("cat", categorySlug)
				.addValue("dur", durationMinutes)
				.addValue("price", priceMinor)
				.addValue("cur", currency));
	}

	private static final RowMapper<Provider> PROVIDER = (ResultSet rs, int n) -> new Provider(
		rs.getLong("id"),
		rs.getString("slug"),
		rs.getString("name"),
		rs.getString("status"),
		rs.getString("onboarding_state"),
		rs.getString("contact_email"),
		// cal_user_id is int4 because that is what Cal's own id is. Neither
		// casting getObject to Long nor asking getObject for Long works — the
		// driver refuses to widen — so it is read and null-checked by hand.
		nullableLong(rs, "cal_user_id"),
		rs.getString("cal_username"),
		rs.getString("stripe_account_id"),
		rs.getBoolean("payouts_enabled"),
		rs.getString("default_category_slug"));

	private static Long nullableLong(ResultSet rs, String column) throws java.sql.SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	record Provider(
		long id,
		String slug,
		String name,
		String status,
		String onboardingState,
		String contactEmail,
		Long calUserId,
		String calUsername,
		String stripeAccountId,
		boolean payoutsEnabled,
		/** Chosen at signup. Null for providers that predate the question. */
		String defaultCategory
	) {}

}
