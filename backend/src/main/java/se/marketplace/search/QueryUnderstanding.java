package se.marketplace.search;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import se.marketplace.ai.AiGate;

/**
 * Runs the agent, and always returns a query.
 *
 * <p>Every path out of here produces something {@code SearchPort} can execute.
 * There is no exception to propagate and no empty result to interpret, because
 * the alternative — a search box that fails when a third party is slow — would
 * make an optional feature capable of breaking the one page this marketplace
 * exists to serve.
 *
 * <p>The three ways to end up with the plain query are worth keeping distinct in
 * the logs even though the customer sees the same thing: not enabled here, too
 * long to be a search, and the model did not answer.
 */
@Component
class QueryUnderstanding {

	private static final Logger log = LoggerFactory.getLogger(QueryUnderstanding.class);

	/**
	 * {@link ObjectProvider} rather than the bean, so that removing the Embabel
	 * starter is a dependency change rather than a context that will not start.
	 * The gate can already turn the feature off; this makes the code honest
	 * about the platform being optional too.
	 */
	private final ObjectProvider<AgentPlatform> platform;
	private final AiGate gate;

	/**
	 * Longer than any real search and short enough to bound the bill. A search
	 * box is not a text area, and something arriving here at two thousand
	 * characters is either a paste accident or someone finding out what we do
	 * with it.
	 */
	@Value("${marketplace.ai.max-query-length:200}")
	private int maxQueryLength;

	QueryUnderstanding(ObjectProvider<AgentPlatform> platform, AiGate gate) {
		this.platform = platform;
		this.gate = gate;
	}

	UnderstoodQuestion of(AskedQuestion question) {
		AgentPlatform agents = platform.getIfAvailable();

		if (!gate.enabled() || agents == null) {
			return UnderstoodQuestion.plain(question, null);
		}

		if (question.text() == null || question.text().isBlank()) {
			return UnderstoodQuestion.plain(question, null);
		}

		if (question.text().length() > maxQueryLength) {
			return UnderstoodQuestion.plain(question, "that is longer than we read");
		}

		try {
			return AgentInvocation.builder(agents)
				.build(UnderstoodQuestion.class)
				.invoke(question);
		}
		catch (RuntimeException e) {
			// Deliberately broad. What reaches here is a timeout, a rate limit,
			// a refusal, a malformed structured response, or a provider outage —
			// five failures with one correct response, which is to search
			// without the filters and say nothing was applied. Warn rather than
			// error: the customer got results, and what is broken is a feature
			// on top of them.
			log.warn("could not interpret '{}': {}", question.text(), e.toString());
			return UnderstoodQuestion.plain(question, "we could not read that, so this is everything nearby");
		}
	}

}
