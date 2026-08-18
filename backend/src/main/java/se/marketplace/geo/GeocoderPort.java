package se.marketplace.geo;

import java.util.Optional;

/**
 * The one place that knows how to ask a third party where an address is.
 *
 * <p>A port for the usual reason — the geocoder is a vendor decision, and
 * Nominatim, Pelias and a commercial API are all plausible — but also because
 * the interesting logic is not the HTTP call. It is deciding whether an answer
 * is good enough to store, and that judgement belongs on this side of the seam
 * where it can be tested without a network.
 */
public interface GeocoderPort {

	/**
	 * Where this address is, if the geocoder is confident enough to say.
	 *
	 * <p>Empty is a normal outcome and not an error. It means the address could
	 * not be resolved to at least a street, which happens constantly with real
	 * input: an apartment number the map data has never heard of, a misspelling,
	 * a new building, a postal box. The caller's job is then to leave the salon
	 * unplaced, not to substitute something plausible.
	 *
	 * @throws GeocoderUnavailable if the geocoder itself could not be reached.
	 *         Distinct from empty on purpose: "we do not know where this is" and
	 *         "we could not ask" call for opposite responses — the first should
	 *         stop being retried, the second should be retried shortly.
	 */
	Optional<Placement> locate(Address address);

	record Address(String line, String postalCode, String city, String country) {}

	/**
	 * How precisely the geocoder claims to have matched.
	 *
	 * <p>Only two values, and neither is "city". That is the point of the enum:
	 * a coarser match is not a less precise placement, it is a different address
	 * from the one asked about, and the type refuses to carry it.
	 */
	enum Precision {

		/** A building. The address as given exists in the map data. */
		ROOFTOP,

		/**
		 * A street. The house number was not matched but the street was, which
		 * for a radius measured in kilometres is a distinction without a
		 * difference.
		 */
		STREET
	}

	/**
	 * @param matched what the geocoder says it matched, kept verbatim. An
	 *        operator checking a suspicious placement wants to see the address
	 *        the geocoder thought it was answering about, which is often subtly
	 *        not the one we sent.
	 */
	record Placement(double latitude, double longitude, Precision precision, String matched) {}

	class GeocoderUnavailable extends RuntimeException {

		public GeocoderUnavailable(String message) {
			super(message);
		}

		public GeocoderUnavailable(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
