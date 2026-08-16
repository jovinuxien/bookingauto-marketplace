package se.marketplace.console;

import java.sql.ResultSet;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ProviderUserRepository {

	private final NamedParameterJdbcTemplate jdbc;

	ProviderUserRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Case-insensitive, matching the unique index — see db/005. */
	Optional<ProviderUser> findByEmail(String email) {
		return jdbc.query("""
			SELECT id, provider_id, email, password_hash, display_name, role, active
			  FROM provider_user
			 WHERE lower(email) = lower(:email) AND active
			""",
			new MapSqlParameterSource("email", email),
			(ResultSet rs, int n) -> new ProviderUser(
				rs.getLong("id"),
				rs.getObject("provider_id") == null ? null : rs.getLong("provider_id"),
				rs.getString("email"),
				rs.getString("password_hash"),
				rs.getString("display_name"),
				rs.getString("role"))).stream().findFirst();
	}

	void recordLogin(long id) {
		jdbc.update("UPDATE provider_user SET last_login_at = now() WHERE id = :id",
			new MapSqlParameterSource("id", id));
	}

	long create(Long providerId, String email, String passwordHash, String displayName, String role) {
		var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO provider_user (provider_id, email, password_hash, display_name, role)
			VALUES (:pid, :email, :hash, :name, :role)
			""",
			new MapSqlParameterSource()
				.addValue("pid", providerId)
				.addValue("email", email)
				.addValue("hash", passwordHash)
				.addValue("name", displayName)
				.addValue("role", role),
			keys, new String[] { "id" });
		return keys.getKey().longValue();
	}

	/**
	 * @param providerId null for a platform admin, who belongs to no salon —
	 *        enforced by a check constraint rather than by convention
	 */
	record ProviderUser(
		long id,
		Long providerId,
		String email,
		String passwordHash,
		String displayName,
		String role
	) {}

}
