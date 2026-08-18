package se.marketplace.geo;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import se.marketplace.geo.GeocoderPort.Address;
import se.marketplace.geo.GeocoderPort.GeocoderUnavailable;
import se.marketplace.geo.GeocoderPort.Placement;
import se.marketplace.geo.PlacementRepository.Unplaced;

/**
 * Places salons that arrived without coordinates.
 *
 * <p>A sweep rather than a step in signup, and the reasoning is the same as the
 * availability reconciler's: the thing that must not fail is the salon
 * registering. Geocoding inline would put a third party's availability directly
 * in front of a signup, so an OSM outage would become a salon that could not
 * join. Here, an outage means a salon is unfindable for a few minutes longer.
 *
 * <p>Work is bounded per pass. The public Nominatim instance permits one request
 * per second and blocks applications that ignore that, so the batch is small and
 * spaced — a backlog is worked off over several passes and never in a burst.
 */
@Component
class ProviderPlacement {

	private static final Logger log = LoggerFactory.getLogger(ProviderPlacement.class);

	private final PlacementRepository repository;
	private final GeocoderPort geocoder;

	/**
	 * How many times an address is asked about before it becomes a person's
	 * problem. Low, because a refusal is nearly always the address being
	 * unmatchable rather than the geocoder having a bad day, and asking a fourth
	 * time has never once changed the answer.
	 */
	@Value("${marketplace.geocoding.max-attempts:3}")
	private int maxAttempts;

	@Value("${marketplace.geocoding.batch-size:5}")
	private int batchSize;

	/**
	 * Between calls within a pass. Nominatim's usage policy is an absolute
	 * maximum of one request per second, so this is deliberately over it.
	 */
	@Value("${marketplace.geocoding.pause-ms:1200}")
	private long pauseMs;

	ProviderPlacement(PlacementRepository repository, GeocoderPort geocoder) {
		this.repository = repository;
		this.geocoder = geocoder;
	}

	@Scheduled(
		fixedDelayString = "${marketplace.geocoding.interval-ms:300000}",
		initialDelayString = "${marketplace.geocoding.initial-delay-ms:20000}")
	void placeWaiting() {
		List<Unplaced> waiting = repository.needingLocation(maxAttempts, batchSize);

		if (waiting.isEmpty()) {
			return;
		}

		log.debug("placing {} salon(s)", waiting.size());

		for (Unplaced salon : waiting) {
			try {
				place(salon);
			}
			catch (GeocoderUnavailable e) {
				// Not the address's fault, so nothing is counted against it and
				// the pass stops: if the geocoder is down for one salon it is
				// down for the next four, and the next pass will find them all
				// still waiting.
				log.warn("geocoder unavailable, stopping this pass: {}", e.getMessage());
				return;
			}

			pause();
		}
	}

	private void place(Unplaced salon) {
		Address address = new Address(
			salon.addressLine(), salon.postalCode(), salon.city(), countryName(salon.country()));

		if (!SwedishAddress.worthAsking(salon.addressLine(), salon.city())) {
			repository.recordFailure(salon.id(), "no usable street address");
			return;
		}

		var located = geocoder.locate(address);

		if (located.isEmpty()) {
			// Counted, so this address stops being asked about and starts being
			// an operator's list item. The address itself is the useful part of
			// the message: it is what a person has to look at.
			repository.recordFailure(salon.id(),
				"no match for '" + salon.addressLine() + ", " + salon.city() + "'");
			log.info("no placement for provider {} — {}", salon.id(), salon.addressLine());
			return;
		}

		Placement placement = located.get();
		int updated = repository.place(
			salon.id(), placement.latitude(), placement.longitude(), "geocoded");

		if (updated == 0) {
			// Someone placed it by hand between the read and the write. Theirs
			// wins; the repository guard is what enforces that.
			log.debug("provider {} was placed by hand while we were geocoding", salon.id());
			return;
		}

		log.info("placed provider {} at {},{} ({}) from '{}'",
			salon.id(), placement.latitude(), placement.longitude(),
			placement.precision(), placement.matched());
	}

	/**
	 * Nominatim wants a country name and the column holds an ISO code. Only the
	 * countries this platform actually operates in are mapped, and an unknown
	 * code is passed through rather than guessed at — a wrong country is how an
	 * address in Malmö becomes a confident match in another hemisphere.
	 */
	private static String countryName(String code) {
		return switch (code == null ? "" : code.trim().toUpperCase()) {
			case "SE" -> "Sweden";
			case "NO" -> "Norway";
			case "DK" -> "Denmark";
			case "FI" -> "Finland";
			default -> code;
		};
	}

	private void pause() {
		try {
			Thread.sleep(pauseMs);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
