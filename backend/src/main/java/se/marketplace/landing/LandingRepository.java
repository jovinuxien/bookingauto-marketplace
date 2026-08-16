package se.marketplace.landing;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads for the crawlable pages.
 *
 * <p>Reads the catalogue directly and never the availability index. A landing
 * page indexed today is read by someone next week, so putting today's free slots
 * in it would publish a promise that is stale before it is crawled — and worse,
 * make the page's content churn constantly, which search engines read as
 * instability rather than freshness.
 *
 * <p>What goes on these pages is what stays true: who exists, where they are,
 * what they sell and for how much.
 */
@Repository
class LandingRepository {

	private final NamedParameterJdbcTemplate jdbc;

	LandingRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Cities with something to sell. Drives both the sitemap and the index page. */
	List<CityRow> cities() {
		return jdbc.query("""
			SELECT p.city, count(DISTINCT p.id) AS providers
			  FROM provider p
			  JOIN service s ON s.provider_id = p.id AND s.active
			 WHERE p.status = 'active' AND p.city IS NOT NULL
			 GROUP BY p.city
			 ORDER BY providers DESC, p.city
			""",
			new MapSqlParameterSource(),
			(ResultSet rs, int n) -> new CityRow(
				rs.getString("city"), Slugs.city(rs.getString("city")), rs.getInt("providers")));
	}

	/**
	 * Salons in a city, optionally narrowed to a category.
	 *
	 * <p>Matched on the folded city name, so {@code /goteborg} finds Göteborg and
	 * {@code /Stockholm} and {@code /stockholm} are one page rather than two
	 * competing for the same result. Duplicate URLs for identical content is a
	 * self-inflicted ranking problem.
	 *
	 * <p>The fold is done in SQL with {@code translate} rather than by requiring
	 * the {@code unaccent} extension, which is not installed everywhere this
	 * runs and would make the query fail rather than merely miss.
	 */
	List<ProviderRow> providersIn(String city, String category) {
		return jdbc.query("""
			SELECT p.id, p.slug, p.name, p.city, p.address_line, p.description,
			       min(s.price_minor) AS from_price_minor,
			       max(s.currency)    AS currency,
			       count(s.id)        AS service_count
			  FROM provider p
			  JOIN service s ON s.provider_id = p.id AND s.active
			 WHERE p.status = 'active'
			   AND translate(lower(p.city), 'åäöüéè', 'aaouee') = lower(:city)
			   AND (CAST(:category AS text) IS NULL OR s.category_slug = :category)
			 GROUP BY p.id, p.slug, p.name, p.city, p.address_line, p.description
			 ORDER BY p.name
			""",
			new MapSqlParameterSource().addValue("city", city).addValue("category", category),
			(ResultSet rs, int n) -> new ProviderRow(
				rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
				rs.getString("city"), Slugs.city(rs.getString("city")),
				rs.getString("address_line"), rs.getString("description"),
				rs.getInt("from_price_minor"), rs.getString("currency"), rs.getInt("service_count")));
	}

	Optional<ProviderRow> provider(String slug) {
		return jdbc.query("""
			SELECT p.id, p.slug, p.name, p.city, p.address_line, p.description,
			       COALESCE(min(s.price_minor), 0) AS from_price_minor,
			       COALESCE(max(s.currency), 'SEK') AS currency,
			       count(s.id) AS service_count
			  FROM provider p
			  LEFT JOIN service s ON s.provider_id = p.id AND s.active
			 WHERE p.slug = :slug AND p.status = 'active'
			 GROUP BY p.id, p.slug, p.name, p.city, p.address_line, p.description
			""",
			new MapSqlParameterSource("slug", slug),
			(ResultSet rs, int n) -> new ProviderRow(
				rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
				rs.getString("city"), Slugs.city(rs.getString("city")),
				rs.getString("address_line"), rs.getString("description"),
				rs.getInt("from_price_minor"), rs.getString("currency"), rs.getInt("service_count")))
			.stream().findFirst();
	}

	List<ServiceRow> servicesOf(long providerId) {
		return jdbc.query("""
			SELECT id, name, category_slug, duration_minutes, price_minor, currency
			  FROM service
			 WHERE provider_id = :id AND active
			 ORDER BY name
			""",
			new MapSqlParameterSource("id", providerId),
			(ResultSet rs, int n) -> new ServiceRow(
				rs.getLong("id"), rs.getString("name"), rs.getString("category_slug"),
				rs.getInt("duration_minutes"), rs.getInt("price_minor"), rs.getString("currency")));
	}

	/** Every URL worth crawling. */
	List<String> sitemapSlugs() {
		return jdbc.query(
			"SELECT slug FROM provider WHERE status = 'active' ORDER BY slug",
			new MapSqlParameterSource(),
			(ResultSet rs, int n) -> rs.getString("slug"));
	}

	record CityRow(String city, String slug, int providers) {}

	record ProviderRow(
		long id, String slug, String name, String city, String citySlug, String addressLine,
		String description, int fromPriceMinor, String currency, int serviceCount
	) {}

	record ServiceRow(
		long id, String name, String categorySlug, int durationMinutes, int priceMinor, String currency
	) {}

}
