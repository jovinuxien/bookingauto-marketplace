# 9. Payments are asynchronous, so the funnel must be able to wait

**Status:** accepted — extends ADR 0005

## Context

The funnel's stage 7 was a function call: charge, and find out whether it
worked. That is how the dev gateway behaves, and it is how nothing else does.

Swish is a **push payment**. Creating a PaymentIntent does not take money — it
returns `requires_action` and a client secret, the customer opens their bank app,
approves, and Stripe tells us over a webhook some seconds or minutes later. Cards
with 3-D Secure behave identically.

A synchronous `charge()` cannot express that. It would have to either block a
request thread on a human being, or report success before any money moved.

## Decision

**The saga can suspend.** A new state, `AWAITING_PAYMENT`: the slot is reserved,
a PaymentIntent exists, nothing has been charged, and the customer is somewhere
we cannot see.

`PaymentPort.charge` returns a `Charge` that is either `SETTLED` or
`REQUIRES_ACTION` with a client secret. The funnel handles both, so a gateway
that does settle inline still works and the branch is not hypothetical.

The checkout API answers **202 Accepted**, not 201: the client's job is to take
the client secret and finish the payment, not to show a confirmation.

## The half that arrives later

`StripeWebhookController` resumes the saga on `payment_intent.succeeded`, and
releases the slot on `payment_intent.payment_failed` or `.canceled`.

Three properties it must have, all of them learned from how payment webhooks
behave rather than from how they are documented:

- **Verify before reading.** This endpoint completes sales and is exposed to the
  internet. An unsigned body is not a malformed request; it is someone claiming
  a payment succeeded.
- **Record before acting**, as with Cal's webhooks. When someone asks whether
  Stripe ever told us, the answer should be a row.
- **Assume redelivery and reordering.** Stripe retries on any non-2xx and
  promises no order. `event_id` is unique in the database and the funnel ignores
  transitions out of states it has already left, so a repeat is a no-op instead
  of a second booking. Verified: the same event delivered twice returns
  `duplicate` and creates one booking.

## The sweeper is the mechanism; the webhook is the optimisation

**Nobody sends a webhook for a customer who simply walked away.** They open
Swish, get distracted, and never come back — and that attempt is holding a real
appointment slot with nothing to release it.

So `AbandonedCheckoutSweeper` releases anything that has been waiting too long.
This is the same shape as the availability reconciler, and for the same reason:
if Stripe's failure webhooks stopped entirely, slots would still come back —
later, but they would come back.

Verified end to end: a checkout was started and abandoned, the slot went from 12
free to 11, and the sweeper returned it to 12 with the attempt in
`CHARGE_FAILED`.

The window is a **product decision, not a technical one**. Too short and a
customer who paid slowly finds their slot resold; too long and popular times sit
blocked by people who left. It errs long — 15 minutes — because reselling a slot
somebody just paid for is much the worse failure.

## Money details that are not incidental

- **Destination charges.** The PaymentIntent is created on our account with
  `transfer_data.destination` set to the salon's connected account and
  `application_fee_amount` as commission. A direct charge on the salon's account
  would make *them* the merchant of record and undo ADR 0003.
- **Refunds reverse the application fee** (`refund_application_fee`,
  `reverse_transfer`). Keeping commission on a sale that did not happen is
  revenue we are not entitled to.
- **Automatic capture, because there is no alternative.** Swish has no manual
  capture, so the only way to undo a completed payment is a refund — which is
  what forced the funnel's ordering in ADR 0005.
- **A provider with no connected account is refused before any call is made.** A
  charge with no destination succeeds and leaves the whole amount on the platform
  account with nothing recording whose it is.

## What has actually been verified

Against **stripe-mock**, which answers with fixtures and validates nothing about
the business. A green run means *the requests are well formed*, not that payments
work:

```
POST /v1/payment_intents
  amount:60000  application_fee_amount:9000  currency:sek
  payment_method_types:[swish card]
  transfer_data:map[destination:acct_...]
  metadata:map[idempotency_key:... provider_id:1]
```

Still unverified, and only a Stripe test account can do it: real Swish
redirection, real webhook signatures, Connect onboarding and payout behaviour.
