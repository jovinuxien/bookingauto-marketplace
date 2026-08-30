/**
 * Getting a salon to the point where it can be sold.
 *
 * <p>Assembles the two halves of a provider — something to sell (Cal) and
 * somewhere to be paid (Stripe) — neither of which is ours and neither of which
 * is instant. That makes onboarding a state machine rather than a form
 * submission, and the state worth watching is the one where a salon got stuck
 * halfway.
 *
 * <p>The single invariant: a provider is not sellable until it can be paid.
 * "Active but unpayable" takes a customer's money with nowhere to send it, and
 * is enforced by a database constraint as well as by this module.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Onboarding",
	allowedDependencies = { "sync", "payments", "categories", "pricing" })
package se.marketplace.onboarding;
