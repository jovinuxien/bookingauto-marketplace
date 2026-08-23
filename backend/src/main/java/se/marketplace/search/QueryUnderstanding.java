package se.marketplace.search;

import java.time.Duration;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import se.marketplace.ai.AiGate;
import se.marketplace.ratelimit.RateLimiter;

/**
 * Runs the agent, and always returns a query.
 *
 * <p>Every path out of here produces something {@code SearchPort} can execute.
 * There is no exception to propagate and no empty result to interpret, because
 * the alternative — a search box that fails when a third party is slow — would
 * make an optional feature capable of breaking the one page this marketplace
 * exists to serve.
 *
 * <p>The four ways to end up with the plain query are worth keeping distinct in
 * the logs even though the customer sees nearly the same thing: not enabled
 * here, too long to be a search, asked too often from one place, and the model
 * did not answer.
 */
@Component
class QueryUnderstanding {

	private static final Logger log = LoggerFactory.getLogger(QueryUnderstanding.class);

	private static final Duration HOUR = Duration.ofHours(1);

	/**
	 * {@link ObjectProvider} rather than the bean, so that removing the Embabel
	 * starter is a dependency change rather than a context that will not start.
	 * The gate can already turn the feature off; this makes the code honest
	 * about the platform being optional too.
	 */
	private final ObjectProvider<AgentPlatform> platform;
	private final AiGate gate;
	private final RateLimiter limiter;

	/**
	 * Longer than any real search and short enough to bound the bill. A search
	 * box is not a text area, and something arriving here at two thousand
	 * characters is either a paste accident or someone finding out what we do
	 * with it.
	 */
	@Value("${marketplace.ai.max-query-length:200}")
	private int maxQueryLength;

	/**
	 * Interpreted searches per hour from one address.
	 *
	 * <p>Generous for a person and useless for a script. Someone genuinely
	 * shopping might search a few dozen times in an afternoon, and a shared
	 * office or a mobile carrier is one address for a great many of them — so
	 * the number can be high, because being over it is not a lockout. It costs
	 * the caller their interpretation and costs us a PostGIS query we were going
	 * to run anyway.
	 *
	 * <p>That is what separates this from ADR 0011's limits, which had to be
	 * small. Signup was defending accounts at Cal and Stripe, where being wrong
	 * is not ours to undo. This is defending an invoice, where it is.
	 */
	@Value("${marketplace.ai.ask-per-ip-per-hour:60}")
	private int askPerIpPerHour;

	QueryUnderstanding(ObjectProvider<AgentPlatform> platform, AiGate gate, RateLimiter limiter) {
		this.platform = platform;
		this.gate = gate;
		this.limiter = limiter;
	}

	/**
	 * @param clientIp the socket address, never a header. A limit keyed on
	 *                 something the caller writes is a limit the caller sets for
	 *                 themselves, one fresh value per request — see ADR 0011.
	 */
	UnderstoodQuestion of(AskedQuestion question, String clientIp) {
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

		// Counted last of the cheap checks, and only where the next line would
		// actually spend something. Counting above them would let a script empty
		// a real customer's window using input we pay nothing for.
		if (!limiter.allow("search:ask:" + clientIp, askPerIpPerHour, HOUR)) {
			// Not a 429. What is being protected is a bill, not a resource, and
			// the customer is still owed the search they asked for — they get
			// the same salons, without the sentence having been read.
			return UnderstoodQuestion.plain(question, "this is everything nearby");
		}

		try {
			return interpret(agents, question);
		}
		catch (Exception e) {
			// Deliberately broad. What reaches here is a timeout, a rate limit,
			// a refusal, a malformed structured response, or a provider outage —
			// five failures with one correct response, which is to search
			// without the filters and say nothing was applied. Warn rather than
			// error: the customer got results, and what is broken is a feature
			// on top of them.
			log.warn("could not interpret '{}': {}", question.text(), rootOf(e));
			return UnderstoodQuestion.plain(question, "we could not read that, so this is everything nearby");
		}
	}

	/**
	 * The invocation, behind a seam, and the {@code throws Exception} is the
	 * whole reason it exists.
	 *
	 * <p>This was originally inline under {@code catch (RuntimeException)}, which
	 * compiled, read as thorough and did not work: Embabel is Kotlin, Kotlin has
	 * no checked exceptions, and a failed model call arrives as a
	 * {@link java.util.concurrent.ExecutionException} — checked — thrown straight
	 * through a signature that declares nothing. A bad API key produced HTTP 500
	 * from the one endpoint whose entire design is that it cannot fail.
	 *
	 * <p>Declaring the seam {@code throws Exception} makes the compiler insist on
	 * the catch that actually holds, and lets a test throw the exception that
	 * caused this without needing a model to refuse one.
	 */
	UnderstoodQuestion interpret(AgentPlatform agents, AskedQuestion question) throws Exception {
		return AgentInvocation.builder(agents)
			.build(UnderstoodQuestion.class)
			.invoke(question);
	}

	/**
	 * The cause, not the wrapper. {@code ExecutionException: NonTransientAiException: 401}
	 * says three things and the last one is the only one worth reading at 3am.
	 */
	private static String rootOf(Throwable e) {
		Throwable cause = e;

		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}

		return cause.toString();
	}

}
