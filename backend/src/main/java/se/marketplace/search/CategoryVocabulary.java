package se.marketplace.search;

import java.util.List;

/**
 * The categories that exist, read from the services that exist.
 *
 * <p>Not a constant and not a reference table, because neither would be true.
 * {@code service.category_slug} is free text filled by whatever salons imported
 * from Cal, so the only honest source for "what may be asked for" is the column
 * itself.
 *
 * <p>Its job is to be the closed set the model chooses from and is checked
 * against afterwards. Both halves matter: given the list, a model rarely
 * invents; not given it, a model asked to categorise "balayage" will confidently
 * answer {@code harfargning}, which is a plausible slug matching no row in the
 * database. See ADR 0012.
 */
public record CategoryVocabulary(List<String> slugs) {

	boolean has(String slug) {
		return slug != null && slugs.stream().anyMatch(slug::equalsIgnoreCase);
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
		return slugs.stream().filter(slug::equalsIgnoreCase).findFirst().orElse(null);
	}

}
