package se.marketplace.console;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Minting a console login, for callers outside this module.
 *
 * <p>Everything else here is package private on purpose: credentials are this
 * module's business and the fewer places that can create one, the fewer places
 * can create one wrongly. Self-serve signup is the first legitimate outside
 * caller, so it gets a door rather than the repository.
 *
 * <p>Hashing is exposed alongside creation rather than left to the caller. A
 * signup collects a password before the account it belongs to exists, so the
 * hash has to be computed early and stored while the address is verified —
 * and the one thing that must not happen is a second opinion about how a
 * credential is turned into a hash.
 */
@Service
public class ProviderLogins {

	private final ProviderUserRepository repository;
	private final PasswordEncoder encoder;

	ProviderLogins(ProviderUserRepository repository, PasswordEncoder encoder) {
		this.repository = repository;
		this.encoder = encoder;
	}

	/**
	 * Whether this address can already sign in.
	 *
	 * <p>For deciding what to <em>send</em>, never for deciding what to answer.
	 * A caller that returns a different status code for a known address has
	 * turned its endpoint into a way to enumerate the platform's salons.
	 */
	public boolean exists(String email) {
		return repository.findByEmail(email).isPresent();
	}

	public String hash(String rawPassword) {
		return encoder.encode(rawPassword);
	}

	/**
	 * Creates the salon's owner login from an already-computed hash.
	 *
	 * @param passwordHash from {@link #hash}, computed when the password was
	 *        collected. Taking a hash rather than a password is deliberate: it
	 *        means a signup waiting to be verified never has to hold the plain
	 *        text anywhere, not in a column and not in a field
	 */
	public long createOwner(long providerId, String email, String passwordHash,
		String displayName) {
		return repository.create(providerId, email, passwordHash, displayName, "owner");
	}

}
