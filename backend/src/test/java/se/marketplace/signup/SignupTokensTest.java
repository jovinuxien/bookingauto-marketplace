package se.marketplace.signup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The verification token is a bearer credential — whoever holds it can prove
 * ownership of an address they may not own — so it is tested like one.
 */
class SignupTokensTest {

	@Test
	@DisplayName("tokens do not repeat")
	void unique() {
		Set<String> seen = new HashSet<>();

		for (int i = 0; i < 1000; i++) {
			seen.add(SignupTokens.issue());
		}

		assertThat(seen).hasSize(1000);
	}

	@Test
	@DisplayName("a token survives being a URL")
	void urlSafe() {
		// It travels in a query string through a mail client that will happily
		// mangle anything needing escaping.
		for (int i = 0; i < 100; i++) {
			assertThat(SignupTokens.issue()).matches("^[A-Za-z0-9_-]{43}$");
		}
	}

	@Test
	@DisplayName("the hash is stable, and is not the token")
	void hashing() {
		String token = SignupTokens.issue();

		assertThat(SignupTokens.hash(token)).isEqualTo(SignupTokens.hash(token));
		assertThat(SignupTokens.hash(token)).doesNotContain(token);
		assertThat(SignupTokens.hash(token)).matches("^[0-9a-f]{64}$");
		assertThat(SignupTokens.hash(token)).isNotEqualTo(SignupTokens.hash(SignupTokens.issue()));
	}

	@Test
	@DisplayName("the Cal password always satisfies Cal's own rule")
	void calPassword() {
		// Composed rather than drawn uniformly and hoped for: a one-in-a-few-
		// hundred signup failing on a rejected password would be untraceable
		// from our side and would look like Cal being down.
		for (int i = 0; i < 500; i++) {
			String password = SignupTokens.calPassword();

			assertThat(password).hasSize(20);
			assertThat(password).matches(".*[A-Z].*");
			assertThat(password).matches(".*[a-z].*");
			assertThat(password).matches(".*[0-9].*");
			// Read off a screen and typed into another site by a person.
			assertThat(password).doesNotContain("l").doesNotContain("I")
				.doesNotContain("O").doesNotContain("0").doesNotContain("1");
		}
	}

}
