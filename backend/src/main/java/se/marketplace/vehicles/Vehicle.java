package se.marketplace.vehicles;

/**
 * What a registry knows about a plate, reduced to what a workshop needs.
 *
 * <p>Make and model are what get said on the phone; the year is what tells a
 * tyre fitter which generation. Nothing about the owner: the registry may
 * have it and this record refuses to carry it, because a marketplace has no
 * business storing who owns a car it did not sell.
 *
 * @param modelYear null when the registry does not say
 */
public record Vehicle(String make, String model, Integer modelYear) {

	/** One line, for a booking list: {@code Volvo V70 (2016)}. */
	public String describe() {
		String name = (make == null ? "" : make) + " " + (model == null ? "" : model);
		return modelYear == null ? name.trim() : name.trim() + " (" + modelYear + ")";
	}

}
