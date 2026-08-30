package se.marketplace.search;

import java.time.LocalDate;
import java.util.List;

/**
 * The seam.
 *
 * <p>One port, so that adding text relevance later is a wiring change rather
 * than a rewrite. Today there is a single PostGIS implementation. When free-text
 * discovery earns its place, a composite implementation asks OCSS to rank the
 * text and then filters those results here by geo and availability — see
 * ADR 0006.
 */
public interface SearchPort {

	List<SearchHit> nearby(SearchRequest request);

	record SearchRequest(
		double latitude,
		double longitude,
		int radiusMetres,
		String categorySlug,
		LocalDate day,
		PartOfDay partOfDay,
		int limit
	) {}

	enum PartOfDay { ANY, MORNING, AFTERNOON, EVENING }

	record SearchHit(
		long providerId,
		String slug,
		String name,
		String city,
		int distanceMetres,
		String serviceName,
		int durationMinutes,
		int priceMinor,
		String currency,
		int freeSlots,
		java.time.Instant firstFreeAt,
		/**
		 * How old the index row is. Exposed rather than hidden: the caller is
		 * entitled to know it is looking at an approximation, and it is what
		 * makes staleness measurable instead of mysterious.
		 */
		long indexAgeSeconds,
		/** Null with no reviews — a new provider is not a bad one. */
		Double ratingAverage,
		int ratingCount
	) {}

}
