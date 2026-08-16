package se.marketplace.console;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class ConsoleUserDetailsService implements UserDetailsService {

	private final ProviderUserRepository repository;

	ConsoleUserDetailsService(ProviderUserRepository repository) {
		this.repository = repository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		return repository.findByEmail(email)
			.map(ConsolePrincipal::new)
			// Deliberately the same message whether the address is unknown or
			// the account is inactive. Distinguishing them turns the login form
			// into a way to enumerate which salons exist.
			.orElseThrow(() -> new UsernameNotFoundException("bad credentials"));
	}

}
