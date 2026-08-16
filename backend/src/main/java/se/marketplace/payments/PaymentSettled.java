package se.marketplace.payments;

/**
 * The customer's payment completed.
 *
 * <p>Part of this module's published contract rather than an internal detail:
 * it is what lets a suspended booking saga resume without {@code booking} ever
 * learning that Stripe is the gateway, or what a PaymentIntent is.
 */
public record PaymentSettled(String paymentIntentId, String chargeReference) {}
