package se.marketplace.search;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider pages.
 *
 * <p>Only active providers are served, and only active services. An inactive
 * provider is one we cannot pay or that has nothing to sell; giving it a public
 * page invites bookings that the funnel will refuse and the customer will blame
 * us for.
 */
@RestController
@RequestMapping("/api/providers")
class CatalogueController {

	private final NamedParameterJdbcTemplate jdbc;

	CatalogueController(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/{slug}")
	ResponseEntity<ProviderDetail> bySlug(@PathVariable String slug) {
		List<ProviderDetail> found = jdbc.query("""
			SELECT id, slug, name, city, address_line, description
			  FROM provider
			 WHERE slug = :slug AND status = 'active'
			""",
			new MapSqlParameterSource("slug", slug),
			(ResultSet rs, int n) -> new ProviderDetail(
				rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
				rs.getString("city"), rs.getString("address_line"),
				rs.getString("description"), new ArrayList<>()));

		if (found.isEmpty()) {
			return ResponseEntity.notFound().build();
		}

		ProviderDetail provider = found.get(0);

		List<ProviderDetail.Service> services = jdbc.query("""
			SELECT id, name, category_slug, duration_minutes, price_minor, currency
			  FROM service
			 WHERE provider_id = :id AND active
			 ORDER BY name
			""",
			new MapSqlParameterSource("id", provider.id()),
			(ResultSet rs, int n) -> new ProviderDetail.Service(
				rs.getLong("id"), rs.getString("name"), rs.getString("category_slug"),
				rs.getInt("duration_minutes"), rs.getInt("price_minor"), rs.getString("currency")));

		return ResponseEntity.ok(new ProviderDetail(
			provider.id(), provider.slug(), provider.name(), provider.city(),
			provider.addressLine(), provider.description(), services));
	}

}
