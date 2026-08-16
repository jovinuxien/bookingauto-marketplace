package se.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * One backend behind both frontends.
 *
 * <p>bokadirekt.se and business.bokadirekt.se are two TypeScript applications
 * over this single service. The modules below divide by <em>domain</em>, not by
 * audience — both audiences need providers, both touch bookings, and splitting
 * by audience would duplicate all of it.
 *
 * <p>Spring Modulith enforces the boundaries at build time, so splitting a
 * module into its own deployable later is a deployment change rather than a
 * rewrite. {@code search} is the one most likely to earn that first.
 */
@Modulithic(
	systemName = "booking-marketplace",
	sharedModules = {}
)
@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
