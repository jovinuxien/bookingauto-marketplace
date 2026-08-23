package se.marketplace.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps an unconfigured model provider from stopping the backend.
 *
 * <p>Embabel registers its Anthropic support as an unconditional
 * auto-configuration whose constructor throws when there is no API key:
 * {@code IllegalStateException: Anthropic API key required}. That is a
 * reasonable default for an application that exists to run agents. It is the
 * wrong one here, where agents are an opt-in feature on one endpoint — it would
 * mean a developer machine, a CI run and every deployment that has not enabled
 * search-by-sentence could not start the application at all. An optional feature
 * must not be able to do that.
 *
 * <p>So when the gate is off, or when no key has been supplied, the
 * auto-configuration is excluded before it can run. The agent platform itself
 * stays: it simply has no models, {@link AiGate} already reports disabled, and
 * every caller already has an answer for that.
 *
 * <p>An {@code EnvironmentPostProcessor} rather than a conditional bean because
 * the exclusion has to be decided before the context refreshes, which is the
 * only moment early enough to matter. Registered in
 * {@code META-INF/spring.factories}.
 */
public class ModelsOnlyWhenConfigured implements EnvironmentPostProcessor {

	private static final String EXCLUDE = "spring.autoconfigure.exclude";

	/**
	 * Named as a string rather than a class literal on purpose. Referencing the
	 * class would load it, and this runs precisely in the case where we do not
	 * want it touched.
	 */
	private static final List<String> AGENTS = List.of(
		"com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration",
		"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (usable(environment)) {
			return;
		}

		List<String> excluded = new ArrayList<>(
			List.of(environment.getProperty(EXCLUDE, String[].class, new String[0])));

		List<String> missing = AGENTS.stream().filter(name -> !excluded.contains(name)).toList();

		if (missing.isEmpty()) {
			return;
		}

		excluded.addAll(missing);

		// Highest precedence, because this decides whether a bean may be built
		// at all and anything that could override it would be overriding the
		// reason the application starts.
		environment.getPropertySources().addFirst(new MapPropertySource(
			"marketplace-ai-guard", Map.of(EXCLUDE, String.join(",", excluded))));
	}

	/**
	 * Both halves are required, and they fail differently.
	 *
	 * <p>No key is a deployment that never intended this. The gate off with a key
	 * present is a deployment that has one and has deliberately stopped using
	 * it — usually to stop the spend — and loading the provider anyway would
	 * leave a live client sitting behind a switch that is meant to be off.
	 */
	private static boolean usable(ConfigurableEnvironment environment) {
		if (!environment.getProperty("marketplace.ai.enabled", Boolean.class, false)) {
			return false;
		}

		return present(environment.getProperty("ANTHROPIC_API_KEY"))
			|| present(environment.getProperty("embabel.agent.platform.models.anthropic.api-key"));
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}

}
