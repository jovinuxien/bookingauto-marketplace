package se.marketplace.pricing;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rim diameter out of a tyre dimension string.
 *
 * <p>"215/55R16", "215/55 R 16", "205/55-16 91V" and "225/45ZR18" all say
 * a two-digit number after the R (or the dash), and that number is what a
 * däckverkstad prices by. Anything else is empty rather than a guess.
 */
final class RimInches {

	private static final Pattern AFTER_R = Pattern.compile("(?i)(?:ZR|R|-)\\s?(\\d{2})(?!\\d)");

	private RimInches() {
	}

	static Optional<Integer> of(String tyreDimension) {
		if (tyreDimension == null) {
			return Optional.empty();
		}
		Matcher m = AFTER_R.matcher(tyreDimension);
		if (!m.find()) {
			return Optional.empty();
		}
		int inches = Integer.parseInt(m.group(1));
		return inches >= 10 && inches <= 30 ? Optional.of(inches) : Optional.empty();
	}

}
