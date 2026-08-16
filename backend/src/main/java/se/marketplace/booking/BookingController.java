package se.marketplace.booking;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout.
 *
 * <p>The status codes are chosen so a client can tell the three outcomes apart
 * without parsing prose. A refused slot is a <em>409</em>, not a 500: the index
 * was stale, the funnel handled it, and the right client behaviour is to offer
 * other times rather than to retry. {@code NEEDS_ATTENTION} is a 500 because
 * something is genuinely stuck and someone has to look.
 */
@RestController
@RequestMapping("/api/bookings")
class BookingController {

	private final BookingFunnel funnel;

	BookingController(BookingFunnel funnel) {
		this.funnel = funnel;
	}

	@PostMapping
	ResponseEntity<BookingFunnel.Outcome> book(@RequestBody Request request) {
		BookingFunnel.Outcome outcome = funnel.book(new BookingFunnel.BookingRequest(
			request.idempotencyKey(),
			request.serviceId(),
			Instant.parse(request.slotStart()),
			request.customerName(),
			request.customerEmail()));

		HttpStatus status = switch (outcome.state()) {
			case CONFIRMED -> HttpStatus.CREATED;
			case REFUSED, VERIFY_FAILED -> HttpStatus.CONFLICT;
			case CHARGE_FAILED -> HttpStatus.PAYMENT_REQUIRED;
			case NEEDS_ATTENTION -> HttpStatus.INTERNAL_SERVER_ERROR;
			default -> HttpStatus.OK;
		};

		return ResponseEntity.status(status).body(outcome);
	}

	record Request(
		String idempotencyKey,
		long serviceId,
		String slotStart,
		String customerName,
		String customerEmail
	) {}

}
