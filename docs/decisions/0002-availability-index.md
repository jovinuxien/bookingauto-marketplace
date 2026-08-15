# 2. Search reads an index, never Cal

**Status:** accepted

## Context

The product's primary query is "salons near me with a free slot for a
45 minute colour on Saturday afternoon".

Cal computes availability per event type, on request, from calendars, buffers
and existing bookings. That is correct, and fast enough for one booking page.
Across ten thousand providers it would be ten thousand computations per search.

## Decision

Maintain an `availability_day` index in the marketplace database: one row per
provider per service per day, holding whether there is capacity, when the first
free slot is, and coarse morning/afternoon/evening flags.

Search reads only the index. It narrows ten thousand providers to roughly
twenty. Cal is then asked the real question for those twenty, and **only Cal
confirms a booking.**

## Why day-level and not slot-level

Slot rows would be enormous and stale within seconds. Day-level answers the
question search actually asks — is this provider worth showing at all — and the
exact times come from Cal for the shortlist, where the cost is affordable.

## Consequences

- The index is **allowed to be wrong**. A provider shown as free who is not is
  a poor search result. A booking confirmed from the index is a double booking.
  These are not the same severity and the design must keep them apart.
- Freshness is a first-class property: `computed_at` and `source` on every row.
- `availability_miss` records every time the index said yes and Cal said no.
  Track it from day one — it decides whether consumers return, and it is
  invisible unless instrumented.
- Webhooks are a latency optimisation. The reconciler is the mechanism.
