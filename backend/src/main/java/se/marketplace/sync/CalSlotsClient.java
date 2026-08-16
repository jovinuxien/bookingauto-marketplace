package se.marketplace.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads free slots from Cal.
 *
 * <h2>Why this uses a tRPC endpoint and not the v2 REST API</h2>
 *
 * <p>The architecture this project inherited assumes {@code /v2/*} throughout.
 * That API is real, but it is <strong>not in the image we run</strong>:
 * {@code calcom/cal.com} ships {@code apps/web} only, and {@code /api/v2/*}
 * answers 500 against it. {@code @calcom/api-v2} is a separate NestJS
 * application, there is no published image for it (the {@code calcom} Docker Hub
 * org publishes {@code cal.com}, {@code cal.diy} and {@code pgbouncer}, nothing
 * else), so using it means building the monorepo ourselves and operating a
 * second service.
 *
 * <p>{@code /api/trpc/slots/getSchedule} is served by the web image we already
 * run, and it is the exact call Cal's own public booking page makes. So it
 * returns, by construction, what a customer would see — which is precisely what
 * the availability index is supposed to mirror.
 *
 * <p>The cost is honest: this is an <em>internal</em> API with no compatibility
 * promise, and a Cal upgrade may change it without notice. Two things make that
 * acceptable. It is contained — this class is the only thing in the system that
 * knows Cal's wire shape. And it fails visibly: a shape change yields zero slots
 * and a warning here, the reconciler leaves the service stale, and
 * {@code computed_at} shows the index standing still. Stale and visibly so,
 * rather than confidently wrong.
 *
 * <p>Revisit if we end up building {@code api-v2} for booking writes anyway —
 * reads should then move with them.
 */
@Component
class CalSlotsClient implements CalPort {

	private static final Logger log = LoggerFactory.getLogger(CalSlotsClient.class);

	/**
	 * Cal computes day boundaries in this zone when grouping slots. It must
	 * match what the reconciler buckets by, or a slot near midnight lands on
	 * one day here and the other day there.
	 */
	private static final String QUERY_ZONE = "Europe/Stockholm";

	private final RestClient http;

	private final String baseUrl;

	CalSlotsClient(@Value("${marketplace.cal.base-url:http://localhost:3000}") String baseUrl) {
		// Kept as a field and prepended by hand below. RestClient applies a
		// configured baseUrl to a String uri, but not to a java.net.URI — a
		// schemeless one throws "URI with undefined scheme" instead — and the
		// String overload is not usable here, see slots().
		this.baseUrl = baseUrl.endsWith("/")
			? baseUrl.substring(0, baseUrl.length() - 1)
			: baseUrl;

		// Pinned to HTTP/1.1. RestClient picks the JDK HttpClient, which
		// defaults to HTTP/2 and so opens with a cleartext h2c upgrade; Cal's
		// Next.js server answers that by closing the connection, and the JDK
		// reports it as "HTTP/1.1 header parser received no bytes" — a
		// connection-level error that looks nothing like a protocol
		// negotiation problem. The same URL over curl succeeds, which is what
		// makes this worth a comment rather than a one-line builder call.
		this.http = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
	}

	@Override
	public List<Slot> slots(long calEventTypeId, Instant from, Instant to) {
		// tRPC takes its argument as a superjson envelope in one query
		// parameter. Both the URI and the encoding are built by hand: the value
		// contains braces, and every convenience API here treats braces as URI
		// template placeholders — UriBuilder and RestClient's String uri
		// overload alike — which mangles the request before it is sent. A
		// pre-encoded java.net.URI is the one form nothing tries to expand.
		String input = """
			{"json":{"eventTypeId":%d,"startTime":"%s","endTime":"%s","timeZone":"%s"}}"""
			.formatted(calEventTypeId, from, to, QUERY_ZONE);

		URI uri = URI.create(
			baseUrl + "/api/trpc/slots/getSchedule?input="
				+ URLEncoder.encode(input, StandardCharsets.UTF_8));

		JsonNode body = http.get().uri(uri).retrieve().body(JsonNode.class);

		return parse(calEventTypeId, body);
	}

	/**
	 * Unwraps {@code result.data.json.slots}, a map of local date to an array of
	 * {@code {"time": "<ISO instant>"}}.
	 *
	 * <p>Parsed defensively at every hop. An upstream shape change should surface
	 * as zero slots and a warning in the one module that is meant to know Cal's
	 * shape, not as a deserialisation failure somewhere further in.
	 *
	 * <p>A tRPC error is an HTTP 200 with an {@code error} member, so it has to
	 * be checked for explicitly — and it is worth logging loudly, because the
	 * failure that actually bit during development (an event type missing from
	 * Cal's {@code _user_eventtype} join table) returned success with an empty
	 * slot map, which is indistinguishable from a fully booked salon.
	 */
	private List<Slot> parse(long calEventTypeId, JsonNode body) {
		List<Slot> out = new ArrayList<>();

		if (body == null) {
			log.warn("empty slots response for cal event type {}", calEventTypeId);
			return out;
		}

		if (body.has("error")) {
			log.warn("cal refused slots for event type {}: {}",
				calEventTypeId, body.path("error").path("json").path("message").asText());
			return out;
		}

		JsonNode slots = body.path("result").path("data").path("json").path("slots");

		if (!slots.isObject()) {
			log.warn("unexpected slots payload for cal event type {}: {}",
				calEventTypeId, slots.getNodeType());
			return out;
		}

		for (Map.Entry<String, JsonNode> day : slots.properties()) {
			for (JsonNode slot : day.getValue()) {
				JsonNode time = slot.path("time");
				if (time.isMissingNode()) {
					continue;
				}
				try {
					out.add(new Slot(Instant.parse(time.asText())));
				}
				catch (Exception e) {
					log.warn("unparseable slot {}: {}", slot, e.toString());
				}
			}
		}

		return out;
	}

}
