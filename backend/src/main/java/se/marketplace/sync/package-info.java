/**
 * The only module that knows Cal's shape.
 *
 * <p>Everything else here talks about providers, services and bookings. This
 * module talks about Cal Teams, EventTypes and Bookings, and translates. If Cal
 * is ever replaced or moves its schema, this is what changes.
 *
 * <p>It owns two jobs: receiving webhooks, and reconciling the availability
 * index on a timer. The second is the mechanism; the first is a latency
 * optimisation over it. See ADR 0002.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sync")
package se.marketplace.sync;
