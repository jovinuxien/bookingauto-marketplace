package se.marketplace.sync;

import java.time.Instant;

/**
 * Writing bookings to Cal.
 *
 * <p>Separate from {@link CalPort} on purpose. Reads and writes turned out to
 * have different deployment requirements, and a single interface would hide
 * that: reading availability works against the web image we already run, while
 * two of the three writes here do not. Keeping them apart means the gap is
 * visible in the type system instead of surfacing as a runtime failure halfway
 * through a payment.
 *
 * <h2>What is reachable, measured against the running Cal</h2>
 *
 * <table>
 *   <caption>Probed endpoints</caption>
 *   <tr><td>{@code POST /api/book/event}</td><td>public</td><td><b>works</b></td></tr>
 *   <tr><td>{@code POST /api/trpc/bookings/confirm}</td><td>401</td><td>needs provider auth</td></tr>
 *   <tr><td>{@code POST /api/cancel}</td><td>403</td><td>needs a session CSRF token</td></tr>
 * </table>
 *
 * <p>Creating a booking is public, because that is what a customer does on the
 * booking page. Confirming and cancelling are privileged, because those are
 * things a salon does — and a bearer token does not get in either.
 *
 * <p>This is the answer to the question ADR 0007 left open, and it is not the
 * same answer as for reads: <strong>the funnel needs {@code api-v2}</strong>,
 * whose whole purpose is API-key-authenticated programmatic access. Not for
 * reserving — for being able to take it back.
 *
 * <p>The consequence is worth stating plainly, because it is the reason this
 * cannot ship half-built. A funnel that can reserve but not cancel is worse than
 * one that does nothing: every failed payment strands a pending booking that
 * blocks a real slot, forever, and no automated path exists to release it.
 */
public interface CalBookingPort {

	/**
	 * Hold the slot. Stage 6.
	 *
	 * <p>Depends on the event type having both {@code requiresConfirmation} and
	 * {@code requiresConfirmationWillBlockSlot} set — see ADR 0005. Without the
	 * second, this returns a pending booking that holds nothing, and the caller
	 * cannot tell the difference from the response.
	 *
	 * @throws CalRefused if Cal will not hold the slot. A normal, expected
	 *         outcome — the index was stale — and not an error.
	 * @throws CalUnavailable if Cal could not be asked. Genuinely exceptional:
	 *         we do not know whether the slot was taken, so nothing may be
	 *         charged.
	 */
	Reservation reserve(ReservationRequest request);

	/**
	 * Complete the sale. Stage 8.
	 *
	 * @throws CalUnavailable if the confirm surface is not deployed.
	 */
	void confirm(String calBookingUid);

	/**
	 * Give the slot back. The compensating action for stages 6 and 7.
	 *
	 * <p>The single most important method here. Everything the funnel is allowed
	 * to attempt rests on being able to undo it.
	 *
	 * @throws CalUnavailable if the cancel surface is not deployed, which leaves
	 *         the attempt needing a person.
	 */
	void cancel(String calBookingUid, String reason);

	record ReservationRequest(
		long calEventTypeId,
		Instant start,
		String customerName,
		String customerEmail,
		String timeZone
	) {}

	/**
	 * What Cal says it created.
	 *
	 * <p>{@code status} is carried verbatim rather than mapped to a boolean, so
	 * that the verify step can compare what we asked for against what we got
	 * instead of trusting that a 200 meant agreement.
	 */
	record Reservation(
		String uid,
		Instant start,
		Instant end,
		long calEventTypeId,
		String status
	) {

		/**
		 * Whether Cal is waiting for someone to confirm this booking.
		 *
		 * <p>Decides whether stage 8 has anything to do, and is read from what
		 * Cal returned rather than from configuration on our side. An event type
		 * with {@code requiresConfirmation} yields {@code PENDING} and needs an
		 * explicit confirm; one without yields {@code ACCEPTED}, already holds
		 * the slot, and there is nothing left to confirm.
		 *
		 * <p>That distinction is load-bearing for more than tidiness: confirming
		 * is the one operation here that requires an authenticated api-v2 call,
		 * and authenticated api-v2 requires a paid Cal licence. Reserving and
		 * cancelling do not. So an auto-accepting event type makes the whole
		 * funnel work without one.
		 */
		public boolean awaitingConfirmation() {
			return "pending".equalsIgnoreCase(status);
		}
	}

	/** Cal declined. The slot was not free. */
	class CalRefused extends RuntimeException {
		public CalRefused(String message) {
			super(message);
		}
	}

	/** Cal could not be asked, or the surface is not deployed. */
	class CalUnavailable extends RuntimeException {
		public CalUnavailable(String message) {
			super(message);
		}

		public CalUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
