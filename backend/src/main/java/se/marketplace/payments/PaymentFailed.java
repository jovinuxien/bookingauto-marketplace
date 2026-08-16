package se.marketplace.payments;

/** The payment failed or was cancelled; whatever it was holding can be released. */
public record PaymentFailed(String paymentIntentId, String reason) {}
