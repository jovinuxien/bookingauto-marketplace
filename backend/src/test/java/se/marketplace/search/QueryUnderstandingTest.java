package se.marketplace.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.embabel.agent.core.AgentPlatform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import se.marketplace.ai.AiGate;
import se.marketplace.ratelimit.RateLimiter;

/**
 * The order the cheap checks happen in, and what the counter is spent on.
 *
 * <p>Everything here is about not calling a model, which is the only part of
 * this class with a bill attached. Whether the model then answers well is not a
 * question an assertion can settle; whether it is asked at all is.
 */
class QueryUnderstandingTest {

	private static final String CALLER = "203.0.113.7";

	private CountingLimiter limiter;
	private AiGate gate;
	private AgentPlatform platform;
	private QueryUnderstanding understanding;

	@BeforeEach
	void setUp() {
		limiter = new CountingLimiter();
		gate = new AiGate();
		platform = mock(AgentPlatform.class);

		enable(true);
		understanding = build(platform);
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<AgentPlatform> providerOf(AgentPlatform available) {
		ObjectProvider<AgentPlatform> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(available);
		return provider;
	}

	private QueryUnderstanding build(AgentPlatform available) {
		return tune(new QueryUnderstanding(providerOf(available), gate, limiter));
	}

	private static <T extends QueryUnderstanding> T tune(T built) {
		ReflectionTestUtils.setField(built, "maxQueryLength", 200);
		ReflectionTestUtils.setField(built, "askPerIpPerHour", 60);
		return built;
	}

	private void enable(boolean enabled) {
		ReflectionTestUtils.setField(gate, "enabled", enabled);
	}

	private static AskedQuestion asking(String text) {
		return new AskedQuestion(text, 59.32, 18.06, 5000, LocalDate.of(2026, 8, 23), 14, 20);
	}

	@Test
	@DisplayName("with the gate off nothing is asked and nothing is counted")
	void gateOffCostsNothing() {
		// Counting here would let a deployment that has never enabled the
		// feature still fill its rate limit table, and would charge a window
		// against a customer for a request that could not have spent anything.
		enable(false);

		var understood = understanding.of(asking("balayage på lördag"), CALLER);

		assertThat(understood.summary()).isNull();
		assertThat(understood.request().categorySlug()).isNull();
		assertThat(limiter.counted).isEmpty();
	}

	@Test
	@DisplayName("with no agent platform at all it is still a search")
	void noPlatformIsStillASearch() {
		// What a deployment with no API key runs: ModelsOnlyWhenConfigured has
		// excluded the platform entirely, so the bean does not exist.
		var understood = build(null).of(asking("balayage"), CALLER);

		assertThat(understood.request().radiusMetres()).isEqualTo(5000);
		assertThat(limiter.counted).isEmpty();
	}

	@Test
	@DisplayName("an empty box is not a question")
	void blankIsNotCounted() {
		assertThat(understanding.of(asking("   "), CALLER).summary()).isNull();
		assertThat(limiter.counted).isEmpty();
	}

	@Test
	@DisplayName("something too long to be a search does not spend the caller's window")
	void overLongIsRefusedBeforeCounting() {
		// The ordering claim. If the length check ran after the counter, a script
		// posting megabytes we never send anywhere would still exhaust the window
		// of everyone behind the same address — an office, a carrier NAT — using
		// input that costs us nothing.
		var understood = understanding.of(asking("x".repeat(201)), CALLER);

		assertThat(understood.ignored()).containsExactly("that is longer than we read");
		assertThat(limiter.counted).isEmpty();
	}

	@Test
	@DisplayName("a real question is counted against the address that asked it")
	void countsBySocketAddress() {
		understanding.of(asking("balayage på lördag"), CALLER);

		assertThat(limiter.counted).containsExactly("search:ask:" + CALLER);
	}

	@Test
	@DisplayName("over the limit is a search without an interpretation, not an error")
	void overTheLimitStillSearches() {
		// Not a 429. The bill is what is being protected, and the customer is
		// still owed the salons — they simply get them unfiltered.
		limiter.refuse("search:ask:" + CALLER);

		var understood = understanding.of(asking("balayage på lördag"), CALLER);

		assertThat(understood.request().categorySlug()).isNull();
		assertThat(understood.request().partOfDay()).isEqualTo(SearchPort.PartOfDay.ANY);
		assertThat(understood.ignored()).containsExactly("this is everything nearby");
	}

	@Test
	@DisplayName("a platform that throws is a search too")
	void aFailingPlatformIsStillASearch() {
		// The mock has no models, no process runner and no goals, so invoking it
		// fails — which is the point. Every provider outage, timeout and refusal
		// arrives here as a RuntimeException and has to come out as results.
		var understood = understanding.of(asking("balayage på lördag"), CALLER);

		assertThat(understood.request().radiusMetres()).isEqualTo(5000);
		assertThat(understood.request().limit()).isEqualTo(20);
		assertThat(understood.ignored())
			.containsExactly("we could not read that, so this is everything nearby");
	}

	@Test
	@DisplayName("a checked ExecutionException is a search too, which it once was not")
	void aCheckedFailureIsStillASearch() {
		// The regression. Embabel is Kotlin and throws this — checked — straight
		// through a signature that declares nothing, so the original
		// catch (RuntimeException) compiled, read as thorough, and let it past.
		// A bad API key produced HTTP 500 from the one endpoint whose whole
		// design is that it cannot fail. Found by running it with a bogus key,
		// not by reading it.
		var throwing = tune(new Throwing(
			providerOf(platform), gate, limiter,
			new ExecutionException(new IllegalStateException("401 - "))));

		var understood = throwing.of(asking("balayage"), CALLER);

		assertThat(understood.request().radiusMetres()).isEqualTo(5000);
		assertThat(understood.ignored())
			.containsExactly("we could not read that, so this is everything nearby");
	}

	/**
	 * Fails the way Embabel does, which is the only way to write this test
	 * without a model on the other end refusing a real request.
	 */
	private static final class Throwing extends QueryUnderstanding {

		private final Exception failure;

		private Throwing(ObjectProvider<AgentPlatform> platform, AiGate gate,
			RateLimiter limiter, Exception failure) {

			super(platform, gate, limiter);
			this.failure = failure;
		}

		@Override
		UnderstoodQuestion interpret(AgentPlatform agents, AskedQuestion question) throws Exception {
			throw failure;
		}

	}

	/**
	 * Records what was counted and refuses whatever it is told to. Faithful in
	 * the one way these tests care about: it is asked exactly when the real one
	 * would be.
	 */
	private static final class CountingLimiter extends RateLimiter {

		private final List<String> counted = new ArrayList<>();
		private final List<String> refused = new ArrayList<>();

		private CountingLimiter() {
			super(null);
		}

		private void refuse(String bucket) {
			refused.add(bucket);
		}

		@Override
		public boolean allow(String bucket, int limit, Duration window) {
			counted.add(bucket);
			return !refused.contains(bucket);
		}

	}

}
