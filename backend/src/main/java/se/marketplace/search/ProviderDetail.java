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
		String currency,
		/** Checkout asks for a registration number. */
		boolean asksVehicle,
		/** What everyone pays when no rule matches; equals priceMinor unless a rule did. */
		int listPriceMinor,
		/** The matching rule's label ("Volvo 2015–2019"), or null. */
		String priceLabel,
		/** True when priceMinor is for the car in ?regnr=, not the list price. */
		boolean pricedForVehicle,
		/** Extras the customer can tick at checkout. */
		List<se.marketplace.pricing.Addon> addons
	) {}

}
