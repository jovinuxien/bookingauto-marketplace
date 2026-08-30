package se.marketplace.vehicles;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A plate, as one value.
 *
 * <p>Normalised on the way in — upper case, no spaces or hyphens — so that
 * "abc 123", "ABC-123" and "ABC123" are one plate rather than three strings,
 * and what is stored is what a registry will be asked for.
 *
 * <p>Lenient about the format on purpose. Swedish plates are three letters,
 * two digits and a digit or letter, and {@link #looksSwedish()} says whether
 * this one is; but a Danish car gets its tyres changed in Malmö too, and
 * refusing its plate at checkout would refuse the booking. Anything from two
 * to eight letters and digits is accepted as typed; whether it can be looked
 * up is the registry adapter's question, not the form's.
 */
public record RegistrationNumber(String value) {

	private static final Pattern PLATE = Pattern.compile("^[A-Z0-9]{2,8}$");

	// ABC123 or ABC12A. Letters I, Q, V and Å/Ä/Ö are not issued, but a plate
	// is what is on the car rather than what the rules say, so not enforced.
	private static final Pattern SWEDISH = Pattern.compile("^[A-Z]{3}[0-9]{2}[0-9A-Z]$");

	public RegistrationNumber {
		if (value == null || !PLATE.matcher(value).matches()) {
			throw new IllegalArgumentException("not a registration number: " + value);
		}
	}

	/** Empty for blank input and for anything that is not plausibly a plate. */
	public static Optional<RegistrationNumber> parse(String typed) {
		if (typed == null) {
			return Optional.empty();
		}

		String normalised = typed.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);

		return PLATE.matcher(normalised).matches()
			? Optional.of(new RegistrationNumber(normalised))
			: Optional.empty();
	}

	public boolean looksSwedish() {
		return SWEDISH.matcher(value).matches();
	}

	/** As a person writes it: {@code ABC 123}. */
	public String display() {
		return looksSwedish() ? value.substring(0, 3) + " " + value.substring(3) : value;
	}

}
