package se.marketplace.signup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The slug is the salon's URL and its Cal username, so it is the one field a
 * registration produces that is very hard to change afterwards.
 */
class SignupSlugsTest {

	@Test
	@DisplayName("Swedish characters fold the way Swedish URLs do")
	void foldsSwedish() {
		assertThat(SignupSlugs.of("Salong Söder")).isEqualTo("salong-soder");
		assertThat(SignupSlugs.of("Håret & Färgen")).isEqualTo("haret-och-fargen");
		assertThat(SignupSlugs.of("Ängens Frisör")).isEqualTo("angens-frisor");
	}

	@Test
	@DisplayName("an ampersand becomes a word, not a gap")
	void ampersand() {
		// "klipp-co" would be a different business name. Salons are called
		// "X & Y" often enough that dropping the conjunction is worse than
		// spelling it.
		assertThat(SignupSlugs.of("Klipp & Co")).isEqualTo("klipp-och-co");
	}

	@Test
	@DisplayName("punctuation and spacing collapse without leaving edges")
	void collapses() {
		assertThat(SignupSlugs.of("  Hår!!  Studio  ")).isEqualTo("har-studio");
		assertThat(SignupSlugs.of("--Salong--")).isEqualTo("salong");
	}

	@Test
	@DisplayName("a long name is cut at a word boundary")
	void truncates() {
		String slug = SignupSlugs.of(
			"Den absolut underbara salongen för hår och skägg i centrala Stockholm");

		assertThat(slug.length()).isLessThanOrEqualTo(48);
		assertThat(slug).doesNotEndWith("-");
		// Cut between words, not mid-word: the result is read by people.
		assertThat(slug).isEqualTo("den-absolut-underbara-salongen-for-har-och");
	}

	@Test
	@DisplayName("names that fold to nothing usable are refused, not defaulted")
	void unusable() {
		assertThat(SignupSlugs.usable(SignupSlugs.of("!!!"))).isFalse();
		assertThat(SignupSlugs.usable(SignupSlugs.of("Ab"))).isFalse();
	}

	@Test
	@DisplayName("reserved words are refused on both sides")
	void reserved() {
		// Ours: /salong/... is not the only place a slug appears.
		assertThat(SignupSlugs.usable("konsol")).isFalse();
		assertThat(SignupSlugs.usable("registrera")).isFalse();

		// Cal's: it serves its own pages under /username, and a salon that took
		// one would have a booking page that resolves to Cal's settings screen.
		assertThat(SignupSlugs.usable("settings")).isFalse();
		assertThat(SignupSlugs.usable("event-types")).isFalse();

		assertThat(SignupSlugs.usable("salong-soder")).isTrue();
	}

	@Test
	@DisplayName("a taken name is suffixed rather than refused")
	void candidates() {
		// Two salons genuinely called "Klipp & Co" is normal in a country with
		// more than one town.
		assertThat(SignupSlugs.candidate("klipp-och-co", 0)).isEqualTo("klipp-och-co");
		assertThat(SignupSlugs.candidate("klipp-och-co", 1)).isEqualTo("klipp-och-co-2");
	}

}
