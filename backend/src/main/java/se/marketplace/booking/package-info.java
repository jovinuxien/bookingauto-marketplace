/**
 * The booking funnel.
 *
 * <p>Where the marketplace stops being allowed to be wrong. Everything upstream
 * is approximate on purpose — search reads a stale-tolerant index, a slot hold
 * is a courtesy — and this is the one place three independent authorities have
 * to end up agreeing: Cal owns time, Stripe owns money, this owns the
 * commercial record.
 *
 * <p>It is the only module that knows all three exist, and the only one
 * positioned to notice when the index was wrong. See
 * {@code docs/design/booking-funnel.md}.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Booking",
	allowedDependencies = { "sync", "payments", "notifications" })
package se.marketplace.booking;
