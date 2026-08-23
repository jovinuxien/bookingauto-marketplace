package se.marketplace.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of the agent that is not a model.
 *
 * <p>Every test here hands {@link UnderstoodQuestion#ground} an
 * {@link Interpretation} written by hand and checks what survives. That is the
 * point of the split: the step that decides what reaches SQL runs the same way
 * whether the string came from Anthropic or from this file, so the behaviour
 * that matters is testable on a machine with no API key and no network.
 *
 * <p>What is not tested here is whether a model reads "på lördag" correctly.
 * That is a question about a model, it cannot be answered by an assertion, and
 * pretending otherwise with a stubbed response would only test the stub.
 */
class QueryGroundingTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 22);

	private static final CategoryVocabulary VOCABULARY =
		new CategoryVocabulary(List.of("har", "massage", "hudvard"));

	private static AskedQuestion asked() {
		return new AskedQuestion("balayage på lördag", 59.32, 18.06, 5000, TODAY, 14, 20);
	}

	private static Interpretation said(String category, String day, String partOfDay) {
		return new Interpretation(category, day, partOfDay, "Hår, lördag eftermiddag");
	}

	@Test
	@DisplayName("a category we have survives, in the spelling the database uses")
	void keepsKnownCategory() {
		// Matched loosely and stored canonically. The SQL compares exactly, so
		// forgiving a model its capitalisation must not mean forgiving it a
		// category we do not have.
		var understood = UnderstoodQuestion.ground(asked(), said("HAR", "", ""), VOCABULARY);

		assertThat(understood.request().categorySlug()).isEqualTo("har");
		assertThat(understood.ignored()).isEmpty();
	}

	@Test
	@DisplayName("a category we do not have is dropped and named")
	void dropsInventedCategory() {
		// The failure this whole design is arranged around. 'harfargning' is a
		// perfectly plausible Swedish slug matching no row, and left in place it
		// returns nothing — which reads as a city with no availability rather
		// than as a filter nobody chose.
		var understood = UnderstoodQuestion.ground(asked(), said("harfargning", "", ""), VOCABULARY);

		assertThat(understood.request().categorySlug()).isNull();
		assertThat(understood.ignored()).containsExactly("Vi har ingen kategori som heter ”harfargning”");
	}

	@Test
	@DisplayName("a date inside the index horizon survives")
	void keepsReachableDate() {
		var understood = UnderstoodQuestion.ground(asked(), said("", "2026-08-29", ""), VOCABULARY);

		assertThat(understood.request().day()).isEqualTo(LocalDate.of(2026, 8, 29));
		assertThat(understood.ignored()).isEmpty();
	}

	@Test
	@DisplayName("a date past the horizon is refused, and the furthest we know is named")
	void dropsDateBeyondHorizon() {
		// 14 days counting today ends on the 4th. The index has no rows past it
		// and never will, so running the query would answer a real question with
		// a confident empty page.
		var understood = UnderstoodQuestion.ground(asked(), said("", "2026-10-01", ""), VOCABULARY);

		assertThat(understood.request().day()).isEqualTo(TODAY);
		assertThat(understood.ignored()).containsExactly("Vi känner bara till tider fram till 2026-09-04");
	}

	@Test
	@DisplayName("yesterday is not a search")
	void dropsPastDate() {
		var understood = UnderstoodQuestion.ground(asked(), said("", "2026-08-21", ""), VOCABULARY);

		assertThat(understood.request().day()).isEqualTo(TODAY);
		assertThat(understood.ignored()).containsExactly("2026-08-21 har redan varit");
	}

	@Test
	@DisplayName("something that is not a date is dropped rather than thrown")
	void dropsUnparseableDate() {
		// A parse failure here must not become an exception. The customer typed
		// a search and is owed results; the date was the optional part.
		var understood = UnderstoodQuestion.ground(asked(), said("har", "på lördag", ""), VOCABULARY);

		assertThat(understood.request().day()).isEqualTo(TODAY);
		assertThat(understood.request().categorySlug()).isEqualTo("har");
		assertThat(understood.ignored()).containsExactly("Kunde inte tolka datumet ”på lördag”");
	}

	@Test
	@DisplayName("a part of day we do not have widens to ANY")
	void dropsUnknownPartOfDay() {
		var understood = UnderstoodQuestion.ground(asked(), said("", "", "LUNCHTIME"), VOCABULARY);

		assertThat(understood.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.ANY);
		assertThat(understood.ignored())
			.containsExactly("Kunde inte tolka tiden på dygnet ”LUNCHTIME”");
	}

	@Test
	@DisplayName("a part of day we do have survives")
	void keepsKnownPartOfDay() {
		var understood = UnderstoodQuestion.ground(asked(), said("", "", "afternoon"), VOCABULARY);

		assertThat(understood.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.AFTERNOON);
		assertThat(understood.ignored()).isEmpty();
	}

	@Test
	@DisplayName("saying nothing is a correct answer and draws no complaint")
	void blanksAreNotFailures() {
		// The prompt tells the model that blank is right when the sentence does
		// not say. If blank were reported as something ignored, the customer
		// would be told we refused a filter they never asked for.
		var understood = UnderstoodQuestion.ground(asked(), said("", "", ""), VOCABULARY);

		assertThat(understood.request().categorySlug()).isNull();
		assertThat(understood.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.ANY);
		assertThat(understood.request().day()).isEqualTo(TODAY);
		assertThat(understood.ignored()).isEmpty();
	}

	@Test
	@DisplayName("everything the model was not asked about is untouched")
	void neverMovesTheCustomer() {
		// Position, radius and limit come from the browser and the UI. A model
		// that could change them could answer "salons near me" with a different
		// "me", and a limit is a bill.
		var understood = UnderstoodQuestion.ground(
			asked(), said("massage", "2026-08-25", "MORNING"), VOCABULARY);

		assertThat(understood.request().latitude()).isEqualTo(59.32);
		assertThat(understood.request().longitude()).isEqualTo(18.06);
		assertThat(understood.request().radiusMetres()).isEqualTo(5000);
		assertThat(understood.request().limit()).isEqualTo(20);
	}

	@Test
	@DisplayName("several bad fields do not take the good ones with them")
	void dropsFieldByField() {
		var understood = UnderstoodQuestion.ground(
			asked(), said("nagelvard", "nästa vecka", "MORNING"), VOCABULARY);

		assertThat(understood.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.MORNING);
		assertThat(understood.request().categorySlug()).isNull();
		assertThat(understood.request().day()).isEqualTo(TODAY);
		assertThat(understood.ignored()).hasSize(2);
	}

	@Test
	@DisplayName("the plain query is a search, not an error")
	void plainIsUsable() {
		// What a deployment with the gate off serves, and what a provider outage
		// serves. It has to be the product, because it is the product: this is
		// the query /api/search already runs.
		var plain = UnderstoodQuestion.plain(asked(), "not enabled");

		assertThat(plain.request().categorySlug()).isNull();
		assertThat(plain.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.ANY);
		assertThat(plain.request().radiusMetres()).isEqualTo(5000);
		assertThat(plain.summary()).isNull();
	}

	@Test
	@DisplayName("an empty vocabulary refuses every category")
	void emptyVocabularyRefusesEverything() {
		// A fresh database, or a reconciler that has not run. Nothing is known
		// to exist, so nothing may be filtered on — the search widens instead of
		// returning nothing.
		var understood = UnderstoodQuestion.ground(
			asked(), said("har", "", ""), new CategoryVocabulary(List.of()));

		assertThat(understood.request().categorySlug()).isNull();
		assertThat(understood.ignored()).containsExactly("Vi har ingen kategori som heter ”har”");
	}

}
