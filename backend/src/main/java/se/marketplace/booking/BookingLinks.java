package se.marketplace.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The link that stands in for a consumer account.
 *
 * <p>A booking is reachable by whoever holds a token naming it, and the token
 * is sent to the address the booking was made with. That address is the
 * identity — see ADR 0014, which is ADR 0011's argument pointed at consumers:
 * a mailbox already proves what a password would, and a marketplace with no
 * consumer credentials has none to leak.
 *
 * <h2>Derived, not stored</h2>
 *
 * <p>An HMAC over the booking id and the customer's address, rather than
 * {@code signup}'s random-and-hashed token. The difference is what the two
 * credentials have to do. A verification link is used once and must be
 * revocable, so it needs a row whose state can change. This one is opened
 * repeatedly — from an email, weeks apart, on whatever device is to hand — and
 * has no state to keep. Deriving it means no table, no row per sale, and
 * nothing to sweep.
 *
 * <p>The address is inside the MAC, so a token is bound to the booking it was
 * issued for and cannot be replayed against another. It is not a second
 * secret: an attacker who could forge the MAC would not need it.
 *
 * <h2>It does not expire, and that is a decision</h2>
 *
 * <p>What the token authorises shrinks by itself: cancelling is refused once
 * the appointment has passed, which is a fact about the booking rather than
 * about the link. What is left afterwards is the ability to read a booking's
 * salon, time and price — which is exactly what the email carrying the link
 * already says, to the same mailbox. An expiry would therefore buy nothing
 * against anyone who can read that mailbox, and would cost a customer their
 * receipt.
 */
@Component
public class BookingLinks {

	private static final Logger log = LoggerFactory.getLogger(BookingLinks.class);

	private static final String ALGORITHM = "HmacSHA256";

	/**
	 * Blank means one is generated at startup.
	 *
	 * <p>Not a fixed default. A shipped constant here would make every
	 * deployment that forgot to set it forgeable by anyone who had read this
	 * repository, and a secret whose default is safe is a secret nobody
	 * remembers to set.
	 */
	@Value("${marketplace.booking.link-secret:}")
	private String configuredSecret;

	@Value("${marketplace.public-url:http://localhost:8090}")
	private String publicUrl;

	private volatile byte[] key;

	/**
	 * Resolves the key once, loudly.
	 *
	 * <p>A missing secret must not stop the application starting — the same rule
	 * ADR 0012 applies to an absent API key and ADR 0013 to a category with no
	 * route. So a generated key is used instead, and the warning says exactly
	 * what it costs, because the failure is otherwise invisible until a customer
	 * clicks a link from before the last restart and is told it is not valid.
	 */
	private byte[] key() {
		byte[] resolved = key;

		if (resolved == null) {
			synchronized (this) {
				if (key == null) {
					if (configuredSecret == null || configuredSecret.isBlank()) {
						byte[] generated = new byte[32];
						new SecureRandom().nextBytes(generated);
						key = generated;
						log.warn("marketplace.booking.link-secret is not set — generated one for "
							+ "this process. Booking links already emailed will stop working at "
							+ "the next restart. Set it before anything is sent to a real customer.");
					}
					else {
						key = configuredSecret.getBytes(StandardCharsets.UTF_8);
					}
				}
				resolved = key;
			}
		}

		return resolved;
	}

	/** The address a customer is sent to reach this booking. */
	public String urlFor(long bookingId, String customerEmail) {
		return publicUrl + "/bokning?token=" + tokenFor(bookingId, customerEmail);
	}

	String tokenFor(long bookingId, String customerEmail) {
		return bookingId + "." + sign(bookingId, customerEmail);
	}

	/**
	 * The booking this token names, if it names one honestly.
	 *
	 * <p>Takes the address to check against, so verification is a comparison
	 * rather than a decode: the caller reads the booking by the id in the token
	 * and asks whether the token was issued for <em>that</em> booking's
	 * customer. A token whose id has been edited therefore fails here rather
	 * than returning someone else's appointment.
	 */
	boolean verify(String token, long bookingId, String customerEmail) {
		if (token == null) {
			return false;
		}

		int dot = token.indexOf('.');

		if (dot < 1 || dot == token.length() - 1) {
			return false;
		}

		String expected = sign(bookingId, customerEmail);

		// Constant time. The comparison is against a value an attacker supplies
		// and can vary freely, which is the shape a timing attack needs.
		return MessageDigest.isEqual(
			token.substring(dot + 1).getBytes(StandardCharsets.UTF_8),
			expected.getBytes(StandardCharsets.UTF_8));
	}

	/** The id a token claims, before anything has been checked about it. */
	static Optional<Long> claimedBooking(String token) {
		if (token == null) {
			return Optional.empty();
		}

		int dot = token.indexOf('.');

		if (dot < 1) {
			return Optional.empty();
		}

		try {
			return Optional.of(Long.parseLong(token.substring(0, dot)));
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private String sign(long bookingId, String customerEmail) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(key(), ALGORITHM));

			// Lower-cased, matching how every other lookup in this system treats
			// an address. A booking written with a capitalised address must not
			// produce a link that stops verifying.
			String message = bookingId + ":"
				+ (customerEmail == null ? "" : customerEmail.trim().toLowerCase(Locale.ROOT));

			return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is required by the platform", e);
		}
	}

}
