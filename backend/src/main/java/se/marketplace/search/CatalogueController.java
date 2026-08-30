package se.marketplace.search;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import se.marketplace.pricing.Addons;
import se.marketplace.pricing.PriceRules;
import se.marketplace.pricing.Quote;
import se.marketplace.vehicles.RegistrationNumber;
import se.marketplace.vehicles.Vehicle;
import se.marketplace.vehicles.VehicleRegistryPort.RegistryUnavailable;
import se.marketplace.vehicles.Vehicles;

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
	private final PriceRules priceRules;
	private final Vehicles vehicles;
	private final Addons addons;

	CatalogueController(NamedParameterJdbcTemplate jdbc, PriceRules priceRules, Vehicles vehicles,
		Addons addons) {
		this.jdbc = jdbc;
		this.priceRules = priceRules;
		this.vehicles = vehicles;
		this.addons = addons;
	}

	@GetMapping("/{slug}")
	ResponseEntity<ProviderDetail> bySlug(@PathVariable String slug,
		@RequestParam(required = false) String regnr) {
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

		// The car, when the page carries a plate (ADR 0016). Through the cache;
		// a registry that cannot be asked means list prices, not an error.
		Optional<Vehicle> vehicle = Optional.empty();
		Optional<RegistrationNumber> plate = RegistrationNumber.parse(regnr);
		if (plate.isPresent()) {
			try {
				vehicle = vehicles.lookup(plate.get());
			}
			catch (RegistryUnavailable e) {
				vehicle = Optional.empty();
			}
		}
		final Optional<Vehicle> car = vehicle;

		List<ProviderDetail.Service> services = jdbc.query("""
			SELECT s.id, s.name, s.category_slug, s.duration_minutes, s.price_minor, s.currency,
			       COALESCE(c.asks_vehicle, false) AS asks_vehicle
			  FROM service s
			  LEFT JOIN service_category c ON c.slug = s.category_slug
			 WHERE s.provider_id = :id AND s.active
			 ORDER BY s.name
			""",
			new MapSqlParameterSource("id", provider.id()),
			(ResultSet rs, int n) -> {
				int listPrice = rs.getInt("price_minor");
				boolean asks = rs.getBoolean("asks_vehicle");
				Quote quote = asks
					? priceRules.quote(rs.getLong("id"), listPrice, car)
					: new Quote(listPrice, null, false);
				return new ProviderDetail.Service(
					rs.getLong("id"), rs.getString("name"), rs.getString("category_slug"),
					rs.getInt("duration_minutes"), quote.priceMinor(), rs.getString("currency"),
					asks, listPrice, quote.label(), quote.forVehicle(),
					addons.forService(rs.getLong("id")));
			});

		return ResponseEntity.ok(new ProviderDetail(
			provider.id(), provider.slug(), provider.name(), provider.city(),
			provider.addressLine(), provider.description(), services));
	}

}
