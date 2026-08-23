package se.marketplace.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The switch, and the model behind it.
 *
 * <p>One object rather than an {@code @Value} in every agent, because the answer
 * has to be the same everywhere: an operator turning this off must turn all of
 * it off, and a deployment that has not been given an API key must not have half
 * the system quietly trying anyway.
 *
 * <p><strong>Off by default.</strong> Not caution for its own sake — an agent
 * call is metered, and the endpoint it sits in front of is the one customers hit
 * most. A default of on would bill every developer machine and every CI run for
 * a feature nobody asked those environments to exercise.
 */
@Component
public class AiGate {

	/**
	 * Whether any agent in this application may call a model.
	 *
	 * <p>Callers are expected to have an answer for {@code false} that is a
	 * product rather than an error — ADR 0012 requires it. For search that
	 * answer is the plain geo query, which is what we shipped before any of
	 * this existed.
	 */
	@Value("${marketplace.ai.enabled:false}")
	private boolean enabled;

	/**
	 * The model used for turning a customer's sentence into filter parameters.
	 *
	 * <p>Named separately from anything an operator might later read, because
	 * they are different jobs with different economics. This one runs on the
	 * search path, is short in and short out, and is doing extraction rather
	 * than judgement — the cheapest capable model is the right one, and paying
	 * frontier prices per search would be a real number quickly. Work with a
	 * person waiting on the answer can afford otherwise.
	 *
	 * <p>Blank means "whatever {@code embabel.models.default-llm} says", which is
	 * the right behaviour for a deployment that has deliberately configured one
	 * model and does not want a second name to keep in step.
	 */
	@Value("${marketplace.ai.interpretation-model:}")
	private String interpretationModel;

	public boolean enabled() {
		return enabled;
	}

	/**
	 * @return the model name to use for query interpretation, or {@code null} to
	 *         accept the platform default.
	 */
	public String interpretationModel() {
		return interpretationModel == null || interpretationModel.isBlank() ? null : interpretationModel;
	}

}
