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
			   customer_email, customer_name, registration_number, state)
			VALUES
			  (:key, :providerId, :serviceId, :slotStart,
			   :priceMinor, :commissionMinor, :currency,
			   :email, :name, :plate, 'STARTED')
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
				.addValue("name", request.customerName())
				.addValue("plate", request.registrationNumber()),
			keys, new String[] { "id" });

		long id = keys.getKey().longValue();

		// The extras, by name and price, frozen with the quote (ADR 0017).
		for (var addon : request.addons()) {
			jdbc.update("""
				INSERT INTO booking_attempt_addon (attempt_id, addon_id, name, price_minor)
				VALUES (:aid, :addon, :name, :price)
				""",
				new MapSqlParameterSource().addValue("aid", id).addValue("addon", addon.id())
					.addValue("name", addon.name()).addValue("price", addon.priceMinor()));
		}

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
	 * <p>The cancellation cutoff is written here rather than left to the column
	 * default, and copied rather than referenced, for the same reason
	 * {@code price_minor} is: it is a term of this sale. Changing the configured
	 * default must change what is sold next, never what somebody already agreed
	 * to. See ADR 0014.
	 *
	 * <p>The Cal uid is passed in rather than read off {@code attempt}. It has to
	 * be: {@link #recordCalUid} writes it to the database, not to the caller's
	 * in-memory copy, so the attempt object still carries null at this point. A
	 * NOT NULL constraint caught it here, which is the good outcome — but the
	 * argument is what stops it recurring.
	 */
	long createBooking(Attempt attempt, String calBookingUid, Instant startsAt, Instant endsAt,
		int cancellationCutoffHours) {

		var keys = new GeneratedKeyHolder();

		jdbc.update("""
			INSERT INTO booking
			  (provider_id, service_id, cal_booking_uid, starts_at, ends_at,
			   customer_email, customer_name, price_minor, commission_minor, currency,
			   cancellation_cutoff_hours, registration_number)
			VALUES
			  (:providerId, :serviceId, :uid, :startsAt, :endsAt,
			   :email, :name, :priceMinor, :commissionMinor, :currency,
			   :cutoffHours, :plate)
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
				.addValue("currency", attempt.currency())
				.addValue("cutoffHours", cancellationCutoffHours)
				.addValue("plate", attempt.registrationNumber()),
			keys, new String[] { "id" });

		long bookingId = keys.getKey().longValue();

		jdbc.update("""
			INSERT INTO booking_addon (booking_id, addon_id, name, price_minor)
			SELECT :bid, addon_id, name, price_minor FROM booking_attempt_addon WHERE attempt_id = :id
			""", new MapSqlParameterSource().addValue("bid", bookingId).addValue("id", attempt.id()));

		jdbc.update("UPDATE booking_attempt SET booking_id = :bid WHERE id = :id",
			new MapSqlParameterSource().addValue("bid", bookingId).addValue("id", attempt.id()));

		return bookingId;
	}

	// ------------------------------------------------- after the sale --

	/**
	 * A confirmed booking, as the customer who made it needs to see it.
	 *
	 * <p>Joined rather than assembled from three calls because every field here
	 * is shown on one page, and because the payment reference has to come with
	 * it: refunding needs the charge, and the charge is on the attempt rather
	 * than on the commercial record. Kept there deliberately — {@code booking}
	 * is what was sold, and Stripe's identifier for how it was paid for belongs
	 * with the attempt that did the paying.
	 */
	Optional<ConsumerBooking> findBookingForCustomer(long id) {
		return jdbc.query("""
			SELECT b.id, b.provider_id, b.service_id, b.cal_booking_uid,
			       b.starts_at, b.ends_at, b.customer_email, b.customer_name,
			       b.price_minor, b.currency, b.status,
			       b.cancellation_cutoff_hours, b.cancelled_at, b.needs_attention,
			       b.registration_number,
			       -- contact_email, not email. db/001's provider.email is a
			       -- marketing field and is null on every row in this database;
			       -- what onboarding and signup actually write is contact_email,
			       -- added by db/004. Reading the wrong one compiles, runs, and
			       -- silently never notifies a salon that a slot came free.
			       p.name AS provider_name, p.city,
			       COALESCE(p.contact_email, p.email) AS provider_email,
			       s.name AS service_name,
			       (SELECT a.payment_ref FROM booking_attempt a
			         WHERE a.booking_id = b.id AND a.payment_ref IS NOT NULL
			         LIMIT 1) AS payment_ref
			  FROM booking b
			  JOIN provider p ON p.id = b.provider_id
			  JOIN service  s ON s.id = b.service_id
			 WHERE b.id = :id
			""",
			new MapSqlParameterSource("id", id),
			(ResultSet rs, int n) -> new ConsumerBooking(
				rs.getLong("id"),
				rs.getLong("provider_id"),
				rs.getLong("service_id"),
				rs.getString("cal_booking_uid"),
				rs.getTimestamp("starts_at").toInstant(),
				rs.getTimestamp("ends_at").toInstant(),
				rs.getString("customer_email"),
				rs.getString("customer_name"),
				rs.getInt("price_minor"),
				rs.getString("currency"),
				rs.getString("status"),
				rs.getInt("cancellation_cutoff_hours"),
				rs.getTimestamp("cancelled_at") == null
					? null : rs.getTimestamp("cancelled_at").toInstant(),
				rs.getBoolean("needs_attention"),
				rs.getString("provider_name"),
				rs.getString("provider_email"),
				rs.getString("city"),
				rs.getString("service_name"),
				rs.getString("payment_ref"),
				rs.getString("registration_number"))).stream().findFirst();
	}

	/**
	 * Takes ownership of a cancellation, and refuses to hand it out twice.
	 *
	 * <p>The {@code status = 'confirmed'} predicate is the whole point and is
	 * not a convenience. A customer who double-clicks, or opens the link on a
	 * phone and a laptop, produces two requests in flight at once — and both
	 * would read a confirmed booking, both would ask Cal to release the slot,
	 * and both would refund. Checking the status in Java loses that race by
	 * construction; the database is the only place it can be settled.
	 *
	 * <p><strong>It claims before the work, not after.</strong> Which means the
	 * row says cancelled while Cal and Stripe are still being asked, and that is
	 * why it also sets {@code needs_attention}: between here and
	 * {@link #settleCancellation} the booking is one whose outcome nobody knows,
	 * and a process that dies in that window has to leave something behind that
	 * says so. The flag is cleared by the settle when everything worked.
	 *
	 * <p>The alternative — do the work first, write afterwards — makes a crash
	 * lose nothing but makes a double click refund twice. A visible
	 * inconsistency a person can reconcile is worth more than money out of the
	 * door twice, so this claims first.
	 *
	 * @return whether this call is the one that owns the cancellation
	 */
	boolean claimForCancellation(long id) {
		return jdbc.update("""
			UPDATE booking
			   SET status = 'cancelled',
			       cancelled_at = now(),
			       needs_attention = true,
			       updated_at = now()
			 WHERE id = :id AND status = 'confirmed'
			""",
			new MapSqlParameterSource("id", id)) == 1;
	}

	/**
	 * Records how the claimed cancellation actually went.
	 *
	 * <p>Unguarded on purpose. The claim already established that this caller
	 * owns the row, and a guard here would mean a settle that silently did
	 * nothing — which is the one thing that must not happen after money has
	 * moved.
	 */
	int rescheduleCount(long id) {
		Integer n = jdbc.queryForObject("SELECT rescheduled_count FROM booking WHERE id = :id",
			new MapSqlParameterSource("id", id), Integer.class);
		return n == null ? 0 : n;
	}

	/** Guarded on status and the old uid, so a concurrent cancel or move wins and this returns 0. */
	int reschedule(long id, String oldUid, String newUid, java.time.Instant startsAt, java.time.Instant endsAt) {
		return jdbc.update("""
			UPDATE booking
			   SET cal_booking_uid = :newUid,
			       rescheduled_from = starts_at,
			       starts_at = :startsAt,
			       ends_at = :endsAt,
			       rescheduled_count = rescheduled_count + 1,
			       rescheduled_at = now(),
			       updated_at = now()
			 WHERE id = :id AND status = 'confirmed' AND cal_booking_uid = :oldUid
			""",
			new MapSqlParameterSource()
				.addValue("id", id).addValue("oldUid", oldUid).addValue("newUid", newUid)
				.addValue("startsAt", java.sql.Timestamp.from(startsAt))
				.addValue("endsAt", java.sql.Timestamp.from(endsAt)));
	}

	void markCancelledBy(long id, String who) {
		jdbc.update("UPDATE booking SET cancelled_by = :who WHERE id = :id",
			new MapSqlParameterSource().addValue("id", id).addValue("who", who));
	}

	void settleCancellation(long id, String status, String refundRef, boolean needsAttention) {
		jdbc.update("""
			UPDATE booking
			   SET status = :status,
			       refund_ref = :refundRef,
			       needs_attention = :attention,
			       updated_at = now()
			 WHERE id = :id
			""",
			new MapSqlParameterSource()
				.addValue("status", status)
				.addValue("refundRef", refundRef)
				.addValue("attention", needsAttention)
				.addValue("id", id));
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

	/** Names for the customer's confirmation. Cheap, and only read when one is owed. */
	String providerName(long providerId) {
		return jdbc.queryForObject("SELECT name FROM provider WHERE id = :id",
			new MapSqlParameterSource("id", providerId), String.class);
	}

	List<BookingCancellation.Extra> addonsOf(long bookingId) {
		return jdbc.query("SELECT name, price_minor FROM booking_addon WHERE booking_id = :id ORDER BY name",
			new MapSqlParameterSource("id", bookingId),
			(rs, n) -> new BookingCancellation.Extra(rs.getString("name"), rs.getInt("price_minor")));
	}

	/** "Spolarvätska, Däckhotell" for the mails, or null. */
	String attemptExtras(long attemptId) {
		List<String> names = jdbc.query(
			"SELECT name FROM booking_attempt_addon WHERE attempt_id = :id ORDER BY name",
			new MapSqlParameterSource("id", attemptId), (rs, n) -> rs.getString("name"));
		return names.isEmpty() ? null : String.join(", ", names);
	}

	/**
	 * Appointments that happened and have not been asked about: confirmed,
	 * ended between {@code afterHours} ago and {@code withinDays} ago.
	 */
	List<ConsumerBooking> needingReviewRequest(int afterHours, int withinDays, int limit) {
		List<Long> ids = jdbc.query("""
			SELECT id FROM booking
			 WHERE status = 'confirmed'
			   AND review_requested_at IS NULL
			   AND ends_at < now() - make_interval(hours => :hours)
			   AND ends_at > now() - make_interval(days => :days)
			 ORDER BY ends_at
			 LIMIT :limit
			""",
			new MapSqlParameterSource().addValue("hours", afterHours)
				.addValue("days", withinDays).addValue("limit", limit),
			(rs, n) -> rs.getLong("id"));
		return ids.stream().flatMap(id -> findBookingForCustomer(id).stream()).toList();
	}

	int markReviewRequested(long bookingId) {
		return jdbc.update(
			"UPDATE booking SET review_requested_at = now() WHERE id = :id AND review_requested_at IS NULL",
			new MapSqlParameterSource("id", bookingId));
	}

	/** contact_email, not email -- see findBookingForCustomer for why. */
	String providerEmail(long providerId) {
		return jdbc.queryForObject(
			"SELECT COALESCE(contact_email, email) FROM provider WHERE id = :id",
			new MapSqlParameterSource("id", providerId), String.class);
	}

	String serviceName(long serviceId) {
		return jdbc.queryForObject("SELECT name FROM service WHERE id = :id",
			new MapSqlParameterSource("id", serviceId), String.class);
	}

	/** The quote inputs, read once at stage 5 and then frozen onto the attempt. */
	Optional<ServiceForSale> findServiceForSale(long serviceId) {
		return jdbc.query("""
			SELECT s.id, s.provider_id, s.cal_event_type_id, s.price_minor,
			       s.currency, s.duration_minutes, s.active,
			       p.status AS provider_status,
			       p.stripe_account_id, p.payouts_enabled,
			       COALESCE(c.asks_vehicle, false) AS asks_vehicle
			  FROM service s
			  JOIN provider p ON p.id = s.provider_id
			  LEFT JOIN service_category c ON c.slug = s.category_slug
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
				rs.getBoolean("payouts_enabled"),
				rs.getBoolean("asks_vehicle"))).stream().findFirst();
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

	/**
	 * @param paymentRef        the settled charge, or null for a booking made
	 *                          before payments were wired to anything real
	 * @param needsAttention    the slot was released and the refund was not
	 */
	record ConsumerBooking(
		long id,
		long providerId,
		long serviceId,
		String calBookingUid,
		Instant startsAt,
		Instant endsAt,
		String customerEmail,
		String customerName,
		int priceMinor,
		String currency,
		String status,
		int cancellationCutoffHours,
		Instant cancelledAt,
		boolean needsAttention,
		String providerName,
		String providerEmail,
		String city,
		String serviceName,
		String paymentRef,
		String registrationNumber
	) {

		boolean confirmed() {
			return "confirmed".equals(status);
		}
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
		boolean payoutsEnabled,
		/** The category wants a registration number with the booking. */
		boolean asksVehicle
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
		rs.getString("reserved_status"),
		rs.getString("registration_number"));

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
		String reservedStatus,
		String registrationNumber
	) {
		Attempt withState(AttemptState next) {
			return new Attempt(id, idempotencyKey, providerId, serviceId, slotStart,
				priceMinor, commissionMinor, currency, customerEmail, customerName,
				next, calBookingUid, paymentRef, bookingId, failure,
				reservedEnd, reservedStatus, registrationNumber);
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
		String customerName,
		/** Normalised, or null when the category did not ask. */
		String registrationNumber,
		/** Chosen at checkout, already validated and priced. */
		List<se.marketplace.pricing.Addon> addons
	) {}

}
