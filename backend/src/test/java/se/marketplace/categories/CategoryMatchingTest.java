package se.marketplace.categories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a category out of a name a salon chose.
 *
 * <p>The strings here are the shape Cal actually hands over: an event type
 * title, written by a salon owner for their own customers, with no idea that
 * anything would ever parse it. "Klippning dam 45 min" is the normal case and
 * every awkward one in this file is a real form of the same thing.
 *
 * <p>This runs during provisioning, so it has to be deterministic — ADR 0011
 * keeps third parties off that path and ADR 0013 keeps models off it. Which
 * means it can be tested exactly, which is most of why it is worth having.
 */
class CategoryMatchingTest {

	private static final Category HAR = category("har", "frisor", "Frisörer",
		"klippning", "klipp", "fargning", "balayage", "slingor", "harvard");

	private static final Category MASSAGE = category("massage", "massage", "Massage",
		"massage", "taktil massage", "ryggmassage");

	private static final Category HUD = category("hud", "hudvard", "Hudvård",
		"ansiktsbehandling", "vaxning", "fransar");

	/**
	 * Folded on construction, the way {@link Categories} folds them on the way
	 * out of the database — so these tests exercise the same values production
	 * compares against rather than a tidier version of them.
	 */
	private static Category category(String slug, String path, String label, String... synonyms) {
		return new Category(slug, path, label,
			java.util.Arrays.stream(synonyms).map(Categories::fold).toList(), 10, false);
	}

	private static final List<Category> ALL = List.of(HAR, MASSAGE, HUD);

	/**
	 * The real thing, with the table swapped for a fixed list.
	 *
	 * <p>Subclassed rather than mocked, and rather than reimplementing the
	 * selection here: a test that re-derives which category wins would agree
	 * with itself while {@link Categories#classify} was wrong, which is the one
	 * outcome it exists to rule out. Only the reading of rows is replaced.
	 */
	private static final Categories CATEGORIES = new Categories(null) {
		@Override
		public List<Category> all() {
			return ALL;
		}
	};

	private static java.util.Optional<Category> classify(String title) {
		return CATEGORIES.classify(title);
	}

	@Test
	@DisplayName("the ordinary case is a substring of a name with noise around it")
	void matchesInsideARealTitle() {
		assertThat(classify("Klippning dam 45 min")).contains(HAR);
		assertThat(classify("Massage 60 min")).contains(MASSAGE);
		assertThat(classify("Ansiktsbehandling – classic")).contains(HUD);
	}

	@Test
	@DisplayName("the more specific phrase wins")
	void longestSynonymWins() {
		// Both categories match "taktil massage" if either lists "massage".
		// Length is the tie-break because the longer synonym is the more
		// specific claim, and the wrong answer here is silent: a service filed
		// under the wrong category is findable, just never by anyone looking
		// for it.
		assertThat(classify("Taktil massage 45 min")).contains(MASSAGE);
	}

	@Test
	@DisplayName("a Swedish keyboard is not required")
	void foldsDiacritics() {
		// Both directions. The synonym is stored as a Swede would write it and
		// the title may be typed either way, so folding has to happen on both
		// sides or one of the two spellings silently stops matching.
		assertThat(classify("Färgning + klipp")).contains(HAR);
		assertThat(classify("FARGNING")).contains(HAR);
		assertThat(classify("hårvård")).contains(HAR);
	}

	@Test
	@DisplayName("case is not a category")
	void ignoresCase() {
		assertThat(classify("KLIPPNING")).contains(HAR);
		assertThat(classify("Klippning")).contains(HAR);
	}

	@Test
	@DisplayName("a name that says nothing we know matches nothing")
	void refusesToGuess() {
		// Empty rather than a nearest guess. The caller has a default and is
		// expected to use it; a category invented here would be indistinguishable
		// from one a person chose, which is the rule this whole system runs on.
		assertThat(classify("Konsultation 15 min")).isEmpty();
		assertThat(classify("Presentkort")).isEmpty();
		assertThat(classify("")).isEmpty();
	}

	@Test
	@DisplayName("folding leaves letters that are not decorated alone")
	void foldingIsNarrow() {
		assertThat(Categories.fold("Klippning")).isEqualTo("klippning");
		assertThat(Categories.fold("Hårvård")).isEqualTo("harvard");
		assertThat(Categories.fold("Ögonbryn")).isEqualTo("ogonbryn");
	}

	@Test
	@DisplayName("slug and path are different words and stay different")
	void slugIsNotPath() {
		// The confusion that once pointed every canonical at a URL that 404s.
		// The database stores 'hud'; the page is /hudvard/stockholm.
		assertThat(HUD.slug()).isEqualTo("hud");
		assertThat(HUD.path()).isEqualTo("hudvard");
		assertThat(HAR.slug()).isNotEqualTo(HAR.path());
	}

}
