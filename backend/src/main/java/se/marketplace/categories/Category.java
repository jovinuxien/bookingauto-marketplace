package se.marketplace.categories;

import java.util.List;

/**
 * One category, as the rest of the application sees it.
 *
 * <p>Carries both names because they are genuinely different and confusing them
 * has already cost something: {@code slug} is the value in
 * {@code service.category_slug} and in a search filter, {@code path} is the URL
 * segment. The database says {@code har}; the page is {@code /frisor/stockholm},
 * because that is what a person types. Using the slug where the path belonged
 * once pointed every canonical at a URL that 404s.
 */
public record Category(
	String slug,
	String path,

	/** Swedish, and the only place it is written down. */
	String label,

	/**
	 * What customers type, rather than what we call it.
	 *
	 * <p>Lower case and already folded — see {@link Categories#fold} for what
	 * that means and why the folding is done here rather than by a database
	 * collation.
	 */
	List<String> synonyms,

	int sortOrder,

	/**
	 * The customer is bringing a car. Set by db/013 for the bil & däck
	 * categories; what checkout, the landing page and the booking funnel key
	 * off, so that none of them holds its own list of which slugs are cars.
	 */
	boolean vehicle
) {

	/**
	 * Whether this category is what {@code title} is about.
	 *
	 * <p>Substring rather than equality, because the strings this meets are
	 * event types a salon named itself: "Klippning dam 45 min", "Massage 60
	 * min". Both are the category plus noise, and the noise is unbounded.
	 *
	 * <p>Both sides are folded, so "Färgning" matches {@code fargning} and a
	 * salon that never types an umlaut is still understood.
	 */
	boolean describes(String title) {
		String folded = Categories.fold(title);
		return synonyms.stream().anyMatch(folded::contains);
	}

	/**
	 * The longest synonym that appears in {@code title}, or -1 for no match.
	 *
	 * <p>Length is the tie-break when two categories both match, and it is the
	 * right one for the collision that actually happens: "taktil massage"
	 * belongs to massage rather than to whichever category also lists
	 * "massage" — the more specific phrase is the more specific answer.
	 */
	int matchStrength(String title) {
		String folded = Categories.fold(title);
		return synonyms.stream()
			.filter(folded::contains)
			.mapToInt(String::length)
			.max()
			.orElse(-1);
	}

}
