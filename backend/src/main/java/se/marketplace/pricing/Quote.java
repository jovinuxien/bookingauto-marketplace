package se.marketplace.pricing;

/**
 * What this service costs for this car, and why.
 *
 * @param label      the matching rule's label, or null for the list price
 * @param forVehicle true when a rule matched — the price is for the car,
 *                   not for whoever walks in
 */
public record Quote(int priceMinor, String label, boolean forVehicle) {

	public static Quote list(int priceMinor) {
		return new Quote(priceMinor, null, false);
	}

}
