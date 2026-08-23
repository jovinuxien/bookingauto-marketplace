package se.marketplace.landing;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import se.marketplace.categories.Categories;
import se.marketplace.categories.Category;

/**
 * Checks that every category has a page and every page has a category.
 *
 * <p>The list of categories is a table and the list of URLs is a literal in a
 * mapping annotation, and ADR 0013 explains why that split is deliberate rather
 * than an oversight: a dynamic {@code /{a}/{b}} mapping would sit in front of
 * every other two-segment path in the application. The cost of the split is that
 * the two can disagree, and both directions fail quietly.
 *
 * <p>A category with no route is a filter customers can be given and a page they
 * cannot reach. A route with no category is a URL that 404s for everyone
 * forever, listed in nothing, tested by nothing — which is precisely the state
 * {@code /massage/{city}} and {@code /hudvard/{city}} were in before this
 * migration, and nobody noticed for exactly that reason.
 *
 * <p><strong>Warns rather than fails.</strong> A row in a table must not be able
 * to stop the application starting — the same rule that keeps an absent API key
 * from doing it (ADR 0012). The mismatch is loud, at boot, naming both sides.
 */
@Component
class CategoryRoutes {

	private static final Logger log = LoggerFactory.getLogger(CategoryRoutes.class);

	private final Categories categories;

	CategoryRoutes(Categories categories) {
		this.categories = categories;
	}

	/**
	 * On ready rather than on construction: this reads the database, and a bean
	 * that queries during context startup fails for reasons that have nothing to
	 * do with what it is checking.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void check() {
		Set<String> routed = new LinkedHashSet<>(
			Arrays.asList(LandingController.CATEGORY_PATHS.split("\\|")));

		Set<String> known = categories.all().stream()
			.map(Category::path)
			.collect(Collectors.toCollection(LinkedHashSet::new));

		Set<String> unreachable = new LinkedHashSet<>(known);
		unreachable.removeAll(routed);

		Set<String> orphaned = new LinkedHashSet<>(routed);
		orphaned.removeAll(known);

		if (!unreachable.isEmpty()) {
			log.warn("category has no landing route and cannot be browsed: {} — "
				+ "add it to LandingController.CATEGORY_PATHS", unreachable);
		}

		if (!orphaned.isEmpty()) {
			log.warn("landing route has no active category and will always 404: {} — "
				+ "seed it in service_category or remove it from CATEGORY_PATHS", orphaned);
		}

		if (unreachable.isEmpty() && orphaned.isEmpty()) {
			log.info("{} categor{} routed: {}",
				known.size(), known.size() == 1 ? "y" : "ies", known);
		}
	}

}
