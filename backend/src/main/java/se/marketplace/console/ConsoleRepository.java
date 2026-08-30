package se.marketplace.console;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The console's reads.
 *
 * <p>Every query takes a provider id and filters on it. There is no unscoped
 * read here on purpose — a method that could return another salon's bookings
 * would only need one careless caller to do so.
 */
@Repository
class ConsoleRepository {

	private final NamedParameterJdbcTemplate jdbc;

	ConsoleRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Is the salon sellable, and what has it earned.
	 *
	 * <p>The two payability flags are reported separately rather than as one
	 * "ready" boolean. They fail independently and mean different things: no
	 * services is something the salon fixes in Cal, while payouts disabled is
	 * something only Stripe can clear, and telling someone to "complete setup"
	 * when the ball is not in their court wastes their afternoon.
	 */
	Summary summary(long providerId) {
		return jdbc.queryForObject("""
			SELECT p.name,
			       p.status,
			       p.onboarding_state,
			       p.payouts_enabled,
			       (SELECT count(*) FROM service s WHERE s.provider_id = p.id AND s.active)
			           AS active_services,
			       COALESCE((SELECT sum(b.price_minor - b.commission_minor)
			                   FROM booking b
			                  WHERE b.provider_id = p.id AND b.status = 'confirmed'), 0)
			           AS earned_minor,
			       COALESCE((SELECT sum(b.commission_minor)
			                   FROM booking b
			                  WHERE b.provider_id = p.id AND b.status = 'confirmed'), 0)
			           AS commission_minor,
			       (SELECT count(*) FROM booking b
			         WHERE b.provider_id = p.id AND b.status = 'confirmed'
			           AND b.starts_at >= now()) AS upcoming_count
			  FROM provider p
			 WHERE p.id = :id
			""",
			new MapSqlParameterSource("id", providerId),
			(ResultSet rs, int n) -> new Summary(
				rs.getString("name"),
				rs.getString("status"),
				rs.getString("onboarding_state"),
				rs.getBoolean("payouts_enabled"),
				rs.getInt("active_services"),
				rs.getLong("earned_minor"),
				rs.getLong("commission_minor"),
				rs.getInt("upcoming_count")));
	}

	List<BookingRow> upcomingBookings(long providerId, int days) {
		return jdbc.query("""
			SELECT b.id, b.starts_at, b.ends_at, b.customer_name, b.customer_email,
			       b.price_minor, b.commission_minor, b.currency, b.status,
			       b.cal_booking_uid, s.name AS service_name,
			       b.registration_number, b.vehicle_make, b.vehicle_model, b.vehicle_model_year
			  FROM booking b
			  JOIN service s ON s.id = b.service_id
			 WHERE b.provider_id = :id
			   AND b.starts_at >= now() - interval '1 day'
			   AND b.starts_at < now() + make_interval(days => :days)
			 ORDER BY b.starts_at
			""",
			new MapSqlParameterSource().addValue("id", providerId).addValue("days", days),
			(ResultSet rs, int n) -> new BookingRow(
				rs.getLong("id"),
				rs.getTimestamp("starts_at").toInstant(),
				rs.getTimestamp("ends_at").toInstant(),
				rs.getString("customer_name"),
				rs.getString("customer_email"),
				rs.getInt("price_minor"),
				rs.getInt("commission_minor"),
				rs.getString("currency"),
				rs.getString("status"),
				rs.getString("service_name"),
				rs.getString("cal_booking_uid"),
				rs.getString("registration_number"),
				vehicle(rs)));
	}

	/**
	 * Attempts stuck in {@code NEEDS_ATTENTION}.
	 *
	 * <p>The only state in the funnel that a machine could not resolve: a
	 * compensation itself failed, so a slot may be held for a sale that will
	 * never complete, or a customer may be owed money.
	 */
	List<AttentionRow> needingAttention(long providerId) {
		return jdbc.query("""
			SELECT a.id, a.slot_start, a.customer_email, a.failure, a.updated_at,
			       a.cal_booking_uid, a.payment_ref
			  FROM booking_attempt a
			 WHERE a.provider_id = :id AND a.state = 'NEEDS_ATTENTION'
			 ORDER BY a.updated_at DESC
			 LIMIT 50
			""",
			new MapSqlParameterSource("id", providerId),
			(ResultSet rs, int n) -> new AttentionRow(
				rs.getLong("id"),
				rs.getTimestamp("slot_start").toInstant(),
				rs.getString("customer_email"),
				rs.getString("failure"),
				rs.getTimestamp("updated_at").toInstant(),
				rs.getString("cal_booking_uid"),
				rs.getString("payment_ref")));
	}

	/**
	 * @param earnedMinor     the salon's share of confirmed sales
	 * @param commissionMinor what the platform kept. Shown rather than netted
	 *                        away — a marketplace that hides its own cut invites
	 *                        exactly the argument it is trying to avoid
	 */
	record Summary(
		String name,
		String status,
		String onboardingState,
		boolean payoutsEnabled,
		int activeServices,
		long earnedMinor,
		long commissionMinor,
		int upcomingCount
	) {}

	record BookingRow(
		long id,
		Instant startsAt,
		Instant endsAt,
		String customerName,
		String customerEmail,
		int priceMinor,
		int commissionMinor,
		String currency,
		String status,
		String serviceName,
		String calBookingUid,
		/** As the customer typed it, normalised. Null for a salon's booking. */
		String registrationNumber,
		/** "Volvo V70 (2016)" once the registry has answered; null until then. */
		String vehicle
	) {}

	/** One line or nothing. The columns are filled in together, so make decides. */
	private static String vehicle(ResultSet rs) throws java.sql.SQLException {
		String make = rs.getString("vehicle_make");
		if (make == null) {
			return null;
		}
		String model = rs.getString("vehicle_model");
		int year = rs.getInt("vehicle_model_year");
		String name = model == null ? make : make + " " + model;
		return rs.wasNull() ? name : name + " (" + year + ")";
	}

	record AttentionRow(
		long id,
		Instant slotStart,
		String customerEmail,
		String failure,
		Instant updatedAt,
		String calBookingUid,
		String paymentRef
	) {}

}
