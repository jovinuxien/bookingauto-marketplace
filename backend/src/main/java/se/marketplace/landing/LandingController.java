package se.marketplace.landing;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import se.marketplace.categories.Categories;
import se.marketplace.categories.Category;

/**
 * The crawlable entry points.
 *
 * <p>Three pages, chosen because they are the three things someone searches for
 * before they know this site exists: a city, a treatment in a city, and a salon
 * by name.
 *
 * <p>Each is a complete HTML document with its own title, description, canonical
 * URL and structured data, and each boots the SPA afterwards so the journey
 * continues without a reload. The server-rendered markup is what gets indexed;
 * React replaces it once it mounts.
 */
@Controller
class LandingController {

	/**
	 * The category paths that have routes, as one compile-time constant.
	 *
	 * <p>Used in the mapping below — where it has to be a literal — and checked
	 * against the table at startup by {@link CategoryRoutes}. The list of
	 * categories moved into a table (ADR 0013); the list of *URLs* deliberately
	 * did not, because the alternative is the unbounded {@code /{a}/{b}} the
	 * comment on the mapping already rejects.
	 *
	 * <p>So adding a category stays a two-part deliberate act: a row in a
	 * migration and a word here. Getting half of it done is now noticed at boot
	 * rather than by a 404 nobody is watching.
	 */
	private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

	static final String CATEGORY_PATHS = "frisor|massage|hudvard|dackbyte|bilservice|bilvard|bilglas";

	private final LandingRepository repository;
	private final ViteManifest manifest;
	private final Categories categories;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	LandingController(LandingRepository repository, ViteManifest manifest, Categories categories) {
		this.repository = repository;
		this.manifest = manifest;
		this.categories = categories;
	}

	/**
	 * Cities we cover. The root of the crawlable tree.
	 *
	 * <p>No SPA: the router has no route for this path, and mounting React here
	 * replaces the rendered list with the not-found screen. Links out of it are
	 * ordinary navigations, which for an entry point is what they should be.
	 */
	@GetMapping("/orter")
	String cities(Model model) {
		model.addAllAttributes(manifest.assets(false));
		model.addAttribute("cities", repository.cities());
		model.addAttribute("canonical", publicUrl + "/orter");
		model.addAttribute("title", "Boka tid — orter");
		model.addAttribute("description",
			"Hitta salonger och kliniker med lediga tider, ort för ort.");
		return "cities";
	}

	/**
	 * The page that matters most: a treatment in a city.
	 *
	 * <p>{@code /frisor/stockholm} is the shape of the query people actually
	 * type, and having a URL that matches it is most of what makes a marketplace
	 * findable.
	 */
	// The category is constrained in the pattern itself. An unbounded
	// /{a}/{b} would sit in front of every other two-segment path in the
	// application and quietly shadow one the day it is added.
	@GetMapping("/{category:" + CATEGORY_PATHS + "}/{city}")
	String cityCategory(@PathVariable String category, @PathVariable String city, Model model) {
		// Only known categories are pages. Without this every stray two-segment
		// path becomes a thin, empty page, and a site full of those ranks worse
		// than one without them.
		Category resolved = categories.byPath(category)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

		List<LandingRepository.ProviderRow> providers =
			repository.providersIn(city, resolved.slug());

		if (providers.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}

		String cityName = providers.get(0).city();

		// Also no SPA, for the same reason as /orter.
		model.addAllAttributes(manifest.assets(false));
		model.addAttribute("providers", providers);
		model.addAttribute("cityName", cityName);
		model.addAttribute("category", resolved);
		// What kind of place is listed. A workshop is not a HealthAndBeautyBusiness
		// however it is filed, and the structured data is the part a search
		// engine believes.
		model.addAttribute("schemaType", resolved.vehicle() ? "AutoRepair" : "HealthAndBeautyBusiness");
		String noun = resolved.vehicle() ? "verkstäder" : "salonger";
		// The calendar, on the one page where it is the reason people came.
		if ("dack".equals(resolved.slug())) {
			model.addAttribute("season", TyreSeason.on(LocalDate.now(STOCKHOLM)));
		}
		// path(), not slug(). The slug is the category's value in the database
		// ("har"); the path is its URL ("frisor"). Using the wrong one pointed
		// every canonical at a URL that 404s, which tells a search engine the
		// real page is the one that does not exist.
		model.addAttribute("canonical",
			publicUrl + "/" + resolved.path() + "/" + city.toLowerCase());
		model.addAttribute("title", resolved.label() + " i " + cityName + " — boka tid online");
		model.addAttribute("description",
			"Jämför " + resolved.label().toLowerCase() + " i " + cityName
				+ " och boka en ledig tid direkt. " + providers.size() + " " + noun + ".");
		return "city-category";
	}

	/** A salon by name. */
	@GetMapping("/salong/{slug}")
	String provider(@PathVariable String slug, Model model) {
		LandingRepository.ProviderRow provider = repository.provider(slug)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

		// This one DOES mount the SPA: /salong/{slug} is a real route, and the
		// interactive slot picker is the point of the page. React replacing the
		// server-rendered markup here is intended -- it renders the same facts
		// plus live times.
		model.addAllAttributes(manifest.assets(true));
		model.addAttribute("provider", provider);
		model.addAttribute("services", repository.servicesOf(provider.id()));
		model.addAttribute("canonical", publicUrl + "/salong/" + provider.slug());
		model.addAttribute("title", provider.name() + " — boka tid i " + provider.city());
		model.addAttribute("description",
			provider.description() != null && !provider.description().isBlank()
				? provider.description()
				: "Boka tid hos " + provider.name() + " i " + provider.city()
					+ ". Lediga tider direkt från salongens kalender.");
		return "provider";
	}

}
