# 14. The mailbox is the consumer account

**Status:** accepted

**Extends** ADR 0011, which established the same argument for salons: an address
that can be read is a thing that can be proved, and proving it is often all the
identity a transaction needs.

## Context

This marketplace has had exactly one consumer write endpoint and no way back
from it. Someone books, is charged, receives a confirmation, and from that
moment the system offers them nothing: no account, no history, no cancel, no
reschedule. `booking.status` has allowed `cancelled` and `refunded` since
db/002 and **nothing has ever written either**.

The gap is not cosmetic. A customer who cannot cancel does not stop cancelling —
they stop turning up, and the salon finds out by holding an empty chair. So the
missing feature is not really "cancellation"; it is that the two parties to a
sale have no way to tell each other that it is off.

Everything mechanical for this already exists. The funnel releases Cal
reservations and refunds Stripe charges as compensations, and has since the
booking saga was written. What was missing is *who may trigger those, and on
what terms* — which is an identity question and a commercial one, not a
technical one.

## The identity question

The obvious answer is a consumer account, and it is worth being precise about
what an account would actually buy here.

| | what it gives | what it costs |
|---|---|---|
| password account | a history page | a second auth system, a password nobody remembers for a haircut, password resets, and consumer credentials to leak |
| passwordless account | a history page | a second auth system, a `consumer` table, and a consumer profile to erase on request |
| **a signed link** | this booking | no history page |

The history page is the whole difference, and it is worth less than it looks:
this is a marketplace where a customer's *second* visit is often a year later
and to a different salon. What a customer wants at the moment they want
anything is **this booking**, and they are holding an email that names it.

### Decision

**A booking is reachable by an HMAC over its id and the customer's address,
sent to that address in the confirmation email. There is no consumer account.**

The property this buys is not convenience. It is that a marketplace which never
asks a consumer to create an account **has no consumer credentials to leak and
no consumer profile to erase**. Under GDPR that is not a small thing: the
personal data we hold about a consumer is exactly the name and address needed to
perform the booking they asked for, held for as long as the commercial record
is, and nothing else. There is no login to breach because there is no login.

### Why derived rather than stored

`signup`'s verification token is random, stored hashed, and single-use, because
it must be *revocable* — the whole point of ADR 0011 is a link that can be spent.

This one is the opposite shape. It is opened repeatedly, from an email, weeks
apart, on whatever device is to hand, and it has no state anyone would want to
change. Deriving it means no table, no row per sale, and nothing to sweep. The
address inside the MAC binds a token to the booking it was issued for, so an
edited id fails rather than opening a stranger's appointment.

### Why it does not expire

What the token authorises shrinks on its own: cancelling is refused once the
appointment has passed, which is a fact about the booking rather than about the
link. What remains afterwards is the ability to read a booking's salon, time and
price — which is precisely what the email carrying the link already says, to the
same mailbox. An expiry would buy nothing against anyone who can read that
mailbox, and would cost a customer their receipt.

## The commercial question

**Free until 24 hours before, and after that the slot comes back but the money
does not.**

The number is configurable, shown on the page before the customer commits, and
**copied onto the booking at sale time** — the same treatment `price_minor`
gets, for the same reason. A policy this system changes next year must not
silently rewrite what somebody agreed to this morning.

Cancelling late is still allowed. A customer who cannot cancel simply does not
turn up, so refusing the cancellation costs the salon the chair and gains
nobody anything. A salon that learns at eight in the morning can sell the slot;
a salon that learns at nine fifteen cannot.

## The order, which is the actual design

**Claim the row, then release the slot, then return the money.**

**Claim first** because a customer with two tabs open is not an edge case. Both
requests read a confirmed booking, and a status check in Java lets both past —
so the guard is a conditional `UPDATE ... WHERE status = 'confirmed'`, and
losing that race is a normal outcome rather than an error. The cost is that a
process dying mid-flight leaves a booking marked cancelled whose outcome nobody
knows, which is why the claim also sets `needs_attention` and the settle clears
it. A visible inconsistency a person can reconcile is worth more than a double
refund.

**Cal before Stripe**, which is the opposite of what the funnel does in
`refundAndStop`, and both are right. There, the sale never completed, so there
was no appointment to protect and the money was the only thing at stake. Here
the customer has asked to lose the appointment — so delivering that and owing
them money is a state that is flagged, logged and fixable. The reverse leaves a
customer with their money and a live appointment they believe is cancelled, and
nothing finds out until the salon holds the chair.

## What this does not do

- **No reschedule.** It is a cancel and a re-book against a live availability
  query, with a failure mode neither half has: the new slot is gone by the time
  the old one is released. Much easier once this is proven, and a separate
  decision about whether the money follows the booking.
- **No history page**, by construction. If one is ever wanted, this ADR is what
  has to be revisited — not extended.
- **It does not let a salon cancel.** The console is still read-only. A salon
  that needs to cancel does it in Cal, and our record will disagree until
  someone reconciles it. That is a real gap and is listed below rather than
  closed here, because "the salon cancels" is a different conversation about who
  owes the customer what.

## Watch items

- **The secret is load-bearing and has no default.** Blank generates one per
  process and warns loudly, which keeps a developer machine working and makes
  the cost visible — every link already emailed stops verifying at the next
  restart. A deployment that never sets it will send links that break on deploy,
  and the only signal is a line in the startup log.
- **`needs_attention` is set and nothing reads it.** The flag is honest and the
  log is loud, but a customer owed money is currently discovered by reading
  logs. The console's attention screen shows stuck *attempts* and does not yet
  show stuck cancellations, which is the same gap `availability_miss` has.
- **Cal can cancel behind our back.** A salon cancelling in Cal's own UI leaves
  our booking reading `confirmed` for an appointment that no longer exists. The
  webhook that marks availability stale does not touch `booking`. This was
  already true before this ADR; giving customers a cancellation makes the
  disagreement more visible rather than more likely.
- **The confirmation email now contains a bearer credential.** It always
  contained enough to identify the booking; now it contains enough to cancel
  it. A forwarded confirmation is a forwarded cancel button, which is the
  understood cost of not having accounts.
