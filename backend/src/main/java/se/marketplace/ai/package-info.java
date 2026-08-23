/**
 * Whether a model may be called, and which one.
 *
 * <p>Deliberately almost empty, and shared rather than depended upon. There are
 * no agents here — an agent belongs to the module that owns its domain, because
 * {@code @Agent} is a Spring stereotype and an agent is therefore an ordinary
 * component-scanned bean that the boundary test should treat like any other. A
 * module collecting every agent in the system would become the second one after
 * {@code booking} that knows everything exists, and a query parser has not
 * earned that.
 *
 * <p>What is left is the one decision that is genuinely system-wide: this is the
 * first dependency that costs money per request rather than per month, on the
 * busiest endpoint in the product. So it is off unless a deployment turns it on,
 * for the same reason {@code marketplace.geocoding.provider} defaults to
 * {@code none} — enabling it by default points every developer machine and every
 * CI run at a metered third party, and an invoice is a poor place to discover
 * that.
 *
 * <p>See ADR 0012, which also lists what an agent may never do.
 */
@org.springframework.modulith.ApplicationModule(displayName = "AI")
package se.marketplace.ai;
