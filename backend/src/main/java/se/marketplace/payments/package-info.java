/**
 * Money.
 *
 * <p>Owns Connect accounts, charges, the commission as an application fee,
 * refunds and the ledger. The boundary is deliberately sharp: nothing outside
 * this module decides what anything costs or moves money, and Cal's own
 * {@code Payment} model is not a marketplace ledger and is not used as one.
 * See ADR 0003.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payments")
package se.marketplace.payments;
