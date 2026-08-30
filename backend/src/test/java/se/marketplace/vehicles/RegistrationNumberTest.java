package se.marketplace.vehicles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistrationNumberTest {

	@Test
	@DisplayName("spaces, hyphens and case are not part of the plate")
	void normalises() {
		assertThat(RegistrationNumber.parse("abc 123").map(RegistrationNumber::value)).contains("ABC123");
		assertThat(RegistrationNumber.parse("ABC-123").map(RegistrationNumber::value)).contains("ABC123");
		assertThat(RegistrationNumber.parse("  mlb 12a ").map(RegistrationNumber::value)).contains("MLB12A");
	}

	@Test
	@DisplayName("blank and nonsense are empty rather than an error")
	void refusesWhatIsNotAPlate() {
		assertThat(RegistrationNumber.parse(null)).isEmpty();
		assertThat(RegistrationNumber.parse("")).isEmpty();
		assertThat(RegistrationNumber.parse("   ")).isEmpty();
		assertThat(RegistrationNumber.parse("A")).isEmpty();
		assertThat(RegistrationNumber.parse("Volvo V70, den blå")).isEmpty();
	}

	@Test
	@DisplayName("a foreign plate is accepted and known not to be Swedish")
	void foreignPlatesAreKept() {
		var danish = RegistrationNumber.parse("AB 12 345").orElseThrow();

		assertThat(danish.value()).isEqualTo("AB12345");
		assertThat(danish.looksSwedish()).isFalse();
		assertThat(danish.display()).isEqualTo("AB12345");
	}

	@Test
	@DisplayName("a Swedish plate is shown the way it is written")
	void swedishDisplay() {
		assertThat(RegistrationNumber.parse("abc123").orElseThrow().looksSwedish()).isTrue();
		assertThat(RegistrationNumber.parse("abc123").orElseThrow().display()).isEqualTo("ABC 123");
		assertThat(RegistrationNumber.parse("ABC12A").orElseThrow().display()).isEqualTo("ABC 12A");
	}

}
