# 10. Onboarding creates the account and imports the rest

**Status:** accepted

## Context

Onboarding assembles the two halves of a provider: something to sell, in Cal,
and somewhere to be paid, in Stripe. Neither is ours and neither is instant —
KYC takes days and can be rejected outright.

The obvious design is one flow we own end to end: collect the salon's details,
create everything on their behalf, done. Cal will not allow it.

## What Cal permits without a licence

| operation | result |
|---|---|
| `POST /api/auth/signup` (web) | **201** — public |
| `POST /v2/event-types` | **401** |
| `POST /v2/schedules` | **401** |

Creating a user is public. Creating that user's schedule and event types needs
authenticated api-v2, which needs a paid Cal licence (ADR 0008). So the flow we
would prefer is not available at any amount of effort short of buying one.

## Decision

**Create the account; import the rest.**

1. We create the `provider` row, the Cal user and the Stripe connected account.
2. The salon completes KYC on Stripe's hosted pages, and builds its services in
   **Cal's own UI** — which exists, is good at this, and is what a Cal customer
   would use anyway.
3. We **import** what they built, reading Cal's database as a read-model.

Reading Cal's schema is sanctioned by ADR 0001; the direction is the whole point.
A projection is fine, and writing would be the fork-by-stealth that ADR rejects.
The pool is opened read-only so a stray `UPDATE` fails at the driver.

## Ordering, and what a failure leaves behind

Provider row, then Cal, then Stripe — reversible things first. A provider with a
Cal account and no Stripe account is a resumable state someone can finish; a
Stripe account with no provider row is an orphan nobody will ever look at.

`onboarding_state` is explicit rather than derived from which columns are
non-null. A salon stuck halfway is exactly what operations needs to see, and a
NULL somewhere does not say which half failed.

## The invariant

**A provider is not sellable until it can be paid and has something to sell.**

Both halves matter, and they are not symmetrical. A listing with no services
refuses every booking, which is embarrassing. An active provider without payouts
takes a customer's money with nowhere to send it, which is much worse — so it is
refused by a database constraint as well as by the service:

```sql
CHECK (status <> 'active' OR (stripe_account_id IS NOT NULL AND payouts_enabled))
```

Verified: the constraint rejects the update, not just the code path.

Payability is **re-read** on every `account.updated`, never remembered from
onboarding. Stripe can restrict an account long after approving it, and the
first sign is otherwise a failed transfer — which happens after the customer has
already paid. A provider that stops being payable is suspended immediately.

## Unsafe services are skipped, and the salon is told which

An event type with `requiresConfirmation` but not
`requiresConfirmationWillBlockSlot` produces a reservation that holds nothing
(ADR 0008). Importing one would create a listing that takes money for slots
another customer can still book.

They are skipped and **returned in the response**, with the reason. Only the
salon can fix them, and only if told. Observed on a real import:

```json
{"imported":["Klippning 30 min"],
 "skipped":["Konsultation (requires confirmation but does not hold the slot)"],
 "activated":true}
```

## Consequences

- A provider maps to a Cal **user**, not a Team. A Team is the right model for a
  salon with several staff, and creating one needs the same licensed API.
  `cal_team_id` is now nullable rather than pretending otherwise.
- The salon has two places to log in — ours and Cal's. That is a real cost of
  this decision and the most likely thing to revisit if a licence is ever bought.
- Verified end to end: a new salon onboarded, imported one of two services,
  activated on KYC, was indexed by the reconciler within five seconds, and
  appeared first in search at 361 m with its own price and slot count.
