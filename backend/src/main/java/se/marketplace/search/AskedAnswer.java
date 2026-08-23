package se.marketplace.search;

import java.time.LocalDate;
import java.util.List;

/**
 * Results, and what we thought was being asked for.
 *
 * <p>The interpretation is returned rather than applied silently, for the same
 * reason {@code SearchHit} carries {@code indexAgeSeconds}: the caller is
 * looking at something approximate and is entitled to see the approximation.
 * Here it is sharper than staleness — a filter the customer cannot see is one
 * they cannot correct, and they will blame the salons for the empty page.
 *
 * <p>{@code applied} is what actually went into the SQL, not what the model
 * proposed. Where they differ, {@code ignored} says so.
 */
public record AskedAnswer(

	/** One line, in the customer's language, or null when nothing read the text. */
	String summary,

	/** Filters that were proposed and refused, in words. Usually empty. */
	List<String> ignored,

	Applied applied,

	List<SearchPort.SearchHit> hits
) {

	public record Applied(
		String categorySlug,
		LocalDate day,
		SearchPort.PartOfDay partOfDay,
		int radiusMetres
	) {}

	static AskedAnswer of(UnderstoodQuestion understood, List<SearchPort.SearchHit> hits) {
		SearchPort.SearchRequest request = understood.request();

		return new AskedAnswer(
			understood.summary(),
			understood.ignored(),
			new Applied(
				request.categorySlug(), request.day(), request.partOfDay(), request.radiusMetres()),
			hits);
	}

}
