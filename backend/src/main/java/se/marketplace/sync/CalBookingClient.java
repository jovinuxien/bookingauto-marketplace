package se.marketplace.sync;

import java.net.http.HttpClient;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Writes bookings to Cal.
 *
 * <p>Reserve goes to the web image's public booking endpoint, which is the same
 * call Cal's own booking page makes. Confirm and cancel go to {@code api-v2},
 * which is not deployed here — see {@link CalBookingPort} for the probe results
 * and why that is not a shortcut we can take.
 *
 * <p>Rather than pretend, both throw {@link CalBookingPort.CalUnavailable} until
 * {@code marketplace.cal.api-v2-url} is configured. That routes failed attempts
 * to {@code NEEDS_ATTENTION} — a person, an alert and a stranded reservation
 * they can see — instead of silently leaving a slot blocked with nothing
 * recording why.
 */
@Component
class CalBookingClient implements CalBookingPort {

	private static final Logger log = LoggerFactory.getLogger(CalBookingClient.class);

	private final RestClient http;
	private final String baseUrl;
	private final String apiV2Url;
	private final String apiKey;

	CalBookingClient(
		@Value("${marketplace.cal.base-url:http://localhost:3000}") String baseUrl,
		@Value("${marketplace.cal.api-v2-url:}") String apiV2Url,
		@Value("${marketplace.cal.api-key:}") String apiKey) {

		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.apiV2Url = apiV2Url == null ? "" : apiV2Url.trim();
		this.apiKey = apiKey == null ? "" : apiKey.trim();

		// HTTP/1.1 for the same reason as CalSlotsClient: the JDK client's
		// default h2c upgrade makes Cal's Next.js server close the connection.
		this.http = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
			.build();
	}

	@Override
	public Reservation reserve(ReservationRequest request) {
		String payload = """
			{"eventTypeId":%d,"start":"%s","timeZone":"%s","language":"en","metadata":{},
			 "responses":{"name":%s,"email":%s,"location":{"value":"","optionValue":""}}}"""
			.formatted(
				request.calEventTypeId(),
				request.start(),
				request.timeZone(),
				quote(request.customerName()),
				quote(request.customerEmail()));

		JsonNode body;
		try {
			body = http.post()
				.uri(baseUrl + "/api/book/event")
				.header("Content-Type", "application/json")
				.body(payload)
				.retrieve()
				.body(JsonNode.class);
		}
		catch (RestClientException e) {
			// Cal answers a taken slot with a 4xx and a message. That is a
			// refusal, not a fault: the index was stale and the funnel has a
			// handled path for it. Anything else means we do not know what
			// happened, and not knowing must never lead to a charge.
			String message = e.getMessage() == null ? "" : e.getMessage();
			if (message.contains("no_available_users_found_error")
				|| message.contains("booking_seats_full_error")
				|| message.contains("Attempting to book a meeting in the past")
				|| message.contains("already has booking at this time")) {
				throw new CalRefused("cal refused the slot: " + message);
			}
			throw new CalUnavailable("could not reach cal to reserve: " + message, e);
		}

		if (body == null || body.path("uid").isMissingNode()) {
			throw new CalUnavailable("cal returned no booking uid");
		}

		return new Reservation(
			body.path("uid").asText(),
			Instant.parse(body.path("startTime").asText()),
			Instant.parse(body.path("endTime").asText()),
			body.path("eventTypeId").asLong(),
			body.path("status").asText("unknown"));
	}

	@Override
	public void confirm(String calBookingUid) {
		requireApiV2("confirm");

		try {
			http.post()
				.uri(apiV2Url + "/v2/bookings/" + calBookingUid + "/confirm")
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.body("{}")
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new CalUnavailable("could not confirm booking " + calBookingUid, e);
		}
	}

	@Override
	public void cancel(String calBookingUid, String reason) {
		requireApiV2("cancel");

		try {
			http.post()
				.uri(apiV2Url + "/v2/bookings/" + calBookingUid + "/cancel")
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.body("{\"cancellationReason\":" + quote(reason) + "}")
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new CalUnavailable("could not cancel booking " + calBookingUid, e);
		}
	}

	/**
	 * Refuses rather than guesses.
	 *
	 * <p>The temptation with an undeployed dependency is to no-op and move on.
	 * Here that would mean reporting a cancellation that never happened, leaving
	 * a pending booking blocking a real slot with the attempt marked clean. An
	 * exception routes it to NEEDS_ATTENTION, which is exactly what it is.
	 */
	private void requireApiV2(String operation) {
		if (apiV2Url.isBlank()) {
			log.error("cannot {} — cal api-v2 is not configured; "
				+ "the web image does not expose an authenticated {} surface", operation, operation);
			throw new CalUnavailable(
				"cal api-v2 is not deployed, so " + operation + " is unavailable "
					+ "(set marketplace.cal.api-v2-url)");
		}
	}

	/** Minimal JSON string escaping; these values reach Cal and come back in emails. */
	private static String quote(String value) {
		if (value == null) {
			return "\"\"";
		}
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"'  -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default   -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

}
