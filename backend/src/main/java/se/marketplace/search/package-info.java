/**
 * Discovery: geo, availability and category.
 *
 * <p>Answers the filter problem only — "near me, free Saturday afternoon".
 * The relevance problem ("balayage", misspellings, two languages) is a different
 * shape with opposite churn characteristics and is deferred to OCSS behind
 * {@link se.marketplace.search.SearchPort}. See ADR 0006.
 */
@org.springframework.modulith.ApplicationModule(
	displayName = "Search",
	// Declared now that /api/search/ask calls a metered model. Search had no
	// list before because it needed nothing; an empty list and an unstated one
	// look identical from here, and this endpoint is one whose exposure should
	// be written down rather than inferred.
	allowedDependencies = { "ratelimit", "categories" })
package se.marketplace.search;
