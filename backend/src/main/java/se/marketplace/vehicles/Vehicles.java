package se.marketplace.vehicles;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.marketplace.vehicles.VehicleCacheRepository.Cached;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * What a plate is — asked of the cache first and the registry once.
 *
 * <p>The one entry point for every reader: the public endpoint, the sweep
 * that fills in bookings, and — when ADR 0016's second phase lands — the
 * price matcher. All of them see the same answer for the same plate, and
 * the registry sees each plate once a year rather than once per reader.
 *
 * <p>Three outcomes, and the third is the one worth explaining. A fresh row
 * is served. A missing or stale row is asked of the registry and written
 * back, found or not. And when the registry cannot be asked, a stale row is
 * served anyway — a year-old make and model beats a 503 for a customer who
 * is looking at a page — while an empty cache propagates
 * {@link RegistryUnavailable}, because there is nothing honest to say.
 */
@Service
public class Vehicles {

	private static final Logger log = LoggerFactory.getLogger(Vehicles.class);

	private final VehicleCacheRepository cache;
	private final VehicleRegistryPort registry;

	/** How long a found car is trusted. Tyres and plates change hands; a year is the compromise. */
	@Value("${marketplace.vehicles.cache.known-days:365}")
	private int knownDays;

	/** How long "the registry does not know this plate" is trusted. Shorter: a new car is registered daily. */
	@Value("${marketplace.vehicles.cache.unknown-days:30}")
	private int unknownDays;

	@Value("${marketplace.vehicles.registry:none}")
	private String source;

	Vehicles(VehicleCacheRepository cache, VehicleRegistryPort registry) {
		this.cache = cache;
		this.registry = registry;
	}

	/**
	 * @throws RegistryUnavailable only when the registry could not be asked
	 *         <em>and</em> nothing, however stale, is cached for the plate
	 */
	public Optional<Vehicle> lookup(RegistrationNumber plate) {
		Optional<Cached> cached = cache.find(plate);

		if (cached.isPresent() && fresh(cached.get())) {
			return Optional.ofNullable(cached.get().vehicle());
		}

		Optional<Vehicle> answer;
		try {
			answer = registry.lookup(plate);
		}
		catch (RegistryUnavailable e) {
			if (cached.isPresent()) {
				log.warn("registry unavailable, serving {} from a cache entry dated {}: {}",
					plate.display(), cached.get().lookedUpAt(), e.getMessage());
				return Optional.ofNullable(cached.get().vehicle());
			}
			throw e;
		}

		cache.put(plate, answer, source);
		return answer;
	}

	/**
	 * What the cache holds, however old, and never the registry. For the
	 * booking saga (ADR 0016): a customer who came through the page has
	 * already caused the lookup, and one who did not gets the list price
	 * rather than a third party on the path.
	 */
	public Optional<Vehicle> cached(RegistrationNumber plate) {
		return cache.find(plate).map(Cached::vehicle);
	}

	private boolean fresh(Cached cached) {
		Duration ttl = Duration.ofDays(cached.known() ? knownDays : unknownDays);
		return cached.lookedUpAt().plus(ttl).isAfter(Instant.now());
	}

}
