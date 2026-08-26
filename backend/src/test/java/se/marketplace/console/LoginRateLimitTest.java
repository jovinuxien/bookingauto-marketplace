package se.marketplace.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.ratelimit.RateLimiter;

/**
 * What the limit on the login endpoint is actually for.
 *
 * <p>Not the counting — that is {@code RateLimiter}'s, and signup already
 * proves it. What has to hold here is <strong>where</strong> the count sits:
 * before the password is checked. Verifying a password is deliberately slow,
 * and an endpoint that checks first has already paid for the request it is
 * about to refuse, which turns a rate limit into a slightly politer way of
 * being flooded.
 *
 * <p>That ordering is invisible from the response — a refused caller sees 429
 * either way — so nothing but a test can hold it in place.
 */
class LoginRateLimitTest {

	private static final String IP = "198.51.100.7";
	private static final String OTHER_IP = "203.0.113.9";

	private CountingLimiter limiter;
	private CountingAuthentication authentication;
	private AuthController controller;

	@BeforeEach
	void setUp() {
		limiter = new CountingLimiter();
		authentication = new CountingAuthentication();

		LoginAttempts attempts = new LoginAttempts(limiter);
		ReflectionTestUtils.setField(attempts, "perIpPerHour", 30);

		controller = new AuthController(authentication, new SilentRepository(), attempts);
	}

	private ResponseEntity<AuthController.Session> login(String clientIp) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(clientIp);

		return controller.login(
			new AuthController.Credentials("anna@klippco.se", "ett-riktigt-langt-losenord"),
			request, new MockHttpServletResponse());
	}

	// ------------------------------------------- the count comes first --

	@Test
	@DisplayName("a throttled attempt never reaches the password")
	void refusedBeforeAuthenticating() {
		limiter.refuse("login:ip:" + IP);

		assertThat(login(IP).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		// The whole point. Had this been checked after authenticating, the
		// caller would have got the 429 and we would still have paid for it.
		assertThat(authentication.calls).isZero();
	}

	@Test
	@DisplayName("an attempt within the limit is checked as usual")
	void permittedAttemptsAuthenticate() {
		assertThat(login(IP).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		assertThat(authentication.calls).isEqualTo(1);
		assertThat(limiter.counted).containsExactly("login:ip:" + IP);
	}

	// ------------------------------------------------ what gets counted --

	@Test
	@DisplayName("succeeding still spends an attempt")
	void successIsCountedToo() {
		authentication.succeed();

		assertThat(login(IP).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Deliberate, and the reason the allowance is set for a busy salon
		// rather than for one person: before the password is checked there is
		// nothing here that can tell a sign-in from a guess at one.
		assertThat(limiter.counted).containsExactly("login:ip:" + IP);
	}

	@Test
	@DisplayName("one source being over its limit does not refuse another")
	void bucketIsPerSource() {
		limiter.refuse("login:ip:" + IP);

		assertThat(login(IP).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(login(OTHER_IP).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("the address being signed in to is not part of the key")
	void notCountedPerAccount() {
		login(IP);

		// A per-account bucket would be a lockout anyone could trigger against
		// any salon whose email they knew, because refusing before the password
		// is checked cannot tell the owner from someone guessing at them. See
		// LoginAttempts — this is a decision, not an omission.
		assertThat(limiter.counted).noneMatch(bucket -> bucket.contains("anna@klippco.se"));
	}

	// ----------------------------------------------------------- fakes --

	/** Counts what it was asked about, and refuses only what it was told to. */
	private static final class CountingLimiter extends RateLimiter {

		private final List<String> counted = new ArrayList<>();
		private final Map<String, Boolean> refused = new HashMap<>();

		private CountingLimiter() {
			super(null);
		}

		void refuse(String bucket) {
			refused.put(bucket, true);
		}

		@Override
		public boolean allow(String bucket, int limit, Duration window) {
			counted.add(bucket);
			return !refused.getOrDefault(bucket, false);
		}

	}

	/** Refuses everything until told otherwise, and remembers being asked. */
	private static final class CountingAuthentication implements AuthenticationManager {

		private int calls;
		private boolean succeeds;

		void succeed() {
			succeeds = true;
		}

		@Override
		public Authentication authenticate(Authentication request) throws AuthenticationException {
			calls++;

			if (!succeeds) {
				throw new BadCredentialsException("no");
			}

			ConsolePrincipal principal = new ConsolePrincipal(
				new ProviderUserRepository.ProviderUser(
					1L, 7L, "anna@klippco.se", "{noop}x", "Klipp & Co", "salon"));

			return new UsernamePasswordAuthenticationToken(
				principal, null, principal.getAuthorities());
		}

	}

	/** There is no database here, and nothing in these tests reads one. */
	private static final class SilentRepository extends ProviderUserRepository {

		private SilentRepository() {
			super(null);
		}

		@Override
		void recordLogin(long id) {
		}

	}

}
