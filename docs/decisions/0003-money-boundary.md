# 3. The consumer pays the platform

**Status:** accepted

## Context

A directory sends the customer to the salon. A marketplace takes the payment,
keeps a commission and pays the salon out. That single decision is most of what
separates the two, and it pulls in KYC, payouts, refunds, disputes and
settlement reporting.

## Decision

Payments run through Stripe Connect. The consumer is charged by the platform;
the commission is taken as an application fee; the balance is destined for the
salon's connected account. Salon KYC happens at onboarding, before the salon
can be listed.

Cal's `Payment` model records that a booking was paid. It is **not** a
marketplace ledger, and is not used as one.

## Consequences

- A `payments` service owns Connect accounts, charges, commission, refunds,
  payouts and stored value, with an immutable log. Money is audited; its
  boundary stays sharp.
- Gift cards and wellness cards are stored value in that ledger, applied before
  the charge, supporting partial redemption and expiry. Wellness cards also
  carry an employer billing relationship.
- Cancellation policy is a money decision before it is a UX one: who is
  refunded, how late, and who absorbs the fee.
