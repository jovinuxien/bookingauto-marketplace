/**
 * How often one caller may do one thing.
 *
 * <p>A module rather than a shared utility, and not in {@code sharedModules}
 * either. A rate limit is a statement about what an endpoint is worth
 * attacking, so the modules that need one should have to say so — the
 * declaration is the documentation, and a module that could reach this without
 * declaring it would be a module whose exposure nobody had to think about.
 *
 * <p>It owns {@code rate_limit}, and that table is deliberately a bucket, a
 * window and a count: the name of what is being limited is the caller's
 * business, so two limits never have to share a schema change.
 *
 * <p>What is limited here is not always abuse. {@code signup} is defending Cal
 * and Stripe from an endpoint that provisions on demand (ADR 0011);
 * {@code search} is defending an invoice from an endpoint that calls a metered
 * model (ADR 0012). Different threats, same counter.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Rate limiting")
package se.marketplace.ratelimit;
