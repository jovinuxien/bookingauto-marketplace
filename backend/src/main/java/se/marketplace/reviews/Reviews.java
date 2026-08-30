package se.marketplace.reviews;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** The module's one door. */
@Service
public class Reviews {

	private final ReviewRepository repository;

	Reviews(ReviewRepository repository) {
		this.repository = repository;
	}

	/**
	 * Records a verdict. Whether the caller is entitled to give one is the
	 * caller's problem — the booking module proves the customer — and the
	 * only refusal here is a second review of the same booking.
	 *
	 * @throws IllegalArgumentException for a rating outside 1–5 or a comment over 1 000 characters
	 */
	public boolean submit(long bookingId, long providerId, int rating, String comment) {
		if (rating < 1 || rating > 5) {
			throw new IllegalArgumentException("Betyget är 1 till 5.");
		}
		String trimmed = comment == null || comment.isBlank() ? null : comment.trim();
		if (trimmed != null && trimmed.length() > 1000) {
			throw new IllegalArgumentException("Högst 1 000 tecken.");
		}
		return repository.insert(bookingId, providerId, rating, trimmed);
	}

	public Optional<Review> forBooking(long bookingId) {
		return repository.forBooking(bookingId);
	}

	public RatingSummary summaryFor(long providerId) {
		return repository.summaryFor(providerId);
	}

	public List<Review> recentFor(long providerId, int limit) {
		return repository.recentFor(providerId, Math.min(limit, 50));
	}

	Optional<Long> providerIdOf(String slug) {
		return repository.providerIdOf(slug);
	}

	/** "Anna Andersson" → "Anna A."; "Anna" → "Anna"; blank → "Kund". */
	static String author(String customerName) {
		if (customerName == null || customerName.isBlank()) {
			return "Kund";
		}
		String[] parts = customerName.trim().split("\\s+");
		if (parts.length == 1) {
			return parts[0];
		}
		return parts[0] + " " + parts[parts.length - 1].substring(0, 1).toUpperCase() + ".";
	}

}
