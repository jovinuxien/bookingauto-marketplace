/**
 * A salon registering itself.
 *
 * <p>The first endpoint in this system that both faces the open internet and
 * causes something expensive to happen elsewhere. Search is public but only
 * reads; a consumer booking is public and takes money, which bounds it. This
 * one makes us create a Cal account and a Stripe connected account for an
 * address nobody has proved they own.
 *
 * <p>So the whole module is arranged around one ordering: <strong>nothing
 * outside this database exists until the address is verified.</strong> A
 * registration writes a row and sends a link. Cal and Stripe are not touched
 * until that link is clicked. Verification is the gate in front of provisioning,
 * not a formality after it.
 *
 * <p>The second concern is volume. An endpoint that provisions on demand is
 * worth attacking even when each attempt is cheap, so every entry point is
 * counted and capped before it does any work.
 *
 * <p>The third is silence. Registration answers identically for an address that
 * already has an account and one that does not — otherwise the form becomes a
 * way to ask which salons are on the platform, which is exactly what the login
 * endpoint already refuses to answer.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Signup",
	allowedDependencies = { "onboarding", "console", "notifications", "ratelimit", "categories" })
package se.marketplace.signup;
