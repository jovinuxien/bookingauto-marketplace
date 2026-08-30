package se.marketplace.pricing;

/** One extra a provider offers with a service, priced and named as the customer sees it. */
public record Addon(long id, long serviceId, String name, int priceMinor) {}
