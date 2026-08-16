package se.marketplace.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What we need from Cal, expressed in our terms.
 *
 * <p>Deliberately small. The more of Cal's surface this exposes, the more of Cal
 * leaks into the rest of the system, and the less true it becomes that replacing
 * it is a bounded change.
 *
 * <p><strong>Deployment note.</strong> Cal's v2 REST API is <em>not</em> part of
 * the {@code calcom/cal.com} Docker image — that image ships {@code apps/web}
 * only, and {@code /api/v2/*} returns 500 against it. Worth knowing before an
 * architecture is drawn on top of {@code /v2/*}. Reads therefore go through an
 * endpoint the web image does serve; see {@link CalSlotsClient} for what that
 * costs. Booking writes will have to face the same question, and their answer
 * need not be the same one.
 */
public interface CalPort {

	/**
	 * Free slots for one service between two instants.
	 *
	 * <p>This is the authoritative answer and the expensive one. It is asked for
	 * a shortlist at booking time, and by the reconciler for one service at a
	 * time — never across the whole catalogue to serve a search.
	 */
	List<Slot> slots(long calEventTypeId, Instant from, Instant to);

	/**
	 * A start time, and only a start time.
	 *
	 * <p>Cal's slot payload carries no end: duration is a property of the event
	 * type, not of the slot. An end here would therefore be derived rather than
	 * observed, and nothing in this module reads one — so it is left out instead
	 * of computed. Anything that needs the finish time should take the duration
	 * from the service and be explicit that it is doing arithmetic.
	 */
	record Slot(Instant start) {}

	/**
	 * What the reconciler writes back for one service and day.
	 */
	record DayAvailability(
		LocalDate day,
		boolean hasCapacity,
		Instant firstFreeAt,
		int freeSlots,
		boolean freeMorning,
		boolean freeAfternoon,
		boolean freeEvening
	) {}

}
