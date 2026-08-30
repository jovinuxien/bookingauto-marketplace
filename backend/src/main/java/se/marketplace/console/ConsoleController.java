package se.marketplace.console;

import java.time.Instant;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a salon sees.
 *
 * <p>Every method scopes to {@code principal.providerId()} and none accepts a
 * provider id from the caller. That is the whole access-control model for this
 * surface, and keeping it in one sentence is deliberate: the moment an endpoint
 * takes an id from a path or a query, every one of them needs an ownership
 * check and one of them will eventually be missing it.
 */
@RestController
@RequestMapping("/api/console")
class ConsoleController {

	private final ConsoleRepository repository;
	private final se.marketplace.booking.ProviderCancellation cancellation;
	private final se.marketplace.booking.BookingMessages messages;

	ConsoleController(ConsoleRepository repository,
		se.marketplace.booking.ProviderCancellation cancellation,
		se.marketplace.booking.BookingMessages messages) {
		this.repository = repository;
		this.cancellation = cancellation;
		this.messages = messages;
	}

	@GetMapping("/bookings/{id}/messages")
	ResponseEntity<?> messageThread(@AuthenticationPrincipal ConsolePrincipal principal,
		@org.springframework.web.bind.annotation.PathVariable long id) {
		return messages.threadFor(principal.providerId(), id)
			.<ResponseEntity<?>>map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@org.springframework.web.bind.annotation.PostMapping("/bookings/{id}/messages")
	ResponseEntity<?> postMessage(@AuthenticationPrincipal ConsolePrincipal principal,
		@org.springframework.web.bind.annotation.PathVariable long id,
		@org.springframework.web.bind.annotation.RequestBody MessageBody body) {
		return messages.postAsProvider(principal.providerId(), id, body.body())
			.<ResponseEntity<?>>map(result -> switch (result) {
				case se.marketplace.booking.BookingMessages.Posted posted -> ResponseEntity.ok(posted.message());
				case se.marketplace.booking.BookingMessages.Invalid invalid -> ResponseEntity.badRequest().body(invalid.message());
				default -> ResponseEntity.internalServerError().build();
			})
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	record MessageBody(String body) {}

	/**
	 * The salon lets a time go. The refund is unconditional — see
	 * {@link se.marketplace.booking.ProviderCancellation} for why — and the
	 * body says whether the money moved or a human has to move it.
	 */
	@org.springframework.web.bind.annotation.PostMapping("/bookings/{id}/cancel")
	ResponseEntity<?> cancelBooking(@AuthenticationPrincipal ConsolePrincipal principal,
		@org.springframework.web.bind.annotation.PathVariable long id) {
		return switch (cancellation.cancel(principal.providerId(), id)) {
			case se.marketplace.booking.ProviderCancellation.Cancelled c ->
				ResponseEntity.ok(new CancelOutcome("cancelled", c.refunded()));
			case se.marketplace.booking.ProviderCancellation.RefundStuck ignored ->
				ResponseEntity.ok(new CancelOutcome("refund_pending", false));
			case se.marketplace.booking.ProviderCancellation.AlreadyCancelled ignored ->
				ResponseEntity.ok(new CancelOutcome("already_cancelled", false));
			case se.marketplace.booking.ProviderCancellation.TooLate ignored ->
				ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).build();
			case se.marketplace.booking.ProviderCancellation.Unavailable ignored ->
				ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY).build();
			case se.marketplace.booking.ProviderCancellation.Unknown ignored ->
				ResponseEntity.notFound().build();
		};
	}

	record CancelOutcome(String outcome, boolean refunded) {}

	/** The salon's own summary: is it sellable, and what is it owed. */
	@GetMapping("/summary")
	ResponseEntity<ConsoleRepository.Summary> summary(
		@AuthenticationPrincipal ConsolePrincipal principal) {
		return ResponseEntity.ok(repository.summary(principal.providerId()));
	}

	/**
	 * Upcoming bookings.
	 *
	 * <p>Ours, not Cal's. The salon's calendar shows the appointment; this shows
	 * what was sold, for how much, and what the platform kept — which is the
	 * question the console exists to answer and the one Cal cannot.
	 */
	@GetMapping("/bookings")
	ResponseEntity<List<ConsoleRepository.BookingRow>> bookings(
		@AuthenticationPrincipal ConsolePrincipal principal,
		@RequestParam(defaultValue = "30") int days) {
		return ResponseEntity.ok(repository.upcomingBookings(principal.providerId(), days));
	}

	/**
	 * Attempts that need a person.
	 *
	 * <p>Surfaced to the salon rather than kept in an operations dashboard,
	 * because a stranded reservation blocks <em>their</em> calendar and a failed
	 * refund is <em>their</em> customer waiting for money.
	 */
	@GetMapping("/attention")
	ResponseEntity<List<ConsoleRepository.AttentionRow>> attention(
		@AuthenticationPrincipal ConsolePrincipal principal) {
		return ResponseEntity.ok(repository.needingAttention(principal.providerId()));
	}

	record Health(Instant checkedAt) {}

}
