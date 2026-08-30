package se.marketplace.reviews;

/**
 * @param average null when there are no reviews — not zero, which would rank
 *                a new provider below one with a single 1-star review
 */
public record RatingSummary(Double average, int count) {

	public static final RatingSummary NONE = new RatingSummary(null, 0);

}
