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
 * <p><strong>Deployment note.</strong> The implementation targets Cal's v2 API,
 * which is <em>not</em> part of the {@code calcom/cal.com} Docker image — that
 * image ships {@code apps/web} only, and {@code /api/v2/*} returns 500 against
 * it. The v2 API is a separate NestJS application ({@code @calcom/api-v2}) with
 * its own Dockerfile in the repository and has to be built and run alongside.
 * Worth knowing before an architecture is drawn on top of {@code /v2/*}.
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

	record Slot(Instant start, Instant end) {}

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
