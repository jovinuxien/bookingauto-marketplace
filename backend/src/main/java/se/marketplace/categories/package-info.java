/**
 * What a salon sells, as one list.
 *
 * <p>Small, and the reason it is a module rather than a helper is that three
 * other modules need the same answer and none of them may reach into another to
 * get it. {@code landing} turns a category into a page, {@code search} filters
 * on it and hands it to the agent, {@code onboarding} assigns it during import.
 * Before this existed the list was an enum in {@code landing}, an alternation in
 * a route pattern, and an unconstrained text column — three copies, and the
 * column tied to none of them.
 *
 * <p>Not in {@code sharedModules}. A category is a product decision with a URL
 * attached, so a module that filters on one should have to say it does; the
 * declaration is the documentation. See ADR 0013, which also answers the
 * argument for keeping the enum.
 *
 * <p><strong>Read-only from the application's side.</strong> Categories are
 * seeded by migration and there is nothing here that inserts one. Adding a
 * category is a new indexable page and a new filter, and it stays what it always
 * was — someone writing it down on purpose, in a commit.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Categories")
package se.marketplace.categories;
