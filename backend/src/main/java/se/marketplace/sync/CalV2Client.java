package se.marketplace.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Talks to Cal's v2 API.
 *
 * <p><strong>This requires the v2 API to be deployed.</strong> It is not part of
 * the {@code calcom/cal.com} Docker image, which ships {@code apps/web} only —
 * {@code /api/v2/*} answers 500 against it. The v2 API is a separate NestJS
 * application, {@code @calcom/api-v2}, with its own Dockerfile in the Cal
 * repository. Until it is running, every call here fails, the reconciler logs
 * each failure and leaves the service stale, and the index simply does not
 * advance. That is the intended degradation: stale and visibly so, rather than
 * confidently wrong.
 */
@Component
class CalV2Client implements CalPort {

	private static final Logger log = LoggerFactory.getLogger(CalV2Client.class);

	private final RestClient http;

	CalV2Client(
		@Value("${marketplace.cal.base-url:http://localhost:3000}") String baseUrl,
		@Value("${marketplace.cal.api-key:}") String apiKey) {

		this.http = RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader("Authorization", apiKey.isBlank() ? "" : "Bearer " + apiKey)
			.build();
	}

	@Override
	public List<Slot> slots(long calEventTypeId, Instant from, Instant to) {
		JsonNode body = http.get()
			.uri(uriBuilder -> uriBuilder
				.path("/api/v2/slots")
				.queryParam("eventTypeId", calEventTypeId)
				.queryParam("start", from.toString())
				.queryParam("end", to.toString())
				.build())
			.retrieve()
			.body(JsonNode.class);

		return parse(body);
	}

	/**
	 * Cal returns slots grouped by date. Parsed defensively: a shape change
	 * upstream should surface as zero slots and a warning here, in the one
	 * module that is supposed to know Cal's shape, rather than as a
	 * deserialisation error somewhere further in.
	 */
	private List<Slot> parse(JsonNode body) {
		List<Slot> out = new ArrayList<>();

		if (body == null) {
			return out;
		}

		JsonNode data = body.has("data") ? body.get("data") : body;

		if (!data.isObject()) {
			log.warn("unexpected slots payload shape: {}", data.getNodeType());
			return out;
		}

		for (Map.Entry<String, JsonNode> day : data.properties()) {
			for (JsonNode slot : day.getValue()) {
				JsonNode start = slot.path("start");
				if (start.isMissingNode()) {
					continue;
				}
				try {
					Instant startAt = Instant.parse(start.asText());
					JsonNode end = slot.path("end");
					Instant endAt = end.isMissingNode()
						? startAt
						: Instant.parse(end.asText());
					out.add(new Slot(startAt, endAt));
				}
				catch (Exception e) {
					log.warn("unparseable slot {}: {}", slot, e.toString());
				}
			}
		}

		return out;
	}

}
