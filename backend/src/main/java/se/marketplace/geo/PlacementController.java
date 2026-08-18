package se.marketplace.geo;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Placing a salon by hand.
 *
 * <p>Not a fallback for when the geocoder is unavailable — a fallback for when
 * it is <em>right</em> to refuse. Real addresses exist that no geocoder will
 * ever match: a new building, a unit inside a shopping centre, a salon in
 * someone's home. The alternative to a person with a map is a plausible guess,
 * and this module's whole position is that a guess is worse than nothing.
 *
 * <p>An operator's placement outranks the sweep permanently. Once a person has
 * put a salon somewhere, no later geocode moves it.
 *
 * <p>Deliberately not mounted under {@code /api/providers}: that prefix carries
 * a public GET rule for the consumer catalogue, and an operator listing of
 * unplaced salons -- addresses and all -- sitting behind an earlier permitAll
 * matcher is a leak that compiles, passes every test, and is invisible.
 */
@RestController
@RequestMapping("/api/placements")
class PlacementController {

	private final PlacementRepository repository;

	private static final int MAX_ATTEMPTS_FOR_LISTING = 3;

	PlacementController(PlacementRepository repository) {
		this.repository = repository;
	}

	/** Everything with no point, so an operator can see the work. */
	@GetMapping
	ResponseEntity<List<PlacementRepository.Stranded>> unplaced() {
		return ResponseEntity.ok(repository.stranded(MAX_ATTEMPTS_FOR_LISTING));
	}

	@PutMapping("/{id}")
	ResponseEntity<Void> place(@PathVariable long id, @RequestBody Coordinates coordinates) {
		validate(coordinates);

		if (repository.place(id, coordinates.latitude(), coordinates.longitude(), "operator") == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no provider " + id);
		}

		return ResponseEntity.noContent().build();
	}

	/**
	 * Checked here rather than left to PostGIS, which accepts any pair of numbers
	 * quite happily. A transposed latitude and longitude is the classic mistake
	 * and it produces a point that is valid, storable, and in the Gulf of Guinea.
	 */
	private static void validate(Coordinates coordinates) {
		if (coordinates == null
			|| coordinates.latitude() == null || coordinates.longitude() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"latitude and longitude are both required");
		}
		if (coordinates.latitude() < -90 || coordinates.latitude() > 90) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"latitude must be between -90 and 90");
		}
		if (coordinates.longitude() < -180 || coordinates.longitude() > 180) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"longitude must be between -180 and 180");
		}
	}

	record Coordinates(Double latitude, Double longitude) {}

}
