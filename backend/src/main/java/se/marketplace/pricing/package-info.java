/**
 * What a service costs for a particular car.
 *
 * <p>The list price on {@code service} is what a salon charges everyone and
 * what a workshop charges a car nobody knows. This module holds the rules
 * that say otherwise — "Volvo 2015–2019: 2 490 kr", "18-tums fälg: 699 kr"
 * — and the one function that turns a service, a list price and a vehicle
 * into a quote. Deterministic and modelless, like category classification,
 * for the same reason: it runs on a page a customer is looking at and on
 * the checkout path, and neither may wait on a third party.
 *
 * <p>Read by {@code search} (the provider page), {@code booking} (the
 * funnel re-quotes at attempt time) and {@code console} (the provider edits
 * its rules). Money is written only here (ADR 0003): the import from Cal
 * never touches a rule. See ADR 0016.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Pricing",
	allowedDependencies = { "vehicles" })
package se.marketplace.pricing;
