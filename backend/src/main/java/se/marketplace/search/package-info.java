/**
 * Discovery: geo, availability and category.
 *
 * <p>Answers the filter problem only — "near me, free Saturday afternoon".
 * The relevance problem ("balayage", misspellings, two languages) is a different
 * shape with opposite churn characteristics and is deferred to OCSS behind
 * {@link se.marketplace.search.SearchPort}. See ADR 0006.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Search")
package se.marketplace.search;
