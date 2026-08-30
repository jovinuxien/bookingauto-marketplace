package se.marketplace.vehicles;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import se.marketplace.vehicles.VehicleLookupRepository.Pending;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * Fills in what the registry knows, after the fact.
 *
 * <p>Same shape as {@code ProviderPlacement}: a bounded batch on a timer, a
 * counted failure for a plate the registry does not know, and a stopped pass
 * for a registry that cannot be asked. The one difference is the stakes — a
 * salon nobody can find sells nothing, whereas a booking without a make is
 * still a booking — which is why this sweep is allowed to be lazier.
 */
@Component
class BookingVehicles {

	private static final Logger log = LoggerFactory.getLogger(BookingVehicles.class);

	private final VehicleLookupRepository repository;
	private final VehicleRegistryPort registry;

	@Value("${marketplace.vehicles.max-attempts:3}")
	private int maxAttempts;

	@Value("${marketplace.vehicles.batch-size:20}")
	private int batchSize;

	BookingVehicles(VehicleLookupRepository repository, VehicleRegistryPort registry) {
		this.repository = repository;
		this.registry = registry;
	}

	@Scheduled(
		fixedDelayString = "${marketplace.vehicles.interval-ms:300000}",
		initialDelayString = "${marketplace.vehicles.initial-delay-ms:30000}")
	void lookUpWaiting() {
		List<Pending> waiting = repository.needingLookup(maxAttempts, batchSize);

		if (waiting.isEmpty()) {
			return;
		}

		log.debug("looking up {} plate(s)", waiting.size());

		for (Pending pending : waiting) {
			try {
				lookUp(pending);
			}
			catch (RegistryUnavailable e) {
				// Not the plate's fault, so nothing is counted against it and
				// the pass stops; the next pass finds them all still waiting.
				log.warn("vehicle registry unavailable, stopping this pass: {}", e.getMessage());
				return;
			}
		}
	}

	private void lookUp(Pending pending) {
		Optional<RegistrationNumber> plate = RegistrationNumber.parse(pending.registrationNumber());

		if (plate.isEmpty()) {
			// Stored before normalisation existed, or by a path that skipped
			// it. Counted so it stops being asked about; the workshop still has
			// the text the customer typed.
			repository.recordFailure(pending.bookingId(), "not a plate: " + pending.registrationNumber());
			return;
		}

		Optional<Vehicle> found = registry.lookup(plate.get());

		if (found.isEmpty()) {
			repository.recordFailure(pending.bookingId(), "registry does not know " + plate.get().value());
			log.info("no vehicle for booking {} — {}", pending.bookingId(), plate.get().display());
			return;
		}

		int updated = repository.record(pending.bookingId(), found.get());

		if (updated == 0) {
			log.debug("booking {} was filled in while we were asking", pending.bookingId());
			return;
		}

		log.info("booking {}: {} is a {}", pending.bookingId(), plate.get().display(),
			found.get().describe());
	}

}
