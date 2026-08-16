/**
 * The business console, and the identities behind it.
 *
 * <p>The first surface in the system with a person on the other side.
 * Everything before it was either public — a search, a consumer booking — or
 * verified by a shared secret, and a webhook is not a principal.
 *
 * <p>Credentials live here rather than being borrowed from Cal. Authenticating
 * a salon against its Cal account would be convenient and wrong: our session
 * would depend on auth we do not control, and a salon that left Cal would lose
 * access to its own money.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Console")
package se.marketplace.console;
