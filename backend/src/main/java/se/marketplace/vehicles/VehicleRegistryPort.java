package se.marketplace.vehicles;

import java.util.Optional;

/**
 * The one place that knows how to ask a third party what a plate is.
 *
 * <p>A port for the usual reason — Transportstyrelsen, biluppgifter and
 * car.info are all plausible and none has been chosen — and, as with
 * {@code GeocoderPort}, because the interesting decisions are not the HTTP
 * call. They are what to do when the registry does not know the plate and
 * what to do when the registry could not be asked, and those belong on this
 * side of the seam where they can be tested without a network.
 */
public interface VehicleRegistryPort {

	/**
	 * What this plate is, if the registry knows.
	 *
	 * <p>Empty is a normal outcome and not an error: a foreign plate, a typo,
	 * a car registered yesterday. The caller's job is then to leave the
	 * booking with the plate as typed, which is what the workshop would have
	 * had anyway.
	 *
	 * @throws RegistryUnavailable if the registry itself could not be reached.
	 *         Distinct from empty on purpose: "we do not know this car" and "we
	 *         could not ask" call for opposite responses — the first should
	 *         stop being retried, the second should be retried shortly.
	 */
	Optional<Vehicle> lookup(RegistrationNumber plate);

	class RegistryUnavailable extends RuntimeException {
		public RegistryUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
