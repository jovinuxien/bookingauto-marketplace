package se.marketplace.search;

import java.util.List;

/**
 * A provider and what it sells.
 *
 * <p>Everything a provider page needs and nothing a booking needs. Prices and
 * durations here are for display; the funnel re-reads them when it freezes a
 * quote, because a page can be old and a sale cannot.
 */
public record ProviderDetail(
	long id,
	String slug,
	String name,
	String city,
	String addressLine,
	String description,
	List<Service> services
) {

	public record Service(
		long id,
		String name,
		String categorySlug,
		int durationMinutes,
		int priceMinor,
		String currency
	) {}

}
