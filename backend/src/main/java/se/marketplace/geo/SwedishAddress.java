package se.marketplace.geo;

import java.util.regex.Pattern;

/**
 * Trimming a Swedish address down to the part a geocoder can match.
 *
 * <p>Separate from the geocoder, and pure, because this is the half that is
 * worth testing and the half most likely to be wrong. It is also the half that
 * decides whether a real salon is findable: the address that prompted this class
 * is {@code "Munins gata 6 lgh 1101"}, which returns nothing at all from
 * Nominatim and returns the right street the moment the apartment is removed.
 *
 * <p><strong>Removing is allowed; inventing is not.</strong> Everything here
 * drops detail the map data does not model — which flat, which floor, who the
 * post is addressed to. Nothing rewrites a street name, corrects a spelling or
 * substitutes a nearby address, because each of those turns a failed lookup into
 * a confident wrong answer, and a wrong coordinate is the one outcome this
 * module exists to avoid.
 */
final class SwedishAddress {

	/**
	 * Apartment and floor, which Swedish addresses carry inline and map data
	 * does not have. {@code lgh} is the common one; {@code vån} appears on older
	 * registrations. Anchored to the end so a street legitimately containing one
	 * of these words is untouched.
	 */
	private static final Pattern APARTMENT = Pattern.compile(
		"(?iu)[\\s,;]*\\b(lgh|lgh\\.|lägenhet|vån|vån\\.|våning)\\b[\\s.]*\\d+\\s*$");

	/**
	 * "care of" — a person's name, never a place. Dropped wherever it appears
	 * rather than only at the end, because it is written both before and after
	 * the street depending on who filled the form in.
	 */
	private static final Pattern CARE_OF = Pattern.compile(
		"(?iu)\\bc\\s*/\\s*o\\b[^,]*(,|$)");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private SwedishAddress() {
	}

	/**
	 * @return the street line with what a geocoder cannot use removed, or null
	 *         if nothing usable is left. Null rather than an empty string so a
	 *         caller cannot accidentally send a blank line and get the city back.
	 */
	static String street(String line) {
		if (line == null || line.isBlank()) {
			return null;
		}

		String trimmed = CARE_OF.matcher(line).replaceAll("");

		// Twice, for "Storgatan 1, lgh 1101, vån 3". Not a loop: two is the most
		// that occurs in practice and an unbounded loop over a regex driven by
		// user input is a worse trade than missing a third suffix.
		trimmed = APARTMENT.matcher(trimmed).replaceAll("");
		trimmed = APARTMENT.matcher(trimmed).replaceAll("");

		trimmed = WHITESPACE.matcher(trimmed).replaceAll(" ")
			.replaceAll("[\\s,;]+$", "")
			.trim();

		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Whether there is enough here to ask about at all.
	 *
	 * <p>A city on its own is not: it geocodes perfectly, to a centroid, which is
	 * exactly the confident wrong answer this module refuses. Checked before the
	 * call rather than after, so an address that could never work does not spend
	 * a request against someone else's rate limit.
	 */
	static boolean worthAsking(String line, String city) {
		return street(line) != null && city != null && !city.isBlank();
	}

}
