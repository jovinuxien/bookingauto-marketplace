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

	/**
	 * Required by api-v2, and required to be exactly this.
	 *
	 * <p>Cal's own documentation for the header says it plainly: "If not set to
	 * this value, the endpoint will default to an older version." The older
	 * bookings controller has a cancel route and <em>no confirm route at all</em>,
	 * so omitting this header does not fail loudly — it silently routes to an API
	 * where half of what we need is a 404.
	 */
	private static final String API_VERSION = "2024-08-13";

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
				.header("cal-api-version", API_VERSION)
				.header("Content-Type", "application/json")
				.body("{}")
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new CalUnavailable("could not confirm booking " + calBookingUid, e);
		}
	}

	/**
	 * Cancels, deliberately <strong>without</strong> an Authorization header.
	 *
	 * <p>This looks like an omission and is the opposite. Cal guards cancel with
	 * {@code OptionalApiAuthGuard}: no credentials at all is accepted and
	 * performs the attendee-style cancellation, which is exactly what we want
	 * for a booking we created on the customer's behalf. But a token that
	 * <em>is</em> supplied gets validated — and validating an api key begins
	 * with a {@code CALCOM_LICENSE_KEY} check, before it even looks at the key.
	 *
	 * <p>So on an unlicensed deployment, sending our key turns a working call
	 * into a 401. Measured, not guessed: the identical request cancels the
	 * booking with the header removed. Sending credentials is strictly worse
	 * than sending none, which is not a sentence anyone expects to write, and is
	 * why this method does not take the shortcut of reusing the confirm setup.
	 *
	 * <p>The trade-off is that we cancel as the attendee rather than as the
	 * host. For releasing a reservation whose sale failed, that is the correct
	 * role anyway.
	 */
	@Override
	public void cancel(String calBookingUid, String reason) {
		requireApiV2("cancel");

		try {
			http.post()
				.uri(apiV2Url + "/v2/bookings/" + calBookingUid + "/cancel")
				.header("cal-api-version", API_VERSION)
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
