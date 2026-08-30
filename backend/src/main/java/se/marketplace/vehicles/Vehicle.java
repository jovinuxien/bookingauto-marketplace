package se.marketplace.vehicles;

/**
 * What a registry knows about a plate, reduced to what a workshop needs.
 *
 * <p>Make and model are what get said on the phone; the year is what tells a
 * tyre fitter which generation; the tyre dimensions are what goes on the
 * rack before the car arrives. Nothing about the owner: the registry may
 * have it and this record refuses to carry it, because a marketplace has no
 * business storing who owns a car it did not sell.
 *
 * @param modelYear null when the registry does not say
 * @param tyreFront as printed on the sidewall, "205/55 R16"; null if unknown
 * @param tyreRear  null when unknown, or the same as the front on most cars
 */
public record Vehicle(String make, String model, Integer modelYear, String tyreFront, String tyreRear) {

	/** One line, for a booking list: {@code Volvo V70 (2016)}. */
	public String describe() {
		String name = (make == null ? "" : make) + " " + (model == null ? "" : model);
		return modelYear == null ? name.trim() : name.trim() + " (" + modelYear + ")";
	}

	/** {@code 205/55 R16}, or {@code 225/45 R18 / 255/40 R18} when they differ; null if unknown. */
	public String tyres() {
		if (tyreFront == null || tyreFront.isBlank()) {
			return tyreRear == null || tyreRear.isBlank() ? null : tyreRear.trim();
		}
		if (tyreRear == null || tyreRear.isBlank() || tyreRear.trim().equals(tyreFront.trim())) {
			return tyreFront.trim();
		}
		return tyreFront.trim() + " / " + tyreRear.trim();
	}

}
