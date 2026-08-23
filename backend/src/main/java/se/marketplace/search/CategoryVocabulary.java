package se.marketplace.search;

import java.util.List;
import java.util.stream.Collectors;

import se.marketplace.categories.Category;

/**
 * The categories that exist, and the words customers use for them.
 *
 * <p>It used to be {@code SELECT DISTINCT category_slug}, which was honest
 * about what existed and was the one-element set {@code {har}} — because every
 * import wrote the configured default. The agent could not have proposed
 * "massage" however plainly a customer asked for it. ADR 0013 gave the list a
 * table; this reads it.
 *
 * <p>Its job is unchanged: be the closed set the model chooses from and is
 * checked against afterwards. What the synonyms change is the hit rate, not the
 * trust — {@link UnderstoodQuestion#ground} still overrules anything outside
 * the slug list. Given the list, a model rarely invents; given the list plus
 * "balayage", it does not have to infer either.
 */
public record CategoryVocabulary(List<Category> categories) {

	static CategoryVocabulary of(List<Category> categories) {
		return new CategoryVocabulary(categories);
	}

	List<String> slugs() {
		return categories.stream().map(Category::slug).toList();
	}

	boolean has(String slug) {
		return slug != null && categories.stream().anyMatch(c -> c.slug().equalsIgnoreCase(slug));
	}

	/**
	 * The slug as it is spelled in the database.
	 *
	 * <p>A model handed {@code har} may hand back {@code Har}, and the SQL
	 * compares exactly. Matching loosely and then storing the canonical form is
	 * the difference between forgiving the model its capitalisation and
	 * forgiving it a category we do not have.
	 */
	String canonical(String slug) {
		return categories.stream()
			.filter(c -> c.slug().equalsIgnoreCase(slug))
			.map(Category::slug)
			.findFirst()
			.orElse(null);
	}

	/**
	 * The list as the model sees it: slug, Swedish name, and what people type.
	 *
	 * <p>The slug leads each line because the slug is what has to come back. A
	 * prompt that presented the label first would be asking for "Frisörer" and
	 * then refusing it, which is a rule the grounding step would enforce
	 * correctly and a customer would experience as us ignoring them.
	 */
	String forPrompt() {
		if (categories.isEmpty()) {
			return "(none yet)";
		}

		return categories.stream()
			.map(category -> category.synonyms().isEmpty()
				? category.slug() + " — " + category.label()
				: category.slug() + " — " + category.label()
					+ " (" + String.join(", ", category.synonyms()) + ")")
			.collect(Collectors.joining("\n"));
	}

}
