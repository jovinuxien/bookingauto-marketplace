/**
 * Which car is coming.
 *
 * <p>Exists for one asymmetry the bil &amp; däck vertical (ADR 0015) brought
 * with it: a salon needs a name, a workshop needs a registration number. The
 * number is typed by the customer and stored by {@code booking}; what this
 * module adds is what a registry says about it — make, model, year — so that
 * the workshop knows what is rolling in before it rolls in.
 *
 * <p><strong>Nothing here is on the booking path.</strong> The saga charges
 * and confirms with the plate as typed, and this module sweeps confirmed
 * bookings afterwards, the way {@code geo} places salons after signup rather
 * than during it, and for the same reason: a registry is a third party that
 * can be slow, rate limited or down, and a workshop's booking must not fail
 * because a lookup did.
 *
 * <p><strong>The registry is behind a port</strong> — {@link
 * se.marketplace.vehicles.VehicleRegistryPort} — with no vendor chosen. That
 * is the hexagonal rule every outside system in this codebase follows, and it
 * is what lets the module ship before the vendor decision is made: the
 * disabled adapter answers nothing, bookings carry the plate the customer
 * typed, and the day a vendor is picked it is one adapter and one property.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Vehicles")
package se.marketplace.vehicles;
