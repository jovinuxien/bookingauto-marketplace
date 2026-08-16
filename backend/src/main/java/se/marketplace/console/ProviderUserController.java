package se.marketplace.console;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating a salon's login.
 *
 * <p>An operator action, deliberately separate from onboarding. Onboarding
 * assembles what a salon needs in Cal and Stripe; this hands a person the keys,
 * and the two failing independently is easier to reason about than one endpoint
 * that half-succeeded.
 *
 * <p>Restricted to platform admins. Without that, any salon owner could mint a
 * login for another salon.
 */
@RestController
@RequestMapping("/api/console/users")
class ProviderUserController {

	private final ProviderUserRepository repository;
	private final PasswordEncoder encoder;

	ProviderUserController(ProviderUserRepository repository, PasswordEncoder encoder) {
		this.repository = repository;
		this.encoder = encoder;
	}

	@PostMapping
	@PreAuthorize("hasRole('PLATFORM_ADMIN')")
	ResponseEntity<Created> create(@RequestBody NewUser request) {
		if (repository.findByEmail(request.email()).isPresent()) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}

		long id = repository.create(request.providerId(), request.email(),
			encoder.encode(request.password()), request.displayName(), "owner");

		return ResponseEntity.status(HttpStatus.CREATED).body(new Created(id));
	}

	record NewUser(Long providerId, String email, String password, String displayName) {}

	record Created(long id) {}

}
