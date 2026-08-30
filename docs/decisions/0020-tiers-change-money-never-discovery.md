# 20. Tiers change money, never discovery

**Status:** accepted — with the prices as placeholders, because a price
list is a business decision and this ADR only builds the shelf it sits on

## Context

Lasingoo sells Bronze, Silver and Gold (99/249/499 kr/mån), and what Gold
buys above the others is **top placement in search**. That is the industry
default, and it is the one thing this marketplace should not copy: we just
built reviews so that ranking means something, and a rank that can be
bought is a rank a customer learns to ignore. The first time someone
notices the 3,2-star workshop above the 4,9-star one, the stars stop
working — and the stars are the product.

Yet tiers exist for a reason: a provider doing three bookings a month and
one doing three hundred should not be on the same terms, and a lower
commission is something a busy workshop will pay a monthly fee for.

## Decision

**A plan per provider — `bas`, `plus`, `pro` — and the only thing it
changes is the commission.** Search stays ordered by distance and time;
landing pages stay alphabetical; reviews stay earned. What `pro` buys is a
lower take on every booking, which is worth the most to exactly the
providers worth keeping.

- The **column** holds the plan name. The **numbers** — monthly price,
  commission bps — live in configuration, read through one component
  (`Plans`), because they are a price list and price lists change without
  migrations. An unknown plan reads as `bas`, so a config mistake
  under-charges nobody and over-charges nobody.
- The commission is resolved **at quote time from the provider's plan**
  and frozen onto the attempt as it always was (ADR 0005): a plan change
  never rewrites a sale in flight.
- **The plan is set by an operator**, not self-served. Upgrading is a
  conversation that ends with a payment arrangement; the day that
  conversation is frequent enough to automate is the day to integrate
  Stripe Billing, and it has not happened yet. The console shows the
  provider its plan, its commission, and the full price list, with
  "kontakta oss" as the call to action.

## What this does not do

- **No monthly-fee collection.** The fee is invoiced by a person until the
  volume argues otherwise; wiring Stripe subscriptions for two customers
  would be automation of a queue with nobody in it.
- **No feature gating.** The widget, messaging, price rules and add-ons
  work on every tier. Features withheld from `bas` are features the
  marketplace's supply side does not get to show customers, and thin
  provider pages hurt the marketplace more than they upsell the provider.
- **No sold placement**, which is the decision this document exists to
  write down.

## Watch items

- The placeholder numbers (0 / 249 / 499 kr; 15 / 12 / 9 %) are guesses
  shaped like Lasingoo's ladder. Real numbers need real unit economics —
  Stripe's cut, support cost per booking — and belong to whoever owns the
  P&L, not to a config file's defaults.
- A plan set by an operator with no billing behind it can drift: a `pro`
  provider whose invoice lapsed keeps its 9 % until a person notices. The
  console shows the plan; nothing yet reports "plans vs. paid invoices".
