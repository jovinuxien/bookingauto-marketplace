package se.marketplace.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.marketplace.vehicles.Vehicle;

/**
 * The matcher: most specific wins, ties to the cheaper, no match is the
 * list price. Run on the page and again in the funnel, so it had better be
 * a function.
 */
class PriceRulesTest {

	private static final Vehicle V70_2016 = new Vehicle("VOLVO", "V70 D4", 2016, "215/55R16", "215/55R16");
	private static final Vehicle XC90_2020 = new Vehicle("VOLVO", "XC90 T8", 2020, "275/40R21", "275/40R21");
	private static final Vehicle FIAT = new Vehicle("FIAT", "500", 2012, "175/65R14", "175/65R14");

	private static PriceRule rule(String make, String model, Integer yf, Integer yt, Integer rf, Integer rt, int price, String label) {
		return new PriceRule(0, 1, make, model, yf, yt, rf, rt, price, label);
	}

	@Test
	@DisplayName("no rules is the list price, and says so")
	void noRules() {
		Quote quote = PriceRules.quote(List.of(), 60000, V70_2016);
		assertThat(quote).isEqualTo(new Quote(60000, null, false));
	}

	@Test
	@DisplayName("no matching rule is the list price")
	void noMatch() {
		var rules = List.of(rule("BMW", null, null, null, null, null, 30000, "BMW"));
		assertThat(PriceRules.quote(rules, 60000, V70_2016).forVehicle()).isFalse();
	}

	@Test
	@DisplayName("the most specific matching rule wins")
	void mostSpecificWins() {
		var rules = List.of(
			rule("VOLVO", null, null, null, null, null, 25000, "Volvo"),
			rule("VOLVO", "V70", 2015, 2019, null, null, 24900, "Volvo V70 2015–2019"),
			rule(null, null, null, null, 16, 17, 19900, "16–17 tum"));

		assertThat(PriceRules.quote(rules, 60000, V70_2016))
			.isEqualTo(new Quote(24900, "Volvo V70 2015–2019", true));
		// The XC90 misses the V70 rule and the 16–17 rim rule; plain Volvo applies.
		assertThat(PriceRules.quote(rules, 60000, XC90_2020).label()).isEqualTo("Volvo");
		// The Fiat matches nothing.
		assertThat(PriceRules.quote(rules, 60000, FIAT).forVehicle()).isFalse();
	}

	@Test
	@DisplayName("equal specificity goes to the cheaper, which is the tie a customer would break")
	void tieGoesToCheaper() {
		var rules = List.of(
			rule("VOLVO", null, null, null, null, null, 30000, "dyr"),
			rule(null, null, null, null, 16, 16, 20000, "billig"));
		assertThat(PriceRules.quote(rules, 60000, V70_2016).label()).isEqualTo("billig");
	}

	@Test
	@DisplayName("a year range needs a year; a rim range needs a parseable tyre")
	void missingDataDoesNotMatch() {
		Vehicle unknownYear = new Vehicle("VOLVO", "V70", null, null, null);
		var rules = List.of(
			rule(null, null, 2010, 2020, null, null, 1, "år"),
			rule(null, null, null, null, 14, 20, 2, "tum"));
		assertThat(PriceRules.quote(rules, 60000, unknownYear).forVehicle()).isFalse();
	}

	@Test
	@DisplayName("make and model are matched case-insensitively, model as a prefix")
	void folding() {
		var rules = List.of(rule("volvo", "v70", null, null, null, null, 1, "x"));
		assertThat(PriceRules.quote(rules, 60000, V70_2016).forVehicle()).isTrue();
	}

	@Test
	@DisplayName("a rule with no constraint is refused: it would be the list price")
	void validation() {
		assertThatThrownBy(() -> new PriceRules.NewRule(null, "", null, null, null, null, 100, null).validated())
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("villkor");
		assertThatThrownBy(() -> new PriceRules.NewRule("Volvo", null, null, null, null, null, 0, null).validated())
			.hasMessageContaining("noll");
		assertThatThrownBy(() -> new PriceRules.NewRule(null, null, 2020, 2010, null, null, 100, null).validated())
			.hasMessageContaining("Årsmodell");
	}

	@Test
	@DisplayName("a rule without a label gets one a customer can read")
	void defaultLabel() {
		assertThat(new PriceRules.NewRule("volvo", "v70", 2015, 2019, null, null, 100, " ").validated().label())
			.isEqualTo("Volvo V70 2015–2019");
		assertThat(new PriceRules.NewRule(null, null, null, null, 16, 17, 100, null).validated().label())
			.isEqualTo("16–17 tum");
		assertThat(new PriceRules.NewRule(null, null, null, null, 18, 18, 100, null).validated().label())
			.isEqualTo("18 tum");
		// Make is stored folded, for the matcher.
		assertThat(new PriceRules.NewRule("volvo", null, null, null, null, null, 100, null).validated().make())
			.isEqualTo("VOLVO");
	}

}
