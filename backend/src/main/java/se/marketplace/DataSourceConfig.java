package se.marketplace;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The marketplace database, declared explicitly.
 *
 * <p>It used to be auto-configured, and would be still except for a Spring Boot
 * rule worth knowing: <strong>defining any {@code DataSource} bean anywhere
 * switches the auto-configuration off entirely.</strong> Adding a read-only
 * connection to Cal's database therefore left exactly one DataSource in the
 * context — Cal's — and every query in the application quietly went there. It
 * surfaced as {@code relation "provider" does not exist}, which is a confusing
 * way to be told the datasource is the wrong one.
 *
 * <p>So both are declared, and this one is {@link Primary}: anything that asks
 * for "the database" without qualification means the marketplace's, and reaching
 * Cal's has to be deliberate.
 */
@Configuration
class DataSourceConfig {

	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource")
	DataSourceProperties marketplaceDataSourceProperties() {
		return new DataSourceProperties();
	}

	@Bean
	@Primary
	@ConfigurationProperties("spring.datasource.hikari")
	DataSource marketplaceDataSource(
		@Qualifier("marketplaceDataSourceProperties") DataSourceProperties properties) {
		return properties.initializeDataSourceBuilder().build();
	}

	@Bean
	@Primary
	NamedParameterJdbcTemplate marketplaceJdbc(@Qualifier("marketplaceDataSource") DataSource dataSource) {
		return new NamedParameterJdbcTemplate(dataSource);
	}

}
