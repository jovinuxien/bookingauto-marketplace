package se.marketplace.pricing;

import java.util.Locale;

import se.marketplace.vehicles.Vehicle;

/**
 * One row of {@code service_price_rule}: for a car like this, this price.
 *
 * @param make        exact, case-folded; null = any
 * @param modelPrefix prefix of the registry's model, case-folded; null = any
 * @param yearFrom    inclusive; null = open
 * @param yearTo      inclusive; null = open
 * @param rimFrom     inclusive inches; null = open
 * @param rimTo       inclusive inches; null = open
 */
public record PriceRule(
	long id,
	long serviceId,
	String make,
	String modelPrefix,
	Integer yearFrom,
	Integer yearTo,
	Integer rimFrom,
	Integer rimTo,
	int priceMinor,
	String label
) {

	/** How many constraints this rule sets. The most specific matching rule wins. */
	int specificity() {
		int n = 0;
		if (make != null) n++;
		if (modelPrefix != null) n++;
		if (yearFrom != null || yearTo != null) n++;
		if (rimFrom != null || rimTo != null) n++;
		return n;
	}

	boolean matches(Vehicle vehicle) {
		if (make != null && !make.equalsIgnoreCase(trimmed(vehicle.make()))) {
			return false;
		}
		if (modelPrefix != null) {
			String model = trimmed(vehicle.model());
			if (model == null || !model.toLowerCase(Locale.ROOT).startsWith(modelPrefix.toLowerCase(Locale.ROOT))) {
				return false;
			}
		}
		if (yearFrom != null || yearTo != null) {
			Integer year = vehicle.modelYear();
			if (year == null
				|| (yearFrom != null && year < yearFrom)
				|| (yearTo != null && year > yearTo)) {
				return false;
			}
		}
		if (rimFrom != null || rimTo != null) {
			// The front tyre decides. Staggered cars are priced per axle by
			// nobody's list, and the front is what is on the rack first.
			Integer rim = RimInches.of(vehicle.tyreFront()).orElse(null);
			if (rim == null
				|| (rimFrom != null && rim < rimFrom)
				|| (rimTo != null && rim > rimTo)) {
				return false;
			}
		}
		return true;
	}

	private static String trimmed(String value) {
		return value == null ? null : value.trim();
	}

}
