package se.marketplace.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The normalisation, which is where a real salon was actually lost.
 *
 * <p>"Munins gata 6 lgh 1101" is a genuine address from this system's own
 * signups. Sent verbatim it returns nothing at all; with the apartment removed
 * it returns the right street in Malmö. That one case is the reason this class
 * exists, so it is the first test.
 */
class SwedishAddressTest {

	@Test
	@DisplayName("an apartment number is what stops a real address matching")
	void stripsApartment() {
		assertThat(SwedishAddress.street("Munins gata 6 lgh 1101")).isEqualTo("Munins gata 6");
		assertThat(SwedishAddress.street("Storgatan 1, lgh 1102")).isEqualTo("Storgatan 1");
		assertThat(SwedishAddress.street("Storgatan 1 lgh. 3")).isEqualTo("Storgatan 1");
		assertThat(SwedishAddress.street("Storgatan 1 lägenhet 3")).isEqualTo("Storgatan 1");
	}

	@Test
	@DisplayName("floors go too")
	void stripsFloor() {
		assertThat(SwedishAddress.street("Odengatan 5 vån 3")).isEqualTo("Odengatan 5");
		assertThat(SwedishAddress.street("Odengatan 5, våning 2")).isEqualTo("Odengatan 5");
	}

	@Test
	@DisplayName("both, because forms are filled in by people")
	void stripsApartmentAndFloor() {
		assertThat(SwedishAddress.street("Bondegatan 12, lgh 1101, vån 3"))
			.isEqualTo("Bondegatan 12");
	}

	@Test
	@DisplayName("care-of is a person, not a place")
	void stripsCareOf() {
		assertThat(SwedishAddress.street("c/o Anna Andersson, Bondegatan 12"))
			.isEqualTo("Bondegatan 12");
	}

	@Test
	@DisplayName("a street that merely contains a stripped word survives")
	void doesNotOverReach() {
		// Anchored to the end for exactly this: the words appear inside real
		// street names, and a greedy strip would quietly mangle them.
		assertThat(SwedishAddress.street("Våningsgatan 4")).isEqualTo("Våningsgatan 4");
		assertThat(SwedishAddress.street("Bondegatan 12")).isEqualTo("Bondegatan 12");
	}

	@Test
	@DisplayName("nothing usable is null, not an empty string")
	void nullWhenNothingLeft() {
		// An empty string sent as a street makes Nominatim answer with the city,
		// which is the confident wrong answer this module exists to refuse. The
		// type has to make that unrepresentable.
		assertThat(SwedishAddress.street("")).isNull();
		assertThat(SwedishAddress.street("   ")).isNull();
		assertThat(SwedishAddress.street(null)).isNull();
		assertThat(SwedishAddress.street("lgh 1101")).isNull();
	}

	@Test
	@DisplayName("a city on its own is never worth asking about")
	void refusesCityOnly() {
		assertThat(SwedishAddress.worthAsking(null, "Stockholm")).isFalse();
		assertThat(SwedishAddress.worthAsking("", "Stockholm")).isFalse();
		assertThat(SwedishAddress.worthAsking("Bondegatan 12", null)).isFalse();
		assertThat(SwedishAddress.worthAsking("Bondegatan 12", "Stockholm")).isTrue();
	}

}
