# The booking funnel

Design note, not a decision record. The decisions it depends on are ADR 0002
(availability index), 0003 (money boundary), 0005 (hold and payment ordering)
and 0007 (how we reach Cal).

## Its role

**The funnel is where the marketplace stops being allowed to be wrong.**

Everything upstream is approximate on purpose:

- Search reads a deliberately stale-tolerant index. Being wrong there costs a
  wasted click, and ADR 0002 accepts that trade knowingly.
- The Redis hold is a courtesy. Being wrong there costs a collision at
  checkout, and ADR 0005 says so plainly.

The funnel is the one place where three independent authorities have to end up
agreeing, and it is the only place where disagreement costs a customer real
money or a real appointment.

| authority | owns | only it can say |
|---|---|---|
| **Cal** | time | this slot is now taken |
| **Stripe** | money | this customer actually paid |
| **us** | the commercial record | what was sold, at what price, on what terms |

So the funnel has three jobs, and only the first is obvious.

**1. Convert an optimistic result into a commitment.** Take "these twenty
salons probably have Saturday afternoon" and turn it into one appointment that
exists in Cal, one charge that exists in Stripe, and one row that exists here —
across three systems that fail independently and cannot be updated atomically.

**2. Fail in the customer's favour, legibly.** There is no distributed
transaction available. What replaces it is an explicit compensation for every
partial failure, and a written trail at each step. The trail is not diagnostics;
it is the answer to "I was charged and have no appointment", and it needs to
exist before the first time someone asks.

**3. Measure whether search is lying.** When Cal refuses a slot the index
advertised, that is an `availability_miss` — the table exists and nothing writes
to it yet. The funnel is the only component positioned to notice, because it is
the only one that asks both the index and the authority about the same slot.
Left unmeasured, index drift is invisible until consumers quietly stop
returning.

## Stages

Each stage has a different authority and a different cost of being wrong. The
boundary that matters most is between 5 and 6: everything before it is
reversible and free, everything after changes the world.

| # | stage | authority | cost of being wrong |
|---|---|---|---|
| 1 | Shortlist | index | a wasted click |
| 2 | Verify against Cal | **Cal** | first true statement; a miss is recorded |
| 3 | Hold | Redis | a collision at checkout |
| 4 | Identify the customer | us | GDPR exposure |
| 5 | Quote | us | wrong price or terms, disputable later |
| — | — | — | *— nothing above has changed the world —* |
| 6 | Reserve | **Cal** | the slot is now held |
| 7 | Charge | **Stripe** | money has moved |
| 8 | Confirm | **Cal** | the appointment is real |

**Stage 2 is not a formality.** The index answers at day granularity — "Saturday
afternoon". The customer books a time. Cal is what turns one into the other, and
it is where the index's staleness surfaces as a fact rather than a suspicion.

**Stage 4 keeps special-category data out.** Treatment journals are health data
under GDPR. They do not belong in booking metadata, which is replicated into
Cal, sent in webhooks and mailed in confirmations.

**Stage 5 freezes the commercial terms.** Price, commission and cancellation
policy are recorded as of this moment. Reading them live at refund time means a
price change silently rewrites a completed sale.

## Compensation

Ordering is forced, not chosen. ADR 0005 established that Swish has **no manual
capture**, so authorise-then-capture is unavailable and the compensation for a
failed sale is a **refund**, not a void. That makes reserve-before-charge the
only ordering that never takes money for a slot we do not hold.

| fails at | compensation | customer sees |
|---|---|---|
| 6 Reserve | release hold, record `availability_miss`, re-verify | alternative times — **no money moved** |
| 7 Charge | cancel the pending Cal booking | slot released, nothing charged |
| 8 Confirm | refund, then retry or cancel | refunded within minutes |

Stage 6 failing is a **normal handled path**, not an exception. It is what
ADR 0002 buys: search is allowed to be wrong precisely because this step exists
to catch it.

## Superseded: how the slot is held

The section below documents a real finding, and the approach it describes is no
longer what we do. Holding a slot with a *pending* booking requires confirming
it later, and confirming is the one operation that needs an authenticated
api-v2 call — which needs a paid Cal licence. Auto-accepting event types hold
the slot just as firmly with nothing to confirm. See **ADR 0008**.

Kept because the flag behaviour is still true, still surprising, and still the
right answer for a licensed deployment.

## Verified: reserve-first requires two flags, and both default to false

ADR 0005 chose reserve-first on the assumption that a pending booking holds the
slot. Tested against this Cal version, that assumption is **false by default**.

With `requiresConfirmation = true` alone, `POST /api/book/event` creates a
booking with status `pending` — and the slot is **still offered to everyone
else**. The day kept all 12 slots and the booked 07:00Z remained on sale. A
funnel built on that would reserve nothing, then charge for a slot another
customer can still take, and the failure would only appear as two people
arriving for one appointment.

Setting `requiresConfirmationWillBlockSlot = true` as well drops the day to 11
slots and removes the booked time. Both flags are required; both default to
`false`.

This is therefore a **provider-onboarding invariant**, not a booking-time
setting: an event type created without them is unsafe to sell, and nothing about
it looks wrong. It is enforced in `seed/cal-dev.sql` and must be enforced again
wherever onboarding creates event types for real.

## Components

**Existing, and what changes**

- `search` — hands off a provider and service, then takes no further part. The
  funnel must never consult the index again; that is the whole point of stage 2.
- `sync` — owns Cal's wire shape and gains write methods (`reserve`, `confirm`,
  `cancel`) on `CalPort`. **The funnel must not call Cal directly.** The
  property that exactly one component knows Cal is what makes ADR 0007's
  internal-API risk containable, and it dies the moment a second caller appears.
- the reconciler — a successful booking marks the service stale immediately
  rather than waiting for Cal's webhook to arrive.
- `availability_miss` — exists, empty, and finally gets written at stage 2 and
  stage 6.

**New**

- `booking` — owns the saga and the state machine. The orchestrator, and the
  only module that knows all three authorities exist.
- `payments` — Stripe Connect, the charge, the commission as an application fee,
  refunds, and the immutable ledger ADR 0003 requires. Cal's `Payment` model is
  not that ledger and is not used as one.
- the hold — Redis, small enough to live inside `booking`.

**New tables**

- `booking` — our commercial record, keyed to Cal's booking `uid`. Carries the
  frozen quote from stage 5.
- `booking_attempt` — the state machine and the trail. Every stage transition,
  every authority's answer, every compensation. This is the row you read when a
  customer says they were charged for nothing.

## Resolved: the funnel needs api-v2, and not for the reason expected

The open question was whether writes force us to build `api-v2`. Probed against
the running Cal, the answer is yes — but the blocker is not creating bookings.

| endpoint | auth | result |
|---|---|---|
| `POST /api/book/event` | public | **works** |
| `POST /api/trpc/bookings/confirm` | — | 401 |
| `POST /api/cancel` | — | 403, wants a session CSRF token |

Creating a booking is public, because that is what a customer does on the
booking page. Confirming and cancelling are privileged, because those are things
a *salon* does. A bearer token does not get in either.

So we can reserve and cannot take it back — which is strictly worse than not
being able to reserve at all. Every failed payment strands a pending booking
that blocks a real slot, permanently, with no automated path to release it.

**Observed, not predicted.** A live checkout ran reserve → verify → charge →
confirm; confirm failed, the refund succeeded, the release did not, and the
attempt ended `NEEDS_ATTENTION`. The next checkout for that slot was then
refused by Cal with `no_available_users_found_error` — a real customer would
have been turned away from a slot nobody holds. Releasing it required deleting
the row from Cal's database by hand.

The write is still not trusted: the read-back gate between stages 6 and 7 stays,
because an internal API changing shape on a read means a stale index, while on a
write it means charging for an appointment that does not exist. Pin the Cal
image version and re-probe every endpoint on upgrade.

**Done.** `api-v2` is built and deployed (`docker/build-api-v2.sh`, service
`cal-api`), and the funnel runs end to end against it:

| attempt | outcome | effect |
|---|---|---|
| complete sale | `CONFIRMED` 201 | booking written, day 12 → 11 slots |
| same slot again | `REFUSED` 409 | availability miss recorded |
| declined payment | `CHARGE_FAILED` 402 | **slot released**, still 11 slots |

Cal ends with one `accepted` and one `cancelled` booking, and the released
attempt's trail carries the compensating step. The one thing still gated is
`confirm`, and ADR 0008 explains why we no longer need it.
