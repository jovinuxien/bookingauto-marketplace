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

Two acceptable orderings:

**Reserve first (preferred).** Create the booking in Cal in a pending state,
then charge, then confirm. If payment fails or is abandoned, cancel the pending
booking. The slot is genuinely held by the authority that owns it, and no money
moves against a slot that was not secured.

**Charge first with compensation.** Authorise rather than capture, create the
booking, capture on success, void the authorisation on failure. Requires the
payment provider to support authorise-and-capture — check this against Swish
specifically, whose flow differs from card rails.

Either way the compensating action is explicit, logged, and tested. "It should
not happen" is not a design.

## Consequences

- Every booking attempt records: hold acquired, Cal accepted or refused, payment
  state, and the compensating action if any. This is the trail you will want the
  first time a customer says they were charged for nothing.
- Cal refusing a booking is measured, not just handled. A rising refusal rate
  means the availability index is drifting — see ADR 0002.
