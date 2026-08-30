package se.marketplace.signup;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class SignupRepository {

	private final NamedParameterJdbcTemplate jdbc;

	SignupRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Retires any registration already in flight for this address.
	 *
	 * <p>Someone who did not receive the first email fills the form in again,
	 * and the link that arrives second has to be the one that works. Superseding
	 * rather than refusing also keeps the partial unique index satisfiable
	 * without the caller having to know it exists.
	 */
	void supersedePending(String email) {
		jdbc.update("""
			UPDATE provider_signup
			   SET state = 'superseded', updated_at = now()
			 WHERE lower(email) = lower(:email) AND state = 'pending'
			""",
			new MapSqlParameterSource("email", email));
	}

	/** Whether a slug is spoken for by a registration that has not finished. */
	boolean slugPending(String slug) {
		Integer count = jdbc.queryForObject("""
			SELECT count(*) FROM provider_signup
			 WHERE slug = :slug AND state IN ('pending', 'verifying')
			""",
			new MapSqlParameterSource("slug", slug), Integer.class);
		return count != null && count > 0;
	}

	long create(New signup) {
		var keys = new GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO provider_signup
			  (email, salon_name, slug, address_line, postal_code, city, category_slug,
			   password_hash, token_hash, expires_at)
			VALUES (:email, :name, :slug, :address, :postal, :city, :category, :hash, :token, :expires)
			""",
			new MapSqlParameterSource()
				.addValue("email", signup.email())
				.addValue("name", signup.salonName())
				.addValue("slug", signup.slug())
				.addValue("address", signup.addressLine())
				.addValue("postal", signup.postalCode())
				.addValue("city", signup.city())
				.addValue("category", signup.category())
				.addValue("hash", signup.passwordHash())
				.addValue("token", signup.tokenHash())
				.addValue("expires", java.sql.Timestamp.from(signup.expiresAt())),
			keys, new String[] { "id" });
		return keys.getKey().longValue();
	}

	/**
	 * Takes the registration this token belongs to, if it is still takeable.
	 *
	 * <p>An {@code UPDATE ... RETURNING} rather than a read followed by a write.
	 * Provisioning takes seconds of HTTP to Cal and to Stripe, so two clicks on
	 * the same link a moment apart would both pass a read-then-check and both
	 * start creating accounts. Moving the row out of a clickable state in one
	 * statement is what makes the second click a no-op.
	 *
	 * <p>{@code failed} is claimable as well as {@code pending}: a registration
	 * whose provisioning fell over on a transient Stripe error is exactly the
	 * one that should work when the person clicks the link again.
	 */
	Optional<Claimed> claim(String tokenHash) {
		return jdbc.query("""
			UPDATE provider_signup
			   SET state = 'verifying',
			       verified_at = COALESCE(verified_at, now()),
			       attempts = attempts + 1,
			       updated_at = now()
			 WHERE token_hash = :token
			   AND state IN ('pending', 'failed')
			   AND expires_at > now()
			RETURNING id, email, salon_name, slug, address_line, postal_code, city, category_slug,
			          password_hash
			""",
			new MapSqlParameterSource("token", tokenHash),
			(ResultSet rs, int n) -> new Claimed(
				rs.getLong("id"),
				rs.getString("email"),
				rs.getString("salon_name"),
				rs.getString("slug"),
				rs.getString("address_line"),
				rs.getString("postal_code"),
				rs.getString("city"),
				rs.getString("category_slug"),
				rs.getString("password_hash"))).stream().findFirst();
	}

	/**
	 * Why a claim came back empty.
	 *
	 * <p>Only ever used to choose the wording shown to someone holding a link
	 * that did not work. "This has already been used" and "this expired" send a
	 * person to different next steps, and a single "invalid link" leaves them
	 * with nowhere to go.
	 */
	String stateOf(String tokenHash) {
		return jdbc.query("SELECT state, expires_at FROM provider_signup WHERE token_hash = :t",
			new MapSqlParameterSource("t", tokenHash),
			(ResultSet rs, int n) -> rs.getTimestamp("expires_at").toInstant().isBefore(Instant.now())
				? "expired"
				: rs.getString("state"))
			.stream().findFirst().orElse("unknown");
	}

	void markCompleted(long id, long providerId) {
		jdbc.update("""
			UPDATE provider_signup
			   SET state = 'completed', provider_id = :pid, failure = NULL, updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource().addValue("id", id).addValue("pid", providerId));
	}

	/**
	 * Records that provisioning did not work, and leaves the link usable.
	 *
	 * <p>The address is proved either way — that is what the click established
	 * and it does not become unproved because Stripe timed out. Returning the
	 * row to a claimable state is what turns a transient outage into a second
	 * click rather than a support ticket.
	 */
	void markFailed(long id, String failure) {
		jdbc.update("""
			UPDATE provider_signup
			   SET state = 'failed', failure = :failure, updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource().addValue("id", id).addValue("failure", failure));
	}

	/**
	 * Releases names held by registrations nobody completed.
	 *
	 * <p>A pending row holds its slug through a unique index, so without this a
	 * single abandoned form reserves a salon's own name against it permanently —
	 * and the salon's second attempt would silently become "klipp-och-co-2".
	 */
	int expire() {
		return jdbc.update("""
			UPDATE provider_signup
			   SET state = 'expired', updated_at = now()
			 WHERE state = 'pending' AND expires_at < now()
			""",
			new MapSqlParameterSource());
	}

	record New(
		String email,
		String salonName,
		String slug,
		String addressLine,
		String postalCode,
		String city,
		String category,
		String passwordHash,
		String tokenHash,
		Instant expiresAt
	) {}

	record Claimed(
		long id,
		String email,
		String salonName,
		String slug,
		String addressLine,
		String postalCode,
		String city,
		/** Null only for rows that predate the field; the import then falls back. */
		String category,
		String passwordHash
	) {}

}
