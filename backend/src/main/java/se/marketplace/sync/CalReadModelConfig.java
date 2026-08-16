package se.marketplace.sync;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A read-only connection to Cal's database.
 *
 * <p>ADR 0001 sanctions database-as-read-model, and the direction is the whole
 * point: Cal owns time, so its schema is something to observe and never to
 * write. The pool is marked read-only so that a stray UPDATE fails at the
 * driver rather than succeeding quietly and putting us in the business of
 * maintaining Cal's invariants.
 *
 * <p>Small on purpose. This exists to import what a salon built during
 * onboarding, not to serve traffic — anything hot enough to need a real pool
 * belongs in our own schema.
 */
@Configuration
class CalReadModelConfig {

	@Bean
	@Qualifier("calReadModel")
	DataSource calDataSource(
		@Value("${marketplace.cal.datasource.url:jdbc:postgresql://localhost:5442/calendso}") String url,
		@Value("${marketplace.cal.datasource.username:cal}") String username,
		@Value("${marketplace.cal.datasource.password:}") String password) {

		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(url);
		config.setUsername(username);
		config.setPassword(password);
		config.setReadOnly(true);
		config.setMaximumPoolSize(3);
		config.setPoolName("cal-read-model");

		// Do not fail startup if Cal's database is unreachable.
		//
		// Hikari's default is to test a connection eagerly and abort the whole
		// application if it cannot get one. That is right for the marketplace's
		// own database, which nothing works without, and wrong here: this is a
		// projection used during onboarding. Letting it stop the boot means a
		// Cal outage takes down search, booking, the console and the landing
		// pages — none of which touch this pool.
		config.setInitializationFailTimeout(-1);

		return new HikariDataSource(config);
	}

	@Bean
	@Qualifier("calReadModel")
	NamedParameterJdbcTemplate calJdbc(@Qualifier("calReadModel") DataSource dataSource) {
		return new NamedParameterJdbcTemplate(dataSource);
	}

}
