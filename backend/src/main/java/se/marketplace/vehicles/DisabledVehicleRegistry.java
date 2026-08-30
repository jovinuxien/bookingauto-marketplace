package se.marketplace.vehicles;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No registry, and the application runs anyway.
 *
 * <p>Bookings keep the plate the customer typed and nothing is looked up. The
 * default, because every candidate vendor is a contract and an API key, and
 * the vertical should exist before either is signed.
 */
@Component
@ConditionalOnProperty(name = "marketplace.vehicles.registry", havingValue = "none", matchIfMissing = true)
class DisabledVehicleRegistry implements VehicleRegistryPort {

	private static final Logger log = LoggerFactory.getLogger(DisabledVehicleRegistry.class);

	DisabledVehicleRegistry() {
		log.info("no vehicle registry configured — bookings carry the plate as typed "
			+ "and nothing is looked up. Set marketplace.vehicles.registry when a vendor is chosen");
	}

	@Override
	public Optional<Vehicle> lookup(RegistrationNumber plate) {
		return Optional.empty();
	}

}
