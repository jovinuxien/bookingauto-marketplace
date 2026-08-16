package se.marketplace.search;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The consumer search endpoint.
 *
 * <p>Serves bokadirekt.se. Deliberately returns {@code indexAgeSeconds} on every
 * hit: the caller is looking at an approximation and is entitled to know how old
 * it is.
 */
@RestController
@RequestMapping("/api/search")
class SearchController {

	private final SearchPort search;

	SearchController(SearchPort search) {
		this.search = search;
	}

	@GetMapping
	List<SearchPort.SearchHit> nearby(
		@RequestParam double lat,
		@RequestParam double lon,
		@RequestParam(defaultValue = "5000") int radius,
		@RequestParam(required = false) String category,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
		@RequestParam(defaultValue = "ANY") SearchPort.PartOfDay when,
		@RequestParam(defaultValue = "20") int limit) {

		return search.nearby(
			new SearchPort.SearchRequest(lat, lon, radius, category, day, when, limit));
	}

}
