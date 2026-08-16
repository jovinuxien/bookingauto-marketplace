package se.marketplace.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Receives Cal's webhooks.
 *
 * <p>Every delivery is written to {@code webhook_receipt} <em>before</em> it is
 * acted on. Webhooks go missing, and when someone later asks whether a booking
 * was ever announced, the answer should be a row rather than a shrug.
 *
 * <p>The handler does not recompute availability inline. It marks the affected
 * service stale and lets the reconciler coalesce the work: a salon taking twenty
 * bookings in a minute would otherwise become twenty calls back to Cal for an
 * answer that is identical after the last one.
 */
@RestController
@RequestMapping("/internal/cal/webhook")
class CalWebhookController {

	private static final Logger log = LoggerFactory.getLogger(CalWebhookController.class);

	private final NamedParameterJdbcTemplate jdbc;
	private final AvailabilityIndexRepository repository;
	private final ObjectMapper mapper = new ObjectMapper();

	@Value("${marketplace.cal.webhook-secret:}")
	private String secret;

	CalWebhookController(NamedParameterJdbcTemplate jdbc, AvailabilityIndexRepository repository) {
		this.jdbc = jdbc;
		this.repository = repository;
	}

	@PostMapping
	ResponseEntity<String> receive(
		@RequestBody String body,
		@RequestHeader(value = "X-Cal-Signature-256", required = false) String signature) {

		if (!signatureValid(body, signature)) {
			// Recorded even when rejected. A run of these is either a
			// misconfigured secret or someone probing, and both are worth
			// being able to see.
			record("REJECTED", body, "invalid signature");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
		}

		String triggerEvent = "UNKNOWN";
		Long calEventTypeId = null;

		try {
			JsonNode json = mapper.readTree(body);
			triggerEvent = json.path("triggerEvent").asText("UNKNOWN");
			JsonNode id = json.path("payload").path("eventTypeId");
			if (!id.isMissingNode() && id.canConvertToLong()) {
				calEventTypeId = id.asLong();
			}
		}
		catch (Exception e) {
			record(triggerEvent, body, "unparseable: " + e);
			// 200 on purpose. A body we cannot read is our problem, and
			// returning an error only makes Cal retry something that will fail
			// identically. The receipt is the record.
			return ResponseEntity.ok("recorded");
		}

		long receiptId = record(triggerEvent, body, null);

		if (calEventTypeId != null) {
			Long serviceId = repository.serviceIdOfCalEventType(calEventTypeId);
			if (serviceId != null) {
				repository.markStale(serviceId);
				log.debug("{} marked service {} stale", triggerEvent, serviceId);
			}
		}

		jdbc.update("UPDATE webhook_receipt SET processed_at = now() WHERE id = :id",
			new MapSqlParameterSource("id", receiptId));

		return ResponseEntity.ok("recorded");
	}

	private long record(String event, String body, String error) {
		var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
		jdbc.update("""
			INSERT INTO webhook_receipt (cal_event, payload, error)
			VALUES (:event, CAST(:payload AS jsonb), :error)
			""",
			new MapSqlParameterSource()
				.addValue("event", event)
				.addValue("payload", body)
				.addValue("error", error),
			keys, new String[] { "id" });
		Number id = keys.getKey();
		return id == null ? -1 : id.longValue();
	}

	private boolean signatureValid(String body, String signature) {
		if (secret == null || secret.isBlank()) {
			// Unsecured only when explicitly left unconfigured, and loudly.
			log.warn("cal webhook secret is not set — accepting unverified deliveries");
			return true;
		}
		if (signature == null || signature.isBlank()) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(expected.length * 2);
			for (byte b : expected) {
				hex.append(String.format("%02x", b));
			}
			// Constant time: a comparison that returns early leaks the prefix.
			return MessageDigest.isEqual(
				hex.toString().getBytes(StandardCharsets.UTF_8),
				signature.trim().getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			log.error("could not verify webhook signature", e);
			return false;
		}
	}

}
