package se.marketplace.landing;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where the built SPA's files ended up.
 *
 * <p>Vite hashes filenames for cache-busting, so a template cannot name them.
 * Reading the manifest keeps the server-rendered pages and the bundle in step
 * automatically; hardcoding them would mean a stale script tag after every
 * frontend build, and a landing page whose "book" button silently does nothing.
 *
 * <p>Missing manifest is tolerated rather than fatal. The backend is routinely
 * run without building the frontend ({@code -Dskip.npm=true} is the default),
 * and the landing pages are still worth serving without their scripts — the
 * content is the part that matters here.
 */
@Component
class ViteManifest {

	private static final Logger log = LoggerFactory.getLogger(ViteManifest.class);

	private final String script;
	private final List<String> stylesheets;

	ViteManifest() {
		String resolvedScript = null;
		List<String> resolvedStyles = List.of();

		try (InputStream in = new ClassPathResource("static/.vite/manifest.json").getInputStream()) {
			JsonNode entry = new ObjectMapper().readTree(in).path("index.html");
			resolvedScript = entry.path("file").asText(null);

			JsonNode css = entry.path("css");
			if (css.isArray()) {
				resolvedStyles = java.util.stream.StreamSupport
					.stream(css.spliterator(), false)
					.map(JsonNode::asText)
					.toList();
			}
		}
		catch (Exception e) {
			log.warn("no vite manifest — landing pages will render without the SPA bundle "
				+ "(build the frontend with `npx vite build`)");
		}

		this.script = resolvedScript;
		this.stylesheets = resolvedStyles;
	}

	Map<String, Object> assets() {
		return Map.of(
			"script", script == null ? "" : "/" + script,
			"stylesheets", stylesheets.stream().map(sheet -> "/" + sheet).toList(),
			"hasBundle", script != null);
	}

}
