package se.marketplace.console;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/**
 * The logged-in person, and which salon they speak for.
 *
 * <p>Extends Spring's {@code User} so the framework handles the credential
 * plumbing, and adds the one thing the framework cannot know: the provider this
 * session is scoped to. Every console query filters on it.
 *
 * <p>Carrying it on the principal rather than reading it from the request is
 * the point. A provider id that arrives in a path or a body is a claim by the
 * caller; this one was established at login.
 */
class ConsolePrincipal extends User {

	private final long userId;
	private final Long providerId;
	private final String displayName;

	ConsolePrincipal(ProviderUserRepository.ProviderUser user) {
		super(user.email(), user.passwordHash(), authorities(user.role()));
		this.userId = user.id();
		this.providerId = user.providerId();
		this.displayName = user.displayName();
	}

	private static List<GrantedAuthority> authorities(String role) {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
	}

	long userId() {
		return userId;
	}

	/**
	 * @throws IllegalStateException for a platform admin, who has no salon. Not
	 *         a null return: a caller that forgets to handle it would silently
	 *         query for provider null and get an empty console rather than an
	 *         error.
	 */
	long providerId() {
		if (providerId == null) {
			throw new IllegalStateException("platform admin has no provider");
		}
		return providerId;
	}

	Long providerIdOrNull() {
		return providerId;
	}

	String displayName() {
		return displayName;
	}

}
