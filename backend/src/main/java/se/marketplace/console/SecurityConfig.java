package se.marketplace.console;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Who may call what.
 *
 * <p>Adding Spring Security made everything deny-by-default, which is the right
 * posture and has a consequence worth naming: <strong>the public endpoints now
 * have to be listed on purpose.</strong> The list below is the security model,
 * and every line of it is a decision rather than an oversight.
 *
 * <h2>Sessions, not tokens</h2>
 *
 * <p>The SPA is served from this same jar, so the session cookie is
 * first-party, {@code HttpOnly}, and never touched by JavaScript. A JWT in
 * {@code localStorage} would be readable by any script that ever gets onto the
 * page, and buys nothing here because there is no third origin to authenticate
 * to.
 *
 * <p>CSRF protection is therefore real and stays on, with the token in a cookie
 * the SPA echoes back. It is disabled only where a cookie plays no part:
 * webhook endpoints, which authenticate by signature and could not fetch a
 * token anyway, and the anonymous consumer endpoints, which carry no session to
 * ride on.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		CsrfTokenRequestAttributeHandler csrf = new CsrfTokenRequestAttributeHandler();
		csrf.setCsrfRequestAttributeName(null);

		http
			.csrf(it -> it
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.csrfTokenRequestHandler(csrf)
				// Signature-verified, and a sender that cannot hold a cookie
				// cannot hold a CSRF token either.
				.ignoringRequestMatchers("/internal/**")
				// Anonymous and session-free: there is no ambient authority for
				// a forged request to borrow.
				.ignoringRequestMatchers(
					new AntPathRequestMatcher("/api/bookings", HttpMethod.POST.name())))

			.authorizeHttpRequests(it -> it
				// --- the SPA itself -------------------------------------------
				.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
				.requestMatchers("/sok", "/salong/**", "/boka/**", "/logga-in", "/konsol/**").permitAll()

				// --- webhooks: verified by signature, not by session ----------
				.requestMatchers("/internal/**").permitAll()

				// --- the consumer journey, deliberately anonymous -------------
				// Requiring an account to see availability would cost more
				// bookings than it could ever protect.
				.requestMatchers(HttpMethod.GET, "/api/search").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/providers/*").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/services/*/slots").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()

				.requestMatchers("/api/auth/login").permitAll()

				// --- onboarding is an operator action -------------------------
				// Left open, this would let anyone on the internet create
				// providers and Stripe accounts. Self-serve salon signup needs
				// email verification and rate limiting before it can face the
				// public, and does not exist yet.
				.requestMatchers(HttpMethod.POST, "/api/providers").hasRole("PLATFORM_ADMIN")

				// --- a salon's own setup --------------------------------------
				// Authentication only; the ownership check is in the controller,
				// because "is this your provider" is a data question that a URL
				// pattern cannot answer.
				.requestMatchers("/api/providers/*/kyc-link").authenticated()
				.requestMatchers("/api/providers/*/import-services").authenticated()

				.requestMatchers("/api/console/**").authenticated()

				.anyRequest().authenticated())

			// 401 rather than a redirect to a login page. The caller is a
			// fetch() that wants to handle this itself, and an HTML login form
			// arriving where JSON was expected is a confusing way to be told to
			// sign in.
			.exceptionHandling(it -> it
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

			.logout(it -> it
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler((request, response, authentication) ->
					response.setStatus(HttpStatus.NO_CONTENT.value())));

		return http.build();
	}

	/**
	 * Exposed explicitly because the login endpoint authenticates by hand.
	 *
	 * <p>Spring builds one internally for its own filters but does not publish
	 * it, so a JSON login controller has nothing to inject. Wiring it from the
	 * configured {@code UserDetailsService} and encoder keeps one definition of
	 * how a credential is checked, rather than a second path that could drift.
	 */
	@Bean
	AuthenticationManager authenticationManager(
		UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		// Still runs the hash comparison when the user does not exist, so a
		// missing account and a wrong password take the same time to answer.
		provider.setHideUserNotFoundExceptions(true);

		return new ProviderManager(provider);
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		// Cost factor lives in the hash, so raising it later is a rehash on next
		// login rather than a migration.
		return new BCryptPasswordEncoder(12);
	}

}
