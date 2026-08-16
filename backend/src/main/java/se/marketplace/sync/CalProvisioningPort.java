package se.marketplace.sync;

import java.util.List;

/**
 * Setting a salon up in Cal.
 *
 * <p>Deliberately lopsided, and the shape is not ours to choose. Creating a Cal
 * user is public. Creating that user's schedule and event types is not:
 * {@code /v2/event-types} and {@code /v2/schedules} answer 401, and
 * authenticated api-v2 requires a paid Cal licence (ADR 0008).
 *
 * <p>So onboarding cannot be a single flow we own end to end. We create the
 * account and the salon builds its services in <strong>Cal's own UI</strong>,
 * which exists and is good at this, and then we {@link #eventTypesOf import}
 * what they built.
 *
 * <p>Import reads Cal's database directly. ADR 0001 sanctions exactly that —
 * "database-as-read-model" — and the direction matters: reading is a projection,
 * writing would be the fork-by-stealth that ADR rejects.
 */
public interface CalProvisioningPort {

	/**
	 * Creates the salon's Cal account.
	 *
	 * @throws CalUserExists if the username or email is taken. A normal outcome
	 *         worth its own type: the salon has probably onboarded before, and
	 *         the right response is to link the existing account rather than to
	 *         show an error.
	 */
	CalUser createUser(NewCalUser request);

	/**
	 * What the salon has actually set up, read from Cal.
	 *
	 * <p>Only event types that are bookable are returned. A hidden or
	 * scheduleless event type looks like a service in Cal's UI and cannot be
	 * sold, and importing one would create a listing that refuses every booking.
	 */
	List<CalEventType> eventTypesOf(long calUserId);

	record NewCalUser(String username, String email, String password) {}

	record CalUser(long id, String username) {}

	record CalEventType(
		long id,
		String title,
		String slug,
		int lengthMinutes,
		int priceMinor,
		String currency,
		boolean requiresConfirmation,
		boolean confirmationBlocksSlot
	) {

		/**
		 * Whether this event type is safe to sell reserve-first.
		 *
		 * <p>An event type with {@code requiresConfirmation} but not
		 * {@code confirmationBlocksSlot} creates a pending booking that holds
		 * nothing — so the reserve step reserves nothing and we would charge for
		 * a slot another customer can still take. Verified behaviour, and
		 * invisible from the response; see ADR 0008.
		 */
		public boolean safeToSell() {
			return !requiresConfirmation || confirmationBlocksSlot;
		}
	}

	class CalUserExists extends RuntimeException {
		public CalUserExists(String message) {
			super(message);
		}
	}

	class CalProvisioningFailed extends RuntimeException {
		public CalProvisioningFailed(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
