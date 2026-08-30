package se.marketplace.reviews;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A provider's reviews, for its page. Public; there is nothing in them that is not meant to be read. */
@RestController
@RequestMapping("/api/reviews")
class ReviewController {

	private final Reviews reviews;

	ReviewController(Reviews reviews) {
		this.reviews = reviews;
	}

	@GetMapping("/{providerSlug}")
	ResponseEntity<ProviderReviews> forProvider(@PathVariable String providerSlug) {
		return reviews.providerIdOf(providerSlug)
			.map(id -> ResponseEntity.ok(new ProviderReviews(
				reviews.summaryFor(id), reviews.recentFor(id, 10))))
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	record ProviderReviews(RatingSummary summary, List<Review> recent) {}

}
