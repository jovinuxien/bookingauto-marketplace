# 5. The Redis hold is a courtesy; Cal is the authority

**Status:** accepted

## Context

The incoming blueprint proposes a Redis lock keyed
`lock:salon_id:stylist_id:timestamp`, taken when a consumer clicks a slot, held
with a five minute TTL while they pay, released after Cal confirms. It describes
this as eliminating double bookings during flash events.

The lock is a good idea and worth building. The claim around it is wrong in a way
that matters.

## What the lock actually buys

It stops two people who are *looking at the same slot right now* from both
reaching checkout. That is a real and common collision, and without a hold the
second customer pays and then discovers the slot is gone. Worth having.

## What it does not buy

Correctness. A Redis key is not a transaction against Cal's booking table.

- Redis evicts under memory pressure, restarts, and partitions.
- A held key says nothing about a booking made in the salon's own Cal calendar,
  through Google Calendar sync, or by a walk-in entered at the counter.
- Two application instances with a brief network split can both believe they
  hold it.

Treating the lock as the guarantee means the day it fails, two customers arrive
for the same appointment and nothing in the logs explains why.

## Decision

**Cal is the only authority on whether a slot is free.** `POST /v2/bookings`
must be allowed to fail, and that failure is a normal, handled path — not an
exception. The Redis hold is an optimistic courtesy that reduces collisions and
improves the funnel; it never authorises anything.

## The ordering problem, which the blueprint leaves open

The proposed sequence is: hold → take payment → create booking in Cal → release.

If the booking call fails after the payment succeeded, the customer has been
charged for an appointment that does not exist. There is no compensation step in
that sequence, and this is the single most likely way to lose a customer's trust.

Only one ordering survives contact with the Swedish payment methods. Checked
against Stripe's Swish documentation:

| | Swish on Stripe |
|---|---|
| Connect | yes — direct, destination, separate charges and transfers |
| Refunds | yes, full and partial, up to 365 days |
| **Manual capture** | **no** |
| Disputes | none |
| Recurring | no |

No manual capture means **authorise-then-capture is not available**. The second
ordering below is therefore closed to us for the dominant Swedish payment
method, and we do not want two different orderings depending on how the customer
chose to pay.

**Reserve first (preferred).** Create the booking in Cal in a pending state,
then charge, then confirm. If payment fails or is abandoned, cancel the pending
booking. The slot is genuinely held by the authority that owns it, and no money
moves against a slot that was not secured.

> **Amendment — "pending holds the slot" is false by default.** Tested against
> the Cal version we run: with `requiresConfirmation = true` alone,
> `POST /api/book/event` returns a booking with status `pending` and the slot
> **stays on sale** — the day kept all 12 slots and the booked time was still
> offered. The reserve step would reserve nothing, and we would charge for a
> slot another customer can still take.
>
> `requiresConfirmationWillBlockSlot = true` is also required; with both set the
> day drops to 11 slots and the booked time disappears. Both default to `false`.
>
> This makes reserve-first an **onboarding invariant** rather than a
> booking-time choice: an event type created without both flags is unsafe to
> sell and looks completely normal.
>
> **Superseded by ADR 0008.** Confirming a pending booking needs an
> authenticated api-v2 call, and that needs a paid Cal licence. We now create
> auto-accepting event types instead: the booking comes back `ACCEPTED`, already
> holding the slot, with nothing to confirm. The ordering argument below is
> unchanged — the slot is still held before money moves — only the mechanism is.

**Charge first with compensation.** ~~Authorise rather than capture, create the
booking, capture on success, void the authorisation on failure.~~ **Not
available** — Swish does not support manual capture. Recorded here so nobody
re-proposes it.

So: reserve first, and the compensating action is a **refund**, not a void.
Swish refunds are full or partial and complete in minutes, which makes the
abandoned-checkout path acceptable. It must still be explicit, logged and
tested; "it should not happen" is not a design.

Two consequences worth carrying forward. Swish has **no disputes**, which
removes a whole class of marketplace chargeback risk. And it has **no recurring
payments**, so anything subscription-shaped — a wellness card billed monthly to
an employer — needs card rails, not Swish.

## Consequences

- Every booking attempt records: hold acquired, Cal accepted or refused, payment
  state, and the compensating action if any. This is the trail you will want the
  first time a customer says they were charged for nothing.
- Cal refusing a booking is measured, not just handled. A rising refusal rate
  means the availability index is drifting — see ADR 0002.
