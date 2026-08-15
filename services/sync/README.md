# sync

The only component that knows Cal's shape.

Everything else in this system talks about providers, services and bookings.
This service talks about Cal Teams, EventTypes and Bookings, and translates.
That boundary is deliberate: if Cal is ever replaced, or upgraded in a way that
moves its schema, this is the component that changes and nothing else is
touched.

## What it does

Two jobs, and the second is the important one.

**Receive webhooks.** Cal posts `BOOKING_CREATED`, `BOOKING_CANCELLED`,
`BOOKING_RESCHEDULED` and friends. Each is written to `webhook_receipt` before
anything acts on it, then the affected provider's slice of `availability_day` is
recomputed.

**Reconcile on a timer.** Every N minutes, recompute availability for providers
whose index rows are older than the freshness target, regardless of whether a
webhook arrived.

The reconciler is not a safety net. It is the mechanism, and webhooks are a
latency optimisation on top of it. Webhooks are missed — on redeploys, on
network blips, whenever the receiver is briefly slower than the sender's
timeout — and a system that only reacts to them drifts silently. `computed_at`
and `source` on every index row exist so that drift is measurable instead of
mysterious.

## What it must never do

Write availability into Cal, or let the index confirm a booking. The index
narrows ten thousand providers to twenty. Cal answers the real question for
those twenty. A booking confirmed against the index is a double booking that
nobody can explain afterwards.

## Contract with Cal

| Direction | Mechanism |
|---|---|
| Cal → sync | Webhook, `subscriberUrl` pointing here, HMAC in `X-Cal-Signature-256` |
| sync → Cal | REST API with an API key, plus availability queries for the shortlist |

Cal's `Webhook` model is scoped per user, per team or per event type. Register
at **team** level per provider, so a new service inside a salon is covered
without registering anything new.

## Not yet built

The handlers are stubs. What exists is the boundary, the receipt table and the
contract; the availability computation itself is the next piece of real work,
and it is the piece worth writing tests for first.
