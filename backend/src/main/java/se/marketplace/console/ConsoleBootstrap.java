package se.marketplace.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform admin, and only the first.
 *
 * <p>Every authenticated system has a bootstrap problem: someone has to be able
 * to create the first account, and that someone cannot log in yet. Solving it
 * with a default password baked into the code is how installations end up
 * reachable by anyone who has read the source.
 *
 * <p>So this does nothing unless credentials are supplied by configuration, and
 * nothing again once an admin exists. It is a one-shot, not a reconciler:
 * re-running it must not resurrect an account that was deliberately removed.
 */
@Component
class ConsoleBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ConsoleBootstrap.class);

	private final NamedParameterJdbcTemplate jdbc;
	private final ProviderUserRepository repository;
	private final PasswordEncoder encoder;

	@Value("${marketplace.console.bootstrap-admin-email:}")
	private String email;

	@Value("${marketplace.console.bootstrap-admin-password:}")
	private String password;

	ConsoleBootstrap(NamedParameterJdbcTemplate jdbc, ProviderUserRepository repository,
		PasswordEncoder encoder) {
		this.jdbc = jdbc;
		this.repository = repository;
		this.encoder = encoder;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (email.isBlank() || password.isBlank()) {
			return;
		}

		Integer existing = jdbc.queryForObject(
			"SELECT count(*) FROM provider_user WHERE role = 'platform_admin'",
			new MapSqlParameterSource(), Integer.class);

		if (existing != null && existing > 0) {
			return;
		}

		repository.create(null, email, encoder.encode(password), "Platform admin", "platform_admin");
		log.warn("created bootstrap platform admin {} — remove the bootstrap properties now", email);
	}

}
