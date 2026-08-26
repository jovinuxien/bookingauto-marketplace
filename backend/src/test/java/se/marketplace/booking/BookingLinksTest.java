package se.marketplace.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The credential a consumer has instead of an account.
 *
 * <p>There is no password to get wrong and no session to expire, so everything
 * standing between a stranger and somebody else's appointment is in this class.
 * The property that matters is not that a valid token works — that is visible
 * the first time anyone clicks a link — but that the ways of arriving with
 * <em>nearly</em> the right one all fail.
 */
class BookingLinksTest {

	private static final String EMAIL = "anna@example.se";

	private BookingLinks links;

	@BeforeEach
	void setUp() {
		links = new BookingLinks();
		ReflectionTestUtils.setField(links, "configuredSecret", "a-secret-nobody-else-has");
		ReflectionTestUtils.setField(links, "publicUrl", "https://boka.example.se");
	}

	@Test
	@DisplayName("a token verifies for the booking it was issued for")
	void roundTrip() {
		String token = links.tokenFor(42L, EMAIL);

		assertThat(links.verify(token, 42L, EMAIL)).isTrue();
		assertThat(BookingLinks.claimedBooking(token)).contains(42L);
	}

	@Test
	@DisplayName("the same booking issued to another address is a different token")
	void boundToTheAddress() {
		String mine = links.tokenFor(42L, EMAIL);

		assertThat(links.verify(mine, 42L, "someone.else@example.se")).isFalse();
	}

	@Test
	@DisplayName("editing the booking id in the link does not open another booking")
	void idIsSigned() {
		String token = links.tokenFor(42L, EMAIL);
		String edited = "43" + token.substring(token.indexOf('.'));

		// The id a token claims is read before anything is checked, so this is
		// the shape of the attack: point a valid signature at a neighbouring
		// booking. It fails because the id is inside the MAC, and because the
		// caller checks against the address of whichever booking it found.
		assertThat(BookingLinks.claimedBooking(edited)).contains(43L);
		assertThat(links.verify(edited, 43L, "the-other-customer@example.se")).isFalse();
	}

	@Test
	@DisplayName("a signature from a different key does not verify")
	void keyMatters() {
		String token = links.tokenFor(42L, EMAIL);

		BookingLinks other = new BookingLinks();
		ReflectionTestUtils.setField(other, "configuredSecret", "a-different-secret");
		ReflectionTestUtils.setField(other, "publicUrl", "https://boka.example.se");

		assertThat(other.verify(token, 42L, EMAIL)).isFalse();
	}

	@Test
	@DisplayName("an address that changes case still opens its own booking")
	void addressIsNormalised() {
		String token = links.tokenFor(42L, EMAIL);

		// Not cosmetic. The booking row holds whatever the customer typed at
		// checkout, and a link that stopped verifying because someone
		// capitalised their own address would be indistinguishable from a
		// forgery — to us and to them.
		assertThat(links.verify(token, 42L, "Anna@Example.SE")).isTrue();
		assertThat(links.verify(token, 42L, "  anna@example.se  ")).isTrue();
	}

	@Test
	@DisplayName("malformed tokens are refused rather than thrown at")
	void malformed() {
		assertThat(links.verify(null, 1L, EMAIL)).isFalse();
		assertThat(links.verify("", 1L, EMAIL)).isFalse();
		assertThat(links.verify("nodot", 1L, EMAIL)).isFalse();
		assertThat(links.verify(".onlyasignature", 1L, EMAIL)).isFalse();
		assertThat(links.verify("42.", 1L, EMAIL)).isFalse();

		assertThat(BookingLinks.claimedBooking(null)).isEmpty();
		assertThat(BookingLinks.claimedBooking("notanumber.sig")).isEmpty();
		assertThat(BookingLinks.claimedBooking("")).isEmpty();
	}

	@Test
	@DisplayName("the link survives being an email link")
	void urlIsSafeToSend() {
		String url = links.urlFor(42L, EMAIL);

		assertThat(url).startsWith("https://boka.example.se/bokning?token=42.");

		// Base64url without padding. A '+' or a '/' in a query string is a
		// token that arrives subtly different from the one that was sent, and
		// '=' is what a mail client wraps a long line on.
		String token = url.substring(url.indexOf("token=") + "token=".length());
		assertThat(token).doesNotContain("+").doesNotContain("/").doesNotContain("=");
		assertThat(links.verify(token, 42L, EMAIL)).isTrue();
	}

	@Test
	@DisplayName("with no secret configured it still works, per process")
	void generatedSecret() {
		// A missing secret must not stop the application starting — the same
		// rule ADR 0012 applies to an absent API key. What it costs is that two
		// processes disagree, which is what the startup warning is about.
		BookingLinks first = new BookingLinks();
		ReflectionTestUtils.setField(first, "configuredSecret", "");
		ReflectionTestUtils.setField(first, "publicUrl", "http://localhost:8090");

		BookingLinks second = new BookingLinks();
		ReflectionTestUtils.setField(second, "configuredSecret", "");
		ReflectionTestUtils.setField(second, "publicUrl", "http://localhost:8090");

		String token = first.tokenFor(42L, EMAIL);

		assertThat(first.verify(token, 42L, EMAIL)).isTrue();
		assertThat(second.verify(token, 42L, EMAIL)).isFalse();
	}

}
