package se.marketplace.signup;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The public registration endpoints.
 *
 * <p>Two calls, one email apart. The first takes the form and answers 202
 * whatever happens next; the second is the link in the email being clicked.
 *
 * <p>The status codes carry the design. 202 rather than 201 because nothing has
 * been created — an accepted registration is a promise to send an email, not a
 * salon — and the same 202 for an address that already has an account, which is
 * the only way this endpoint can avoid being a way to enumerate the platform.
 */
@RestController
@RequestMapping("/api/signup")
class SignupController {

	private final SelfServeSignup signup;

	SignupController(SelfServeSignup signup) {
		this.signup = signup;
	}

	@PostMapping
	ResponseEntity<?> register(@RequestBody SelfServeSignup.Registration request,
		HttpServletRequest http) {

		return switch (signup.register(request, clientIp(http))) {
			case SelfServeSignup.Accepted ignored ->
				ResponseEntity.accepted().build();

			// 400 with the fields, because these are things the person can fix
			// while looking at the form. The one thing not returned here is
			// whether the address is already registered.
			case SelfServeSignup.Rejected rejected ->
				ResponseEntity.badRequest().body(rejected.problems());

			case SelfServeSignup.Throttled ignored ->
				ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
		};
	}

	@PostMapping("/verify")
	ResponseEntity<?> verify(@RequestBody VerifyRequest request, HttpServletRequest http) {
		if (request.token() == null || request.token().isBlank()) {
			return ResponseEntity.badRequest().build();
		}

		return switch (signup.verify(request.token(), clientIp(http))) {
			case SelfServeSignup.Ready ready ->
				ResponseEntity.ok(ready);

			// 410 rather than 404. The link was real; it is the moment that has
			// passed, and that is the difference between "check the address you
			// pasted" and "ask for a new one".
			case SelfServeSignup.LinkUnusable unusable ->
				ResponseEntity.status(HttpStatus.GONE).body(unusable);

			// 502: the failure is upstream, in Cal or in Stripe, and the body
			// says whether clicking again is worth doing.
			case SelfServeSignup.ProvisioningFailed failed ->
				ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(failed);

			case SelfServeSignup.Throttled ignored ->
				ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
		};
	}

	/**
	 * Who is calling, for the purpose of counting them.
	 *
	 * <p>The socket address, never {@code X-Forwarded-For}. That header is
	 * written by the client and a rate limit keyed on it is one the caller sets
	 * for themselves, one fresh value per request. Behind a proxy the fix is
	 * {@code server.forward-headers-strategy}, which makes the container rewrite
	 * this from a header it is configured to trust — a deployment decision, and
	 * correctly not one this class gets to make.
	 */
	private static String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	record VerifyRequest(String token) {}

}
