/**
 * Turning an address into a point.
 *
 * <p>Exists because of one asymmetry: a salon knows its street address and the
 * product's primary filter is a radius. Everything else about a self-serve
 * salon works without coordinates — it has a city page, it can be booked, it
 * gets paid — but it cannot be <em>found</em>, which is the one thing a
 * marketplace is for.
 *
 * <p><strong>This module never blocks provisioning.</strong> Nothing calls it
 * during signup. A salon is created, given a Cal account and a Stripe account,
 * and only later swept up and placed. That ordering is deliberate: geocoding is
 * a call to a third party that can be slow, rate limited or down, and the
 * alternative design — geocode inline, fail the signup — turns someone else's
 * outage into a salon that could not register. The sweep is the mechanism, in
 * the same way the availability reconciler is (ADR 0002), and for the same
 * reason.
 *
 * <p><strong>What it will not do is guess.</strong> A geocoder asked for an
 * address it cannot find will happily answer with the city, and a city centroid
 * is a point that looks correct on a map and is wrong by kilometres — worse
 * than no point at all, because a null is visibly missing and a wrong
 * coordinate is not. Results coarser than a street are refused, and the salon
 * stays unplaced until a person places it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Geo")
package se.marketplace.geo;
