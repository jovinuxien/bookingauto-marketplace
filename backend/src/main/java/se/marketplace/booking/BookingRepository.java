package se.marketplace.booking;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the funnel's records.
 *
 * <p>Every state change goes through {@link #transition}, which writes the trail
 * row and the new state together. Keeping them in one method is what stops the
 * two from drifting: a state that changed without a step to explain it is
 * exactly the gap that makes a stuck attempt unexplainable afterwards.
 */
@Repository
class BookingRepository {

	private final NamedParameterJdbcTemplate jdbc;

	BookingRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Starts an attempt, or returns the existing one for this idempotency key.
	 *
	 * <p>The uniqueness is enforced by the database rather than by a check-then-
	 * insert, because the race this guards against — a customer double-clicking
	 * checkout — is precisely the one a read-then-write loses.
	 */
	Optional<Attempt> findByIdempotencyKey(String key) {
		List<Attempt> found = jdbc.query(
			"SELECT * FROM booking_attempt WHERE idempotency_key = :key",
			new MapSqlParameterSource("key", key), ATTEMPT);
		return found.stream().findFirst();
	}

	Attempt start(NewAttempt request) {
		var keys = new GeneratedKeyHolder();

		jdbc.update("""
			INSERT INTO booking_attempt
			  (idempotency_key, provider_id, service_id, slot_start,
			   price_minor, commission_minor, currency,
			   customer_email, customer_name, state)
			VALUES
			  (:key, :providerId, :serviceId, :slotStart,
			   :priceMinor, :commissionMinor, :currency,
			   :email, :name, 'STARTED')
			""",
			new MapSqlParameterSource()
				.addValue("key", request.idempotencyKey())
				.addValue("providerId", request.providerId())
				.addValue("serviceId", request.serviceId())
				.addValue("slotStart", java.sql.Timestamp.from(request.slotStart()))
				.addValue("priceMinor", request.priceMinor())
				.addValue("commissionMinor", request.commissionMinor())
				.addValue("currency", request.currency())
				.addValue("email", request.customerEmail())
				.addValue("name", request.customerName()),
			keys, new String[] { "id" });

		long id = keys.getKey().longValue();

		step(id, "-", "STARTED", null, "ok", "quote frozen", false);

		return findById(id).orElseThrow();
	}

	Optional<Attempt> findById(long id) {
		return jdbc.query("SELECT * FROM booking_attempt WHERE id = :id",
			new MapSqlParameterSource("id", id), ATTEMPT).stream().findFirst();
	}

	/**
	 * Moves an attempt to a new state and records why, in one place.
	 *
	 * <p>The legality check lives in {@link AttemptState}; this refuses to
	 * persist a move it forbids. An illegal transition is a programming error,
	 * and writing it to the database would turn a loud bug into a quiet one.
	 */
	void transition(Attempt attempt, AttemptState to, String authority,
		String outcome, String detail, boolean compensating) {

		if (!attempt.state().canMoveTo(to)) {
			throw new IllegalStateException(
				"illegal transition " + attempt.state() + " -> " + to
					+ " for attempt " + attempt.id());
		}

		jdbc.update("""
			UPDATE booking_attempt
			   SET state = :state, updated_at = now(),
			       failure = COALESCE(:failure, failure)
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("state", to.name())
				.addValue("failure", "ok".equals(outcome) ? null : detail)
				.addValue("id", attempt.id()));

		step(attempt.id(), attempt.state().name(), to.name(), authority, outcome, detail, compensating);
	}

	/** Records a step that did not change state — a compensation, usually. */
	void note(long attemptId, AttemptState state, String authority,
		String outcome, String detail, boolean compensating) {
		step(attemptId, state.name(), state.name(), authority, outcome, detail, compensating);
	}

	private void step(long attemptId, String from, String to, String authority,
		String outcome, String detail, boolean compensating) {

		jdbc.update("""
			INSERT INTO booking_attempt_step
			  (attempt_id, from_state, to_state, authority, outcome, detail, compensating)
			VALUES (:id, :from, :to, :authority, :outcome, :detail, :compensating)
			""",
			new MapSqlParameterSource()
				.addValue("id", attemptId)
				.addValue("from", from)
				.addValue("to", to)
				.addValue("authority", authority)
				.addValue("outcome", outcome)
				.addValue("detail", detail == null ? null : truncate(detail))
				.addValue("compensating", compensating));
	}

	/** What Cal said it created, so a webhook-driven resume does not have to guess. */
	void recordReservation(long attemptId, String uid, Instant end, String status) {
		jdbc.update("""
			UPDATE booking_attempt
			   SET cal_booking_uid = :uid, reserved_end = :end, reserved_status = :status
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("uid", uid)
				.addValue("end", java.sql.Timestamp.from(end))
				.addValue("status", status)
				.addValue("id", attemptId));
	}

	void recordPaymentIntent(long attemptId, String intentId) {
		jdbc.update("UPDATE booking_attempt SET payment_intent_id = :pi WHERE id = :id",
			new MapSqlParameterSource().addValue("pi", intentId).addValue("id", attemptId));
	}

	Optional<Attempt> findByPaymentIntent(String intentId) {
		return jdbc.query("SELECT * FROM booking_attempt WHERE payment_intent_id = :pi",
			new MapSqlParameterSource("pi", intentId), ATTEMPT).stream().findFirst();
	}

	/**
	 * Attempts that have been waiting for the customer too long.
	 *
	 * <p>Every one of these is holding a real slot for someone who is not coming
	 * back. Abandoning a checkout is the common case, not an edge case, so this
	 * is a load-bearing query rather than a tidy-up.
	 */
	List<Attempt> findAbandoned(int olderThanSeconds, int limit) {
		return jdbc.query("""
			SELECT * FROM booking_attempt
			 WHERE state = 'AWAITING_PAYMENT'
			   AND updated_at < now() - make_interval(secs => :age)
			 ORDER BY updated_at
			 LIMIT :limit
			""",
			new MapSqlParameterSource()
				.addValue("age", olderThanSeconds)
				.addValue("limit", limit),
			ATTEMPT);
	}

	void recordPayment(long attemptId, String reference) {
		jdbc.update("UPDATE booking_attempt SET payment_ref = :ref WHERE id = :id",
			new MapSqlParameterSource().addValue("ref", reference).addValue("id", attemptId));
	}

	/**
	 * The commercial record, written only once the sale actually happened.
	 *
	 * <p>The Cal uid is passed in rather than read off {@code attempt}. It has to
	 * be: {@link #recordCalUid} writes it to the database, not to the caller's
	 * in-memory copy, so the attempt object still carries null at this point. A
	 * NOT NULL constraint caught it here, which is the good outcome — but the
	 * argument is what stops it recurring.
	 */
	long createBooking(Attempt attempt, String calBookingUid, Instant startsAt, Instant endsAt) {
		var keys = new GeneratedKeyHolder();

		jdbc.update("""
			INSERT INTO booking
			  (provider_id, service_id, cal_booking_uid, starts_at, ends_at,
			   customer_email, customer_name, price_minor, commission_minor, currency)
			VALUES
			  (:providerId, :serviceId, :uid, :startsAt, :endsAt,
			   :email, :name, :priceMinor, :commissionMinor, :currency)
			""",
			new MapSqlParameterSource()
				.addValue("providerId", attempt.providerId())
				.addValue("serviceId", attempt.serviceId())
				.addValue("uid", calBookingUid)
				.addValue("startsAt", java.sql.Timestamp.from(startsAt))
				.addValue("endsAt", java.sql.Timestamp.from(endsAt))
				.addValue("email", attempt.customerEmail())
				.addValue("name", attempt.customerName())
				.addValue("priceMinor", attempt.priceMinor())
				.addValue("commissionMinor", attempt.commissionMinor())
				.addValue("currency", attempt.currency()),
			keys, new String[] { "id" });

		long bookingId = keys.getKey().longValue();

		jdbc.update("UPDATE booking_attempt SET booking_id = :bid WHERE id = :id",
			new MapSqlParameterSource().addValue("bid", bookingId).addValue("id", attempt.id()));

		return bookingId;
	}

	/**
	 * Records that search offered a slot Cal would not honour.
	 *
	 * <p>The reason {@code availability_miss} exists. It is written here because
	 * this is the only moment both answers are in hand.
	 */
	void recordMiss(long providerId, long serviceId, Instant requestedAt, Integer indexAgeSeconds) {
		jdbc.update("""
			INSERT INTO availability_miss
			  (provider_id, service_id, requested_at, index_said, cal_said, index_age_s)
			VALUES (:providerId, :serviceId, :requestedAt, true, false, :age)
			""",
			new MapSqlParameterSource()
				.addValue("providerId", providerId)
				.addValue("serviceId", serviceId)
				.addValue("requestedAt", java.sql.Timestamp.from(requestedAt))
				.addValue("age", indexAgeSeconds));
	}

	/** The quote inputs, read once at stage 5 and then frozen onto the attempt. */
	Optional<ServiceForSale> findServiceForSale(long serviceId) {
		return jdbc.query("""
			SELECT s.id, s.provider_id, s.cal_event_type_id, s.price_minor,
			       s.currency, s.duration_minutes, s.active,
			       p.status AS provider_status,
			       p.stripe_account_id, p.payouts_enabled
			  FROM service s JOIN provider p ON p.id = s.provider_id
			 WHERE s.id = :id
			""",
			new MapSqlParameterSource("id", serviceId),
			(ResultSet rs, int n) -> new ServiceForSale(
				rs.getLong("id"),
				rs.getLong("provider_id"),
				rs.getLong("cal_event_type_id"),
				rs.getInt("price_minor"),
				rs.getString("currency"),
				rs.getInt("duration_minutes"),
				rs.getBoolean("active"),
				"active".equals(rs.getString("provider_status")),
				rs.getString("stripe_account_id"),
				rs.getBoolean("payouts_enabled"))).stream().findFirst();
	}

	/**
	 * How stale the index row was that led to this attempt.
	 *
	 * <p>Recorded alongside a miss so the question "was the index merely old, or
	 * actually wrong?" has an answer. A miss against a fresh row means something
	 * more interesting than lag.
	 */
	Integer indexAgeSeconds(long serviceId, Instant slotStart) {
		// The CASE is required, not defensive. markStale writes
		// computed_at = '-infinity' as its sentinel, and subtracting an infinite
		// timestamp is a hard error in Postgres — so recording a miss against a
		// service that had just been marked stale failed the whole request. NULL
		// is also the honest answer: the row was explicitly invalidated, so it
		// has no meaningful age.
		List<Integer> ages = jdbc.query("""
			SELECT CASE WHEN computed_at = TIMESTAMPTZ '-infinity' THEN NULL
			            ELSE EXTRACT(EPOCH FROM (now() - computed_at))::int
			       END AS age
			  FROM availability_day
			 WHERE service_id = :id AND day = (:slot AT TIME ZONE 'Europe/Stockholm')::date
			""",
			new MapSqlParameterSource()
				.addValue("id", serviceId)
				.addValue("slot", java.sql.Timestamp.from(slotStart)),
			(ResultSet rs, int n) -> {
				int age = rs.getInt("age");
				return rs.wasNull() ? null : age;
			});
		return ages.isEmpty() ? null : ages.get(0);
	}

	record ServiceForSale(
		long serviceId,
		long providerId,
		long calEventTypeId,
		int priceMinor,
		String currency,
		int durationMinutes,
		boolean active,
		boolean providerActive,
		String stripeAccountId,
		boolean payoutsEnabled
	) {}

	private static String truncate(String s) {
		return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
	}

	private static final RowMapper<Attempt> ATTEMPT = (ResultSet rs, int n) -> new Attempt(
		rs.getLong("id"),
		rs.getString("idempotency_key"),
		rs.getLong("provider_id"),
		rs.getLong("service_id"),
		rs.getTimestamp("slot_start").toInstant(),
		rs.getInt("price_minor"),
		rs.getInt("commission_minor"),
		rs.getString("currency"),
		rs.getString("customer_email"),
		rs.getString("customer_name"),
		AttemptState.valueOf(rs.getString("state")),
		rs.getString("cal_booking_uid"),
		rs.getString("payment_ref"),
		(Long) rs.getObject("booking_id"),
		rs.getString("failure"),
		rs.getTimestamp("reserved_end") == null ? null : rs.getTimestamp("reserved_end").toInstant(),
		rs.getString("reserved_status"));

	record Attempt(
		long id,
		String idempotencyKey,
		long providerId,
		long serviceId,
		Instant slotStart,
		int priceMinor,
		int commissionMinor,
		String currency,
		String customerEmail,
		String customerName,
		AttemptState state,
		String calBookingUid,
		String paymentRef,
		Long bookingId,
		String failure,
		Instant reservedEnd,
		String reservedStatus
	) {
		Attempt withState(AttemptState next) {
			return new Attempt(id, idempotencyKey, providerId, serviceId, slotStart,
				priceMinor, commissionMinor, currency, customerEmail, customerName,
				next, calBookingUid, paymentRef, bookingId, failure,
				reservedEnd, reservedStatus);
		}

		/** Mirrors {@code CalBookingPort.Reservation.awaitingConfirmation()}. */
		boolean awaitingConfirmation() {
			return "pending".equalsIgnoreCase(reservedStatus);
		}
	}

	record NewAttempt(
		String idempotencyKey,
		long providerId,
		long serviceId,
		Instant slotStart,
		int priceMinor,
		int commissionMinor,
		String currency,
		String customerEmail,
		String customerName
	) {}

}
