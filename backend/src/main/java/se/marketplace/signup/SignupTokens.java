package se.marketplace.signup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The two secrets a signup involves.
 *
 * <p>The verification token is a bearer credential: whoever holds it can prove
 * ownership of an address they may not own. So it is generated from
 * {@link SecureRandom}, is long enough that guessing is not a strategy, and is
 * stored only as a hash — the same reasoning that applies to a password, for
 * the same reason.
 *
 * <p>The Cal password is generated rather than borrowed from the console
 * password the salon just chose. Reusing it would hand a credential the person
 * uses here to a third-party system, and ADR 0010 already commits us to the
 * salon having two logins; making them the same password is how one system's
 * breach becomes both.
 */
final class SignupTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
	private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final String DIGITS = "23456789";

	private SignupTokens() {
	}

	/** 256 bits, URL-safe and unpadded so it survives being an email link. */
	static String issue() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			// Unsalted, and deliberately. A salt defends against precomputation
			// over a small guessable space; this input is 256 random bits, and a
			// per-row salt would only stop us finding the row by its token,
			// which is the entire lookup.
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the platform", e);
		}
	}

	/**
	 * A password for the salon's Cal account.
	 *
	 * <p>Composed to satisfy Cal's own rule — upper, lower and a digit — rather
	 * than drawn uniformly and hoped for. A one-in-a-few-hundred signup failing
	 * on a rejected password would be untraceable from here and would look like
	 * Cal being down.
	 *
	 * <p>Ambiguous characters are left out of the alphabet. It is read off a
	 * screen and typed into another site by a person.
	 */
	static String calPassword() {
		StringBuilder password = new StringBuilder();
		password.append(pick(UPPER)).append(pick(LOWER)).append(pick(DIGITS));

		String all = LOWER + UPPER + DIGITS;
		while (password.length() < 20) {
			password.append(pick(all));
		}

		// The guaranteed characters are at fixed positions until they are not.
		char[] characters = password.toString().toCharArray();
		for (int i = characters.length - 1; i > 0; i--) {
			int j = RANDOM.nextInt(i + 1);
			char swap = characters[i];
			characters[i] = characters[j];
			characters[j] = swap;
		}

		return new String(characters);
	}

	private static char pick(String alphabet) {
		return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
	}

}
