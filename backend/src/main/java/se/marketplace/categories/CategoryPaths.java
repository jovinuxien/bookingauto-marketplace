package se.marketplace.categories;

/**
 * The category URLs that have routes, as one compile-time constant.
 *
 * <p>Three readers, and they must agree: the landing controller's route
 * pattern (where it has to be a literal), the boot check that compares it
 * with the table, and the security configuration that lets the public reach
 * those paths. The third one is why this lives here rather than in
 * {@code landing}: the first vehicle category was routed and seeded and
 * checked at boot — and answered 401, because the permit list in
 * {@code console} was its own copy of the three original paths. A page
 * nobody can reach fails exactly like a page that does not exist, and the
 * boot check could not see it.
 *
 * <p>Still not the table. The route has to be a literal in an annotation,
 * and the alternative — {@code /{a}/{b}} — shadows every other two-segment
 * path in the application (ADR 0013). Adding a category stays a row and a
 * word; there is now one word.
 */
public final class CategoryPaths {

	public static final String ROUTED =
		"frisor|massage|hudvard|dackbyte|bilservice|bilvard|bilglas|cykelservice";

	private CategoryPaths() {
	}

	/** Ant patterns for the security configuration: {@code /frisor/**} and so on. */
	public static String[] antPatterns() {
		String[] paths = ROUTED.split("\\|");
		String[] patterns = new String[paths.length];
		for (int i = 0; i < paths.length; i++) {
			patterns[i] = "/" + paths[i] + "/**";
		}
		return patterns;
	}

}
