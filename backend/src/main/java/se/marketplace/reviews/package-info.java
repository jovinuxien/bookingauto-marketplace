/**
 * What customers thought.
 *
 * <p>Stores a rating per booking and answers the two questions everyone
 * asks about it: what is this provider's average, and what did the last
 * few people say. Who may write one -- the customer holding a booking that
 * has happened -- is the booking module's business, because the booking
 * module owns the link that proves it; this module only refuses a second
 * review for the same booking.
 *
 * <p>Read by SQL from {@code search} and {@code landing}, which is why the
 * table carries {@code provider_id}: the average is on every search hit and
 * the join must be cheap. Nothing here is sent anywhere; the request mail
 * is sent by {@code booking}, which has the address and the link.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reviews")
package se.marketplace.reviews;
