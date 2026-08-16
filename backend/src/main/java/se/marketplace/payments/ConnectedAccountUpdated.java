package se.marketplace.payments;

/**
 * A connected account changed — possibly from payable to not.
 *
 * <p>Carries only the identifier on purpose. Whether the account is still
 * sellable is a question to ask Stripe at the moment it matters, not a fact to
 * copy out of a webhook payload that may already be stale.
 */
public record ConnectedAccountUpdated(String stripeAccountId) {}
