package se.marketplace.landing;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import se.marketplace.categories.Categories;
import se.marketplace.categories.Category;

/**
 * Tells a crawler which pages exist.
 *
 * <p>Generated from the catalogue rather than written by hand, because a
 * sitemap that has to be maintained is a sitemap that goes wrong: it will list
 * salons that were suspended and miss the ones onboarded last week.
 *
 * <p>Only the server-rendered pages are listed. Submitting SPA routes would ask
 * a crawler to index documents that have no content in them, which is worse
 * than not listing them at all.
 */
@RestController
class SitemapController {

	private final LandingRepository repository;
	private final Categories categories;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	SitemapController(LandingRepository repository, Categories categories) {
		this.repository = repository;
		this.categories = categories;
	}

	@GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
	ResponseEntity<String> sitemap() {
		StringBuilder xml = new StringBuilder()
			.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
			.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

		url(xml, publicUrl + "/orter", "0.8");

		// One page per category per city that actually has salons. Emitting
		// combinations with nothing behind them would fill the index with thin
		// pages, which costs more than the extra URLs are worth.
		List<Category> active = categories.all();

		for (LandingRepository.CityRow city : repository.cities()) {
			for (Category category : active) {
				if (!repository.providersIn(city.slug(), category.slug()).isEmpty()) {
					url(xml, publicUrl + "/" + category.path() + "/" + city.slug(), "0.9");
				}
			}
		}

		for (String slug : repository.sitemapSlugs()) {
			url(xml, publicUrl + "/salong/" + slug, "0.7");
		}

		xml.append("</urlset>\n");
		return ResponseEntity.ok(xml.toString());
	}

	@GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
	String robots() {
		// The console and the funnel are explicitly disallowed. They are behind
		// authentication anyway, but a crawler wasting its budget on them is
		// budget not spent on the pages that need it.
		return """
			User-agent: *
			Disallow: /konsol
			Disallow: /logga-in
			Disallow: /boka/
			Disallow: /api/
			Allow: /

			Sitemap: %s/sitemap.xml
			""".formatted(publicUrl);
	}

	private static void url(StringBuilder xml, String location, String priority) {
		xml.append("  <url><loc>").append(escape(location)).append("</loc>")
			.append("<priority>").append(priority).append("</priority></url>\n");
	}

	private static String escape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
