package se.marketplace.search;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * A sentence, turned into a query we are willing to run.
 *
 * <p>{@link #ground} is where the model stops being in charge. It is static,
 * takes no collaborators and calls nothing — every judgement in it can be tested
 * with a made-up {@link Interpretation} and no API key, which is the point. The
 * agent around it is thin on purpose.
 *
 * <p>The rule it enforces is one rule: <strong>a value we cannot verify is
 * dropped, not passed through.</strong> Dropping widens the search — the worst
 * outcome is more salons than the customer asked for, which they can see and
 * narrow. Passing through a category that matches no row returns nothing, which
 * is indistinguishable from a city with no availability, and the customer's
 * conclusion is that the platform is empty. This is the geocoder's argument
 * about city centroids, in a different column. See ADR 0012.
 */
public record UnderstoodQuestion(
	SearchPort.SearchRequest request,

	/** How the sentence was read, for showing back to whoever typed it. */
	String summary,

	/**
	 * What was in the interpretation and did not survive, in words. Returned to
	 * the caller rather than logged: a filter that was silently discarded is as
	 * invisible as one that was silently applied.
	 *
	 * <p>Swedish, because it is rendered on a Swedish page next to a summary the
	 * model was asked to write in the customer's own language. The precedent is
	 * already here — the landing pages and the outbox's emails are Swedish — and
	 * an English sentence in the middle of them would be a developer's note that
	 * escaped onto the page.
	 */
	List<String> ignored
) {

	/**
	 * The query as if nobody had read the text: everything within the radius.
	 *
	 * <p>The answer for a deployment with the gate off, and for a model that was
	 * slow, absent or wrong. It is deliberately the product we shipped before
	 * any of this existed — ADR 0012 requires every agent call to have a defined
	 * answer for failure, and search is a safe first subject precisely because
	 * its failure answer already works.
	 */
	static UnderstoodQuestion plain(AskedQuestion question, String why) {
		return new UnderstoodQuestion(
			new SearchPort.SearchRequest(
				question.latitude(), question.longitude(), question.radiusMetres(),
				null, question.today(), SearchPort.PartOfDay.ANY, question.limit()),
			null,
			why == null ? List.of() : List.of(why));
	}

	static UnderstoodQuestion ground(
		AskedQuestion question, Interpretation interpretation, CategoryVocabulary vocabulary) {

		List<String> ignored = new ArrayList<>();

		return new UnderstoodQuestion(
			new SearchPort.SearchRequest(
				question.latitude(),
				question.longitude(),
				question.radiusMetres(),
				category(interpretation.categorySlug(), vocabulary, ignored),
				day(interpretation.day(), question, ignored),
				partOfDay(interpretation.partOfDay(), ignored),
				question.limit()),
			blank(interpretation.summary()) ? null : interpretation.summary().strip(),
			List.copyOf(ignored));
	}

	private static String category(String proposed, CategoryVocabulary vocabulary, List<String> ignored) {
		if (blank(proposed)) {
			return null;
		}
		if (!vocabulary.has(proposed.strip())) {
			// Named rather than counted. An operator reading these is looking at
			// the gap between what customers ask for and what salons imported,
			// and the slug the model reached for is the useful half of that.
			ignored.add("Vi har ingen kategori som heter ”" + proposed.strip() + "”");
			return null;
		}
		return vocabulary.canonical(proposed.strip());
	}

	private static LocalDate day(String proposed, AskedQuestion question, List<String> ignored) {
		if (blank(proposed)) {
			return question.today();
		}

		LocalDate parsed;

		try {
			parsed = LocalDate.parse(proposed.strip());
		}
		catch (DateTimeParseException e) {
			ignored.add("Kunde inte tolka datumet ”" + proposed.strip() + "”");
			return question.today();
		}

		if (parsed.isBefore(question.today())) {
			ignored.add(parsed + " har redan varit");
			return question.today();
		}

		// The index reaches horizonDays forward, counting today. Beyond it there
		// are no rows and there is nothing to wait for, so the search would come
		// back empty and look like a city with nothing free. Refusing the date
		// and searching today is wrong in a way the customer can see; running it
		// is wrong in a way they cannot.
		LocalDate furthest = question.today().plusDays(question.horizonDays() - 1L);

		if (parsed.isAfter(furthest)) {
			ignored.add("Vi känner bara till tider fram till " + furthest);
			return question.today();
		}

		return parsed;
	}

	private static SearchPort.PartOfDay partOfDay(String proposed, List<String> ignored) {
		if (blank(proposed)) {
			return SearchPort.PartOfDay.ANY;
		}
		try {
			return SearchPort.PartOfDay.valueOf(proposed.strip().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			ignored.add("Kunde inte tolka tiden på dygnet ”" + proposed.strip() + "”");
			return SearchPort.PartOfDay.ANY;
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

}
