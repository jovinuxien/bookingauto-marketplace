package se.marketplace.reviews;

import java.time.Instant;

/**
 * One customer's verdict.
 *
 * @param author the customer as shown to strangers: first name and an
 *               initial, "Anna A." — never the e-mail, never the full name
 */
public record Review(long bookingId, int rating, String comment, Instant createdAt, String author) {}
