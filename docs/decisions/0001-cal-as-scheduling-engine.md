# 1. Cal.diy is the scheduling engine, and is not forked

**Status:** accepted

## Context

The product is a two-sided booking marketplace. The hard, unglamorous core is
resource scheduling: staff calendars, service durations, buffers, opening
hours, double-booking prevention under concurrency, reschedules, cancellations,
no-shows, calendar sync with Google and Microsoft.

That is months of work to build badly and years to build well, and none of it
differentiates the product. Discovery, trust and money are what differentiate.

## Decision

Use `calcom/cal.diy` as the scheduling engine. Treat it as a dependency reached
through its API, webhooks and database-as-read-model. **Do not fork it.**

## Why not fork

Every patch to its internals makes the next upgrade a merge. The licence is MIT
so forking is permitted — the reason to avoid it is the treadmill, not the
lawyer. Everything this product needs is reachable without touching Cal's
source.

## Consequences

- Cal owns availability. Nothing else may write it.
- A `sync` service is the only Cal-aware component, so replacing Cal later is a
  bounded change.
- Cal's model shapes ours: salon → Team, staff → User + Membership, service →
  EventType, appointment → Booking. Fighting that mapping would be the mistake.
- Cal schedules **people, not rooms**. Shared resources — a chair, a treatment
  room, a laser — are not modelled. See ADR 0004 when a salon needs it.

## Licence note

`calcom/cal.com` now redirects to `calcom/cal.diy`, MIT licensed. Earlier
guidance that it was AGPL is out of date; that would have had real consequences
for a closed marketplace and it does not apply. Verify before committing —
licences move, as this one did.
