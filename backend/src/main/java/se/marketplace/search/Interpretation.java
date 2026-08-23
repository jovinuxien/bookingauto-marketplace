package se.marketplace.search;

/**
 * What the model claims the sentence meant.
 *
 * <p>Every field is a {@code String}, including the date and the enum, and that
 * is deliberate. This type is the boundary between a probabilistic answer and a
 * query we are willing to run, and a boundary that parses its own input cannot
 * report what it refused. Typing {@code day} as a {@code LocalDate} here would
 * move the failure into a deserialiser, where it becomes an exception with no
 * opinion about what to do next — rather than into
 * {@link UnderstoodQuestion#ground}, which drops the field, keeps the rest of
 * the query, and tells the caller.
 *
 * <p>Nothing reads this record except the grounding step. It never reaches an
 * HTTP response and never reaches SQL.
 */
public record Interpretation(

	/** A slug from the vocabulary, or blank when the text named no treatment. */
	String categorySlug,

	/** ISO-8601, or blank when the text named no day. */
	String day,

	/** {@code MORNING}, {@code AFTERNOON}, {@code EVENING}, or blank. */
	String partOfDay,

	/**
	 * One short line, in the customer's own language, saying how the sentence
	 * was read. Shown to them — see ADR 0012 on why a filter nobody can see is a
	 * filter nobody can correct.
	 */
	String summary
) {}
