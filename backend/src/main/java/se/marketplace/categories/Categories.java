package se.marketplace.categories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The list, and the two questions worth asking it.
 *
 * <p>Read straight from the table on every call rather than cached. Three rows
 * on an indexed primary key is not a query worth optimising, and it is not on
 * the plain search path at all — only free-text search and the landing pages
 * ask. A cache here would buy nothing measurable and would introduce the one
 * bug this module exists to prevent, which is two lists that disagree.
 *
 * <p>See ADR 0013.
 */
@Component
public class Categories {

	private static final String ACTIVE = """
		SELECT slug, path, label, synonyms, sort_order
		  FROM service_category
		 WHERE active
		 ORDER BY sort_order, slug
		""";

	private final NamedParameterJdbcTemplate jdbc;

	Categories(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Category> all() {
		return jdbc.query(ACTIVE, new MapSqlParameterSource(), MAPPER);
	}

	/** By what the database stores. */
	public Optional<Category> bySlug(String slug) {
		return all().stream().filter(category -> category.slug().equalsIgnoreCase(slug)).findFirst();
	}

	/** By what a person typed into their address bar. */
	public Optional<Category> byPath(String path) {
		return all().stream().filter(category -> category.path().equalsIgnoreCase(path)).findFirst();
	}

	/**
	 * What a service called {@code title} is, if anything says so.
	 *
	 * <p>Deterministic and modelless, on purpose. This runs while a salon is
	 * being provisioned, and ADR 0011 already establishes that provisioning does
	 * not wait on third parties — a salon's categories must not depend on our
	 * having paid for a model or on that model being reachable. It is also
	 * simply enough: salons name event types "Klippning dam 45 min", which is a
	 * substring, not a comprehension problem.
	 *
	 * <p>Empty rather than a guess. The caller has a default and is expected to
	 * use it; a category invented here would be indistinguishable from one a
	 * person chose. Same rule as everywhere else in this system.
	 */
	public Optional<Category> classify(String title) {
		if (title == null || title.isBlank()) {
			return Optional.empty();
		}

		// Strongest match wins, so "taktil massage" is massage rather than
		// whichever category also happens to list "massage". Ties break on sort
		// order, which is at least stable and visible in the table, rather than
		// on whatever the database returned first.
		return all().stream()
			.map(category -> java.util.Map.entry(category, category.matchStrength(title)))
			.filter(entry -> entry.getValue() > 0)
			.max(Comparator
				.<java.util.Map.Entry<Category, Integer>>comparingInt(java.util.Map.Entry::getValue)
				.thenComparing(entry -> -entry.getKey().sortOrder()))
			.map(java.util.Map.Entry::getKey);
	}

	/**
	 * Lower case, and Swedish diacritics reduced to their bare letters.
	 *
	 * <p>Done here rather than by a database collation because it is a decision
	 * about matching rather than about storage, and because the same folding has
	 * to apply to an event type title arriving from Cal, which the database
	 * never sees.
	 *
	 * <p>Folding å and ä to a is wrong as Swedish — they are distinct letters,
	 * not decorated a's, and they sort after z. It is right as matching: a salon
	 * typing "Fargning" on a keyboard laid out for another language means
	 * "Färgning", and the cost of treating them as the same here is a collision
	 * between two treatment names that differ only by a diacritic, which is not
	 * a pair that exists.
	 */
	public static String fold(String value) {
		return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "");
	}

	private static final RowMapper<Category> MAPPER = (ResultSet rs, int rowNum) -> new Category(
		rs.getString("slug"),
		rs.getString("path"),
		rs.getString("label"),
		synonyms(rs),
		rs.getInt("sort_order"));

	/**
	 * Folded on the way out, so the table stays readable by a person.
	 *
	 * <p>It holds "färgning" and callers compare against "fargning"; doing the
	 * folding here means the column can be written the way a Swede would write
	 * it rather than the way a matcher wants it.
	 */
	private static List<String> synonyms(ResultSet rs) throws SQLException {
		var array = rs.getArray("synonyms");

		if (array == null) {
			return List.of();
		}

		return Arrays.stream((String[]) array.getArray())
			.filter(value -> value != null && !value.isBlank())
			.map(Categories::fold)
			.distinct()
			.toList();
	}

}
