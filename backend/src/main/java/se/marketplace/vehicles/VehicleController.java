package se.marketplace.vehicles;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import se.marketplace.ratelimit.RateLimiter;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;

/**
 * Plate in, car out. The public face of the cache.
 *
 * <p>A paid call behind a free URL, which is the same shape as
 * {@code /api/search/ask} and gets the same treatment: counted per address
 * before any work is done, with a ceiling that a person typing plates for
 * their own cars will never meet and a script will meet in a minute. The
 * cache is the second defence — a plate that has been asked about is free
 * to ask about again — and the first is this counter.
 *
 * <p>400 is not a plate, 404 is a plate the register does not know, and
 * 503 with {@code Retry-After} is a registry that could not be asked with
 * nothing cached to fall back on. The page treats 503 as "list prices, and
 * say so", not as an error.
 */
@RestController
@RequestMapping("/api/vehicles")
class VehicleController {

	private static final Duration HOUR = Duration.ofHours(1);

	private final Vehicles vehicles;
	private final RateLimiter limiter;

	@Value("${marketplace.vehicles.lookups-per-ip-per-hour:30}")
	private int perIpPerHour;

	@Value("${marketplace.vehicles.retry-after-seconds:60}")
	private int retryAfterSeconds;

	VehicleController(Vehicles vehicles, RateLimiter limiter) {
		this.vehicles = vehicles;
		this.limiter = limiter;
	}

	@GetMapping("/{plate}")
	ResponseEntity<?> lookup(@PathVariable String plate, HttpServletRequest http) {
		Optional<RegistrationNumber> parsed = RegistrationNumber.parse(plate);

		if (parsed.isEmpty()) {
			return ResponseEntity.badRequest().body("not a registration number");
		}

		if (!limiter.allow("vehicles:ip:" + http.getRemoteAddr(), perIpPerHour, HOUR)) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
		}

		try {
			return vehicles.lookup(parsed.get())
				.<ResponseEntity<?>>map(vehicle -> ResponseEntity.ok(View.of(parsed.get(), vehicle)))
				.orElseGet(() -> ResponseEntity.notFound().build());
		}
		catch (RegistryUnavailable e) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header("Retry-After", String.valueOf(retryAfterSeconds))
				.build();
		}
	}

	/** What a page needs, and no more: no owner, no VIN, no mileage. */
	record View(
		String registrationNumber,
		String display,
		String make,
		String model,
		Integer modelYear,
		String tyreFront,
		String tyreRear,
		String tyres,
		String description
	) {
		static View of(RegistrationNumber plate, Vehicle vehicle) {
			return new View(plate.value(), plate.display(), vehicle.make(), vehicle.model(),
				vehicle.modelYear(), vehicle.tyreFront(), vehicle.tyreRear(), vehicle.tyres(),
				vehicle.describe());
		}
	}

}
