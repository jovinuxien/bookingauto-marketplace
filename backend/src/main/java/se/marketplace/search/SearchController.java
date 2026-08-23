package se.marketplace.search;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>Two ways in, and they are separate on purpose. {@code /api/search} takes
 * filters and is one indexed query. {@code /api/search/ask} takes a sentence and
 * puts a model in front of the same query — two orders of magnitude slower, off
 * unless a deployment enables it, and metered. Folding it into the first would
 * average the latency of the two into a number that describes neither, and would
 * make the endpoint the whole product depends on inherit a dependency it does
 * not need. See ADR 0012.
 */
@RestController
@RequestMapping("/api/search")
class SearchController {

	private final SearchPort search;
	private final QueryUnderstanding understanding;

	/**
	 * Where the salons are, which is what "today" and "Saturday" mean to the
	 * person typing. Read from Cal's setting rather than given a second name of
	 * its own — it is one fact about the market, and two properties that must
	 * agree eventually will not.
	 */
	@Value("${marketplace.cal.timezone:Europe/Stockholm}")
	private String timezone;

	/**
	 * How far the availability index reaches, which bounds what a date in a
	 * sentence is allowed to resolve to. Sync's property, because sync owns the
	 * answer; duplicating the number here would let the two drift and the
	 * symptom would be a search that confidently returns nothing.
	 */
	@Value("${marketplace.reconcile.horizon-days:14}")
	private int horizonDays;

	SearchController(SearchPort search, QueryUnderstanding understanding) {
		this.search = search;
		this.understanding = understanding;
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

	/**
	 * Search by sentence.
	 *
	 * <p>Position is still a parameter. The model reads the words and nothing
	 * else — it cannot move the customer, widen the radius or raise the limit,
	 * because those are the ones a wrong answer would be expensive in.
	 */
	@GetMapping("/ask")
	AskedAnswer ask(
		@RequestParam String q,
		@RequestParam double lat,
		@RequestParam double lon,
		@RequestParam(defaultValue = "5000") int radius,
		@RequestParam(defaultValue = "20") int limit) {

		UnderstoodQuestion understood = understanding.of(new AskedQuestion(
			q, lat, lon, radius, LocalDate.now(ZoneId.of(timezone)), horizonDays, limit));

		return AskedAnswer.of(understood, search.nearby(understood.request()));
	}

}
