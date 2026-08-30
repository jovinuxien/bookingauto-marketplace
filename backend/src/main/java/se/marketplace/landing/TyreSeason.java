package se.marketplace.landing;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What the calendar says about tyres, for the page that sells tyre changes.
 *
 * <p>Swedish law is the whole reason the bil &amp; däck vertical was chosen
 * (ADR 0015): winter tyres are required from 1 December to 31 March when
 * there is winter road condition, and studded tyres are permitted from
 * 1 October to 15 April. Every car in the country changes wheels twice a
 * year against those dates, and a person searching "däckbyte" in November
 * has one of them in mind. Saying which, on the page, is the difference
 * between a list of workshops and an answer.
 *
 * <p>Deterministic on a date so it can be tested; the controller supplies
 * today in Stockholm. Nothing here is a legal opinion — the dates are the
 * ones on Transportstyrelsen's page, and the copy hedges "vid vinterväglag"
 * exactly where the law does.
 */
final class TyreSeason {

	private static final MonthDay STUDS_ALLOWED_FROM = MonthDay.of(10, 1);
	private static final MonthDay WINTER_TYRES_FROM = MonthDay.of(12, 1);
	private static final MonthDay WINTER_TYRES_UNTIL = MonthDay.of(3, 31);
	private static final MonthDay STUDS_OFF_BY = MonthDay.of(4, 15);

	private TyreSeason() {
	}

	/**
	 * The notice for today, and there always is one: outside both change
	 * windows the useful thing to say is when the next one opens.
	 */
	static Notice on(LocalDate today) {
		MonthDay day = MonthDay.from(today);

		if (!day.isBefore(STUDS_ALLOWED_FROM) && day.isBefore(WINTER_TYRES_FROM)) {
			// October–November: the autumn window. The deadline is the one
			// people book against.
			LocalDate deadline = WINTER_TYRES_FROM.atYear(today.getYear());
			return new Notice(
				"Dags för vinterdäck",
				"Vinterdäck är krav från 1 december vid vinterväglag, och dubbdäck får "
					+ "sättas på från 1 oktober. Boka däckskiftet i tid — de sista veckorna "
					+ "i november är fullbokade hos de flesta verkstäder.",
				deadline);
		}

		if (!day.isBefore(WINTER_TYRES_FROM) || !day.isAfter(WINTER_TYRES_UNTIL)) {
			// December–March: winter. Nothing to book against yet; say when
			// the spring window opens.
			int year = day.isBefore(WINTER_TYRES_FROM) ? today.getYear() : today.getYear() + 1;
			return new Notice(
				"Vinterdäck gäller",
				"Vinterdäck är krav till och med 31 mars vid vinterväglag. Dubbdäck ska "
					+ "vara av senast 15 april — boka sommardäcken gärna redan nu.",
				STUDS_OFF_BY.atYear(year));
		}

		if (!day.isAfter(STUDS_OFF_BY)) {
			// 1–15 April: the spring window, and a hard date for studs.
			return new Notice(
				"Dags för sommardäck",
				"Dubbdäck ska vara av senast 15 april om det inte är vinterväglag. "
					+ "Boka däckskiftet nu.",
				STUDS_OFF_BY.atYear(today.getYear()));
		}

		// 16 April–30 September: summer. Next thing that happens is autumn.
		return new Notice(
			"Nästa däckskifte",
			"Vinterdäck krävs från 1 december vid vinterväglag. Dubbdäck får sättas "
				+ "på från 1 oktober.",
			WINTER_TYRES_FROM.atYear(today.getYear()));
	}

	/**
	 * @param deadline the date the copy is about — what a page can render as
	 *        "senast" and a search engine can read as an event
	 */
	record Notice(String heading, String body, LocalDate deadline) {

		private static final DateTimeFormatter SWEDISH =
			DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("sv"));

		/** "1 december" — formatted here rather than in the template, which may not construct a Locale. */
		String deadlineText() {
			return SWEDISH.format(deadline);
		}

	}

}
