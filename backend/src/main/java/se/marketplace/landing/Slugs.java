package se.marketplace.landing;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a Swedish place name into something that survives a URL.
 *
 * <p>"Göteborg" is the city; {@code /frisor/goteborg} is the URL people type and
 * link to. Folding has to happen on <em>both</em> sides or the page 404s for
 * every city with a diacritic in it — which in Sweden is most of the ones worth
 * having a page for.
 *
 * <p>å and ä fold to a, ö to o. That is the convention Swedish URLs use, and it
 * matters that it is a deliberate choice rather than whatever
 * {@link Normalizer} happens to do: stripping combining marks turns ö into o
 * correctly but would also turn ß into ss inconsistently across platforms.
 */
final class Slugs {

	private Slugs() {
	}

	static String city(String name) {
		if (name == null) {
			return "";
		}
		String folded = name.toLowerCase(Locale.ROOT)
			.replace('å', 'a').replace('ä', 'a').replace('ö', 'o')
			.replace('é', 'e').replace('è', 'e').replace('ü', 'u');

		return Normalizer.normalize(folded, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}

}
