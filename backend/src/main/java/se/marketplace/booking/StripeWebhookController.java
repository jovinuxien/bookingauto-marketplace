package se.marketplace.booking;

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
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

/**
 * Where a suspended sale gets finished.
 *
 * <p>The funnel stops at {@code AWAITING_PAYMENT} because Swish is a push
 * payment and the customer is off in their bank app. This is the other half of
 * that saga, arriving minutes later in a different request.
 *
 * <p>Three rules, each learned from the way payment webhooks actually behave.
 *
 * <p><strong>Verify before reading.</strong> This endpoint moves money-adjacent
 * state and is exposed to the internet. An unsigned body is not a malformed
 * request; it is someone claiming a payment succeeded.
 *
 * <p><strong>Record before acting.</strong> Same discipline as Cal's webhooks —
 * when someone later asks whether Stripe ever told us, the answer should be a
 * row rather than a shrug.
 *
 * <p><strong>Assume redelivery and reordering.</strong> Stripe retries on any
 * non-2xx and does not promise order. {@code event_id} is unique in the
 * database, so a repeat is a no-op instead of a second booking; and the funnel
 * ignores transitions out of states it has already left.
 */
@RestController
@RequestMapping("/internal/stripe/webhook")
class StripeWebhookController {

	private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

	private final BookingFunnel funnel;
	private final NamedParameterJdbcTemplate jdbc;
	private final ObjectMapper mapper = new ObjectMapper();

	@Value("${marketplace.payments.stripe.webhook-secret:}")
	private String secret;

	StripeWebhookController(BookingFunnel funnel, NamedParameterJdbcTemplate jdbc) {
		this.funnel = funnel;
		this.jdbc = jdbc;
	}

	@PostMapping
	ResponseEntity<String> receive(
		@RequestBody String body,
		@RequestHeader(value = "Stripe-Signature", required = false) String signature) {

		if (!verified(body, signature)) {
			record("unknown:" + System.nanoTime(), "REJECTED", body, "invalid signature");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
		}

		JsonNode event;
		try {
			event = mapper.readTree(body);
		}
		catch (Exception e) {
			// 200 on purpose: a body we cannot parse will not parse on retry
			// either, and the receipt is the record.
			record("unparseable:" + System.nanoTime(), "UNKNOWN", body, "unparseable: " + e);
			return ResponseEntity.ok("recorded");
		}

		String eventId = event.path("id").asText("");
		String type = event.path("type").asText("unknown");

		if (!record(eventId, type, body, null)) {
			// Already seen. Stripe redelivers freely, and processing twice would
			// mean two bookings or two refunds.
			log.debug("duplicate stripe event {}", eventId);
			return ResponseEntity.ok("duplicate");
		}

		JsonNode intent = event.path("data").path("object");
		String intentId = intent.path("id").asText("");

		try {
			switch (type) {
				case "payment_intent.succeeded" -> funnel.paymentSucceeded(intentId, intentId);
				case "payment_intent.payment_failed", "payment_intent.canceled" ->
					funnel.paymentFailed(intentId,
						intent.path("last_payment_error").path("message").asText("payment failed"));
				default -> log.debug("ignoring stripe event {}", type);
			}
		}
		catch (RuntimeException e) {
			// Left unprocessed with the error recorded, and answered 500 so
			// Stripe retries. A booking that failed to complete is exactly the
			// case where a retry is wanted.
			log.error("could not process stripe event {}", eventId, e);
			jdbc.update("UPDATE stripe_receipt SET error = :err WHERE event_id = :id",
				new MapSqlParameterSource().addValue("err", e.toString()).addValue("id", eventId));
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("retry");
		}

		jdbc.update("UPDATE stripe_receipt SET processed_at = now() WHERE event_id = :id",
			new MapSqlParameterSource("id", eventId));

		return ResponseEntity.ok("recorded");
	}

	/** @return false if this event has already been recorded */
	private boolean record(String eventId, String type, String body, String error) {
		int written = jdbc.update("""
			INSERT INTO stripe_receipt (event_id, event_type, payload, error)
			VALUES (:id, :type, CAST(:payload AS jsonb), :error)
			ON CONFLICT (event_id) DO NOTHING
			""",
			new MapSqlParameterSource()
				.addValue("id", eventId)
				.addValue("type", type)
				.addValue("payload", body)
				.addValue("error", error));
		return written > 0;
	}

	private boolean verified(String body, String signature) {
		if (secret == null || secret.isBlank()) {
			log.warn("stripe webhook secret is not set — accepting unverified deliveries");
			return true;
		}
		if (signature == null || signature.isBlank()) {
			return false;
		}
		try {
			// Stripe's own verifier: constant time, and it checks the timestamp
			// so a captured body cannot be replayed indefinitely.
			Webhook.constructEvent(body, signature, secret);
			return true;
		}
		catch (SignatureVerificationException e) {
			return false;
		}
		catch (Exception e) {
			log.error("could not verify stripe signature", e);
			return false;
		}
	}

}
