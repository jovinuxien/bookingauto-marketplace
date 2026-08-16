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

	ConsoleController(ConsoleRepository repository) {
		this.repository = repository;
	}

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
