package se.marketplace.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Signing in.
 *
 * <p>A JSON endpoint rather than Spring's form login, because the caller is a
 * fetch() that wants to render its own error. The session cookie it establishes
 * is first-party and {@code HttpOnly}, so no script can read it.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final AuthenticationManager authenticationManager;
	private final ProviderUserRepository repository;
	private final SecurityContextRepository contextRepository =
		new HttpSessionSecurityContextRepository();

	AuthController(AuthenticationManager authenticationManager, ProviderUserRepository repository) {
		this.authenticationManager = authenticationManager;
		this.repository = repository;
	}

	@PostMapping("/login")
	ResponseEntity<Session> login(@RequestBody Credentials credentials,
		HttpServletRequest request, HttpServletResponse response) {

		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(credentials.email(), credentials.password()));
		}
		catch (AuthenticationException e) {
			// One message for every failure. Saying "no such user" would let
			// anyone enumerate which salons are on the platform.
			log.info("failed login for {}", credentials.email());
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}

		// A fresh session id after authenticating, so a session fixed before
		// login cannot be reused after it.
		request.getSession(true).invalidate();
		request.getSession(true);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);

		ConsolePrincipal principal = (ConsolePrincipal) authentication.getPrincipal();
		repository.recordLogin(principal.userId());

		return ResponseEntity.ok(sessionOf(principal));
	}

	/** Who am I — used by the SPA on load to decide whether to show the console. */
	@GetMapping("/me")
	ResponseEntity<Session> me(@AuthenticationPrincipal ConsolePrincipal principal) {
		if (principal == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		return ResponseEntity.ok(sessionOf(principal));
	}

	private Session sessionOf(ConsolePrincipal principal) {
		return new Session(
			principal.getUsername(),
			principal.displayName(),
			principal.providerIdOrNull(),
			principal.getAuthorities().iterator().next().getAuthority());
	}

	record Credentials(String email, String password) {}

	record Session(String email, String displayName, Long providerId, String role) {}

}
