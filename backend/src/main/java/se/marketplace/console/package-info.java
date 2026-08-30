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
 *
 * <p>Which is also why the login endpoint is counted. Declared now that it has
 * a limit: an empty dependency list and an unstated one look identical from
 * here, and an endpoint that stands in front of a salon's earnings is one whose
 * exposure should be written down rather than inferred.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Console",
	allowedDependencies = { "ratelimit", "categories", "vehicles", "pricing" })
package se.marketplace.console;
