package se.marketplace.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.marketplace.categories.Category;

/**
 * The list as the model is shown it.
 *
 * <p>Worth a test because it is the one part of the prompt that is generated
 * rather than written, and because getting it wrong degrades quietly: a
 * malformed line does not throw, it just makes the model answer with something
 * the grounding step then refuses, and the customer experiences that as being
 * ignored rather than as a bug.
 */
class CategoryVocabularyTest {

	private static final CategoryVocabulary VOCABULARY = new CategoryVocabulary(List.of(
		new Category("har", "frisor", "Frisörer", List.of("klippning", "balayage"), 10),
		new Category("massage", "massage", "Massage", List.of("ryggmassage"), 20)));

	@Test
	@DisplayName("the slug leads, because the slug is what has to come back")
	void slugFirst() {
		// A line presenting "Frisörer" first would be inviting the model to
		// answer with the label, which UnderstoodQuestion.ground would then drop
		// correctly and for a reason nobody could see from the outside.
		assertThat(VOCABULARY.forPrompt().lines().toList())
			.containsExactly(
				"har — Frisörer (klippning, balayage)",
				"massage — Massage (ryggmassage)");
	}

	@Test
	@DisplayName("a category with no synonyms is still a category")
	void synonymsAreOptional() {
		var bare = new CategoryVocabulary(List.of(
			new Category("hud", "hudvard", "Hudvård", List.of(), 30)));

		// No trailing "()" — an empty bracket reads as a list the model failed
		// to be given rather than one that is simply empty.
		assertThat(bare.forPrompt()).isEqualTo("hud — Hudvård");
	}

	@Test
	@DisplayName("an empty catalogue says so rather than sending a blank")
	void emptyIsNamed() {
		// A fresh database, or every category retired. A blank here would leave
		// the prompt with a heading and nothing under it, which invites the
		// model to fill the gap from its own idea of what a salon sells.
		assertThat(new CategoryVocabulary(List.of()).forPrompt()).isEqualTo("(none yet)");
	}

	@Test
	@DisplayName("the closed set is the slugs and only the slugs")
	void checksAgainstSlugsNotLabels() {
		// Synonyms make the model right more often; they do not widen what is
		// accepted. "balayage" is a hint in the prompt, never an answer.
		assertThat(VOCABULARY.has("har")).isTrue();
		assertThat(VOCABULARY.has("HAR")).isTrue();
		assertThat(VOCABULARY.has("Frisörer")).isFalse();
		assertThat(VOCABULARY.has("balayage")).isFalse();
		assertThat(VOCABULARY.has("frisor")).isFalse();
	}

	@Test
	@DisplayName("canonical returns the database's spelling, not the caller's")
	void canonicalises() {
		assertThat(VOCABULARY.canonical("HAR")).isEqualTo("har");
		assertThat(VOCABULARY.canonical("Massage")).isEqualTo("massage");
	}

}
