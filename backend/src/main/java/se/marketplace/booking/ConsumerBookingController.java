package se.marketplace.booking;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A customer's own booking.
 *
 * <p>Two calls, both anonymous, both authorised by the token in the body rather
 * than by a session. There is no consumer account to sign in to — see ADR 0014.
 *
 * <h2>Why a POST reads something</h2>
 *
 * <p>Looking a booking up is a read and ought to be a GET, and is not, because
 * a GET would put a bearer credential in a URL. Tokens in URLs end up in access
 * logs, in proxy logs, in browser history and in {@code Referer} headers, and
 * the whole security of this design is that the token is only ever in the
 * customer's mailbox. {@code signup} puts its verification token in a POST body
 * for the same reason.
 *
 * <p>The link a customer clicks does carry the token as a query parameter,
 * because an email link has no other way to carry anything. That lands on the
 * SPA, which reads it and posts it here; the token then stops travelling.
 */
@RestController
@RequestMapping("/api/bookings")
class ConsumerBookingController {

	private final BookingCancellation cancellation;

	private final BookingReviews reviews;

	ConsumerBookingController(BookingCancellation cancellation, BookingReviews reviews) {
		this.cancellation = cancellation;
		this.reviews = reviews;
	}

	@PostMapping("/review")
	ResponseEntity<?> review(@RequestBody ReviewRequest request, HttpServletRequest http) {
		return switch (reviews.submit(request.token(), request.rating(), request.comment(), clientIp(http))) {
			case BookingReviews.Saved saved -> ResponseEntity.ok(saved);
			// 409 for both: the page already knows which, from the booking it shows.
			case BookingReviews.AlreadyReviewed ignored -> ResponseEntity.status(HttpStatus.CONFLICT).build();
			case BookingReviews.NotYet ignored -> ResponseEntity.status(HttpStatus.CONFLICT).build();
			case BookingReviews.Invalid invalid -> ResponseEntity.badRequest().body(invalid.message());
			case BookingReviews.Unknown ignored -> ResponseEntity.notFound().build();
			case BookingReviews.Throttled ignored -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
		};
	}

	@PostMapping("/lookup")
	ResponseEntity<?> lookup(@RequestBody TokenRequest request, HttpServletRequest http) {
		return answer(cancellation.lookup(request.token(), clientIp(http)));
	}

	@PostMapping("/cancel")
	ResponseEntity<?> cancel(@RequestBody TokenRequest request, HttpServletRequest http) {
		return answer(cancellation.cancel(request.token(), clientIp(http)));
	}

	/**
	 * The status codes carry the outcome, so the page does not have to read
	 * prose to know what happened.
	 */
	private static ResponseEntity<?> answer(BookingCancellation.Result result) {
		return switch (result) {
			case BookingCancellation.Found found ->
				ResponseEntity.ok(found.booking());

			case BookingCancellation.Cancelled cancelled ->
				ResponseEntity.ok(cancelled.booking());

			// 404 for a bad token and for a booking that does not exist alike.
			// The two are told apart nowhere the caller can see.
			case BookingCancellation.Unknown ignored ->
				ResponseEntity.notFound().build();

			// 409: the booking is real and the request was legitimate, and the
			// moment for it has passed. Not a 400 — nothing about the request
			// was malformed, and the body carries the booking so the page can
			// show what it is refusing to cancel.
			case BookingCancellation.TooLate late ->
				ResponseEntity.status(HttpStatus.CONFLICT).body(late.booking());

			// 502: Cal would not release the slot. The failure is upstream and
			// the right thing for the customer to do is try again shortly.
			case BookingCancellation.Unavailable unavailable ->
				ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(unavailable.booking());

			// 200, and this is deliberate. The cancellation the customer asked
			// for did happen; what did not is the refund, which is ours to
			// chase and is flagged. Answering with an error would tell them the
			// appointment might still stand, which is the one thing that is
			// certainly not true.
			case BookingCancellation.RefundStuck stuck ->
				ResponseEntity.ok(stuck.booking());

			case BookingCancellation.Throttled ignored ->
				ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
		};
	}

	/** The socket address, never {@code X-Forwarded-For} — see ADR 0011. */
	private static String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

	record TokenRequest(String token) {}

	record ReviewRequest(String token, int rating, String comment) {}

}
