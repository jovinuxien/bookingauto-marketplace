package se.marketplace.sync;

/**
 * Telling the index it is wrong.
 *
 * <p>Exists because a completed booking makes the index certainly stale for that
 * service, and we know it at the moment it happens. Waiting for Cal's webhook
 * would leave the just-sold slot advertised for as long as delivery takes — and
 * webhooks are missed, which is why the reconciler exists at all.
 *
 * <p>A method rather than an event, and the direction is the reason.
 * {@code booking} already depends on {@code sync} for Cal writes, so a listener
 * here would close a cycle between the two modules. Spring Modulith rejects that
 * outright, and it is right to: the two would stop being separable.
 */
public interface AvailabilityRefreshPort {

	/**
	 * Marks a service's index rows stale so the reconciler recomputes them.
	 *
	 * <p>Marks rather than recomputes. A salon taking bookings in quick
	 * succession would otherwise mean one round trip to Cal per sale for an
	 * answer that is identical after the last one.
	 */
	void markStale(long serviceId);

}
