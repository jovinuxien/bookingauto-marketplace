package se.marketplace.signup;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Turning a salon's name into its address on the internet.
 *
 * <p>The slug is not an internal identifier. It is {@code /salong/klipp-och-co},
 * it is the Cal username, and it is what the salon will put on a shopfront —
 * which is why it is derived from the name they chose rather than generated,
 * and why it is reserved the moment they ask rather than at the end.
 *
 * <p>Folds the same way {@code landing.Slugs} folds city names, and for the same
 * reason: å and ä to a, ö to o is the convention Swedish URLs use. Duplicated
 * rather than shared because these two are the same eight lines today and will
 * not stay that way — a city slug has to keep matching URLs already indexed by
 * Google, while this one only has to be unique and readable. Merging them would
 * couple a marketing URL to a registration form.
 */
final class SignupSlugs {

	/**
	 * Names nobody may take.
	 *
	 * <p>Two systems' worth, because one slug is used in both. Ours are the
	 * paths the SPA and the landing pages already serve; Cal's are the paths it
	 * serves under {@code /username}, and a salon that took one would have a
	 * booking page that resolves to Cal's own settings screen.
	 */
	private static final Set<String> RESERVED = Set.of(
		// ours
		"api", "internal", "assets", "error", "sok", "salong", "boka", "orter",
		"frisor", "massage", "hudvard", "logga-in", "konsol", "registrera",
		"verifiera", "sitemap", "robots", "index", "favicon", "admin", "static",
		// Cal's
		"auth", "signup", "login", "logout", "settings", "event-types", "bookings",
		"availability", "teams", "apps", "insights", "workflows", "routing-forms",
		"video", "payment", "forgot-password", "reset-password", "org", "d", "embed");

	private SignupSlugs() {
	}

	/**
	 * @return the slug, or an empty string if the name has nothing sluggable in
	 *         it. An empty result is a validation failure rather than something
	 *         to substitute a default for: "salong-1" is not the name anyone
	 *         typed
	 */
	static String of(String name) {
		if (name == null) {
			return "";
		}

		String folded = name.toLowerCase(Locale.ROOT)
			.replace('å', 'a').replace('ä', 'a').replace('ö', 'o')
			.replace('é', 'e').replace('è', 'e').replace('ü', 'u')
			.replace("&", " och ");

		String slug = Normalizer.normalize(folded, Normalizer.Form.NFD)
			.replaceAll("\\p{M}", "")
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");

		// Cal's usernames are short and so are useful URLs. Truncating on a
		// dash rather than mid-word keeps the result readable.
		if (slug.length() > 48) {
			slug = slug.substring(0, 48).replaceAll("-[^-]*$", "");
		}

		return slug;
	}

	/** A one-character slug is a URL nobody can guess and Cal may reject. */
	static boolean usable(String slug) {
		return slug.length() >= 3 && !RESERVED.contains(slug);
	}

	/**
	 * The nth candidate for a name whose slug is taken.
	 *
	 * <p>Suffixed rather than refused. Two salons genuinely called "Klipp &amp;
	 * Co" is a normal thing in a country with more than one town, and telling
	 * the second one to think of a different name for its own business is not a
	 * conversation a signup form gets to have.
	 */
	static String candidate(String base, int attempt) {
		return attempt == 0 ? base : base + "-" + (attempt + 1);
	}

}
