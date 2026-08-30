package se.marketplace.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RimInchesTest {

	@Test
	@DisplayName("the number after the R, however the registry spells it")
	void parses() {
		assertThat(RimInches.of("215/55R16")).contains(16);
		assertThat(RimInches.of("215/55 R 16")).contains(16);
		assertThat(RimInches.of("225/45ZR18 91W")).contains(18);
		assertThat(RimInches.of("205/55-16")).contains(16);
		assertThat(RimInches.of("275/40R21")).contains(21);
	}

	@Test
	@DisplayName("nothing plausible is nothing, not a guess")
	void refuses() {
		assertThat(RimInches.of(null)).isEmpty();
		assertThat(RimInches.of("")).isEmpty();
		assertThat(RimInches.of("Volvo V70")).isEmpty();
		assertThat(RimInches.of("R99")).isEmpty();
	}

}
