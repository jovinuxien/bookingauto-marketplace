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
	}

}
