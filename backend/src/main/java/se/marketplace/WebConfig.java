package se.marketplace;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the SPA from the same jar as the API.
 *
 * <p>One artefact and one origin: no CORS to configure, no second deployment to
 * keep in step, and no way for a page to reach an API version it was not built
 * against.
 *
 * <h2>Why the forwards exist</h2>
 *
 * <p>The router owns paths like {@code /salong/salong-sodermalm} that have no
 * corresponding file. A browser asking for one directly — a shared link, a
 * bookmark, a reload — would otherwise get a 404 from the static handler, and
 * the app would appear to work right up until someone shared a URL.
 *
 * <p>So unmatched paths forward to {@code index.html} and the router takes over
 * on the client. The forwards are enumerated rather than expressed as a
 * catch-all: a genuine typo under {@code /api} should still 404 loudly instead
 * of quietly returning HTML to something expecting JSON, which is a far more
 * confusing failure to debug.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/sok").setViewName("forward:/index.html");
		// /salong/{slug} is not here: it is server-rendered by LandingController,
		// because a salon's own name is one of the three things people search
		// for before they know this site exists.
		registry.addViewController("/boka/{serviceId}").setViewName("forward:/index.html");
		registry.addViewController("/logga-in").setViewName("forward:/index.html");
		registry.addViewController("/konsol").setViewName("forward:/index.html");
		registry.addViewController("/registrera").setViewName("forward:/index.html");
		// Where the verification email lands, which means it is typed by nobody
		// and arrives cold from a mail client. A route the security config
		// permits but this list forgets returns Spring's own error page, and the
		// permit reads as though the page works.
		registry.addViewController("/verifiera").setViewName("forward:/index.html");
		// The same, for the link in a confirmation email — and it was forgotten
		// here first, exactly as the comment above predicts. Permitted in
		// SecurityConfig, routed in the SPA, and a plain 404 in a browser,
		// because nothing reaches React until this line exists.
		registry.addViewController("/bokning").setViewName("forward:/index.html");
		// The embeddable storefront (ADR 0018): framed on other people's sites,
		// so it of all routes must never show Spring's error page.
		registry.addViewController("/widget/{slug}").setViewName("forward:/index.html");
	}

}
