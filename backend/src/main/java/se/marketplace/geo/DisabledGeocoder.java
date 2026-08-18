package se.marketplace.geo;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No geocoder configured.
 *
 * <p>The default, and deliberately so. Enabling one by default would point every
 * developer's machine and every test run at a public service with a strict usage
 * policy, from an application whose whole job is to submit addresses to it.
 *
 * <p>Answering empty rather than throwing keeps the sweep's behaviour identical
 * to what it was before this module existed: salons stay unplaced and an
 * operator places them. The warning is logged once at startup rather than per
 * attempt, because the useful signal is "this deployment has no geocoder", and
 * repeating it per salon would bury it.
 */
@Component
@ConditionalOnProperty(name = "marketplace.geocoding.provider", havingValue = "none", matchIfMissing = true)
class DisabledGeocoder implements GeocoderPort {

	private static final Logger log = LoggerFactory.getLogger(DisabledGeocoder.class);

	DisabledGeocoder() {
		log.warn("no geocoder configured — self-serve salons stay unplaced until "
			+ "an operator places them. Set marketplace.geocoding.provider=nominatim");
	}

	@Override
	public Optional<Placement> locate(Address address) {
		return Optional.empty();
	}

}
