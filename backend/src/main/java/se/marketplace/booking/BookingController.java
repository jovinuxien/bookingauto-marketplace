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
	ResponseEntity<?> book(@RequestBody Request request) {
		BookingFunnel.Outcome outcome;
		try {
			outcome = funnel.book(new BookingFunnel.BookingRequest(
			request.idempotencyKey(),
			request.serviceId(),
			Instant.parse(request.slotStart()),
			request.customerName(),
			request.customerEmail(),
			request.registrationNumber(),
			request.quotedPriceMinor(),
			request.addonIds(),
			request.channel()));
		}
		catch (BookingFunnel.PriceChanged e) {
			// 409: nothing was reserved; the body carries the price as it is
			// now so the page can show it and ask again.
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new PriceNow(e.priceMinor(), e.currency()));
		}
		catch (IllegalArgumentException e) {
			// The request itself was wrong -- no such service, or a workshop's
			// service with no registration number -- and nothing was reserved.
			// 400 with the reason, so the form can say which field.
			return ResponseEntity.badRequest().body(e.getMessage());
		}

		HttpStatus status = switch (outcome.state()) {
			case CONFIRMED -> HttpStatus.CREATED;
			// Accepted, not created. The slot is held and a PaymentIntent
			// exists, but the customer still has to approve it in their bank
			// app — so the client's job is to take the clientSecret and finish,
			// not to show a confirmation.
			case AWAITING_PAYMENT -> HttpStatus.ACCEPTED;
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
		String customerEmail,
		/** Only for services whose category asks; ignored otherwise. */
		String registrationNumber,
		/** The price the page showed. Optional; when sent, a changed price is a 409. */
		Integer quotedPriceMinor,
		/** Add-ons ticked at checkout. */
		java.util.List<Long> addonIds,
		/** Where the checkout was opened from. Reporting only; unknown values become 'marketplace'. */
		String channel
	) {}

	record PriceNow(int priceMinor, String currency) {}

}
