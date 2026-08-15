# booking-marketplace

A two-sided appointment marketplace — consumers find and book local services,
businesses manage calendars and get paid — built on
[`calcom/cal.diy`](https://github.com/calcom/cal.diy) as the scheduling engine.

Architecture write-up:
<https://claude.ai/code/artifact/ce763979-e07f-4119-b320-cc0d376eceac>

## The idea in one line

**Cal.diy owns time. This project owns money, identity, discovery and trust.**
They meet at exactly one object: the booking.

Availability is written only in Cal. Money is written only here. Everything else
is a projection kept fresh by webhooks and a reconciler.

## What runs today

```bash
cp .env.sample .env          # or keep the generated one
set -a && . ./.env && set +a
docker compose up -d
```

| | | |
|---|---|---|
| `cal` | http://localhost:3000 | Cal.diy v6.2.0 — scheduling engine |
| `cal-db` | :5442 | Postgres 16, Cal's own schema (125 tables) |
| `market-db` | :5443 | PostGIS 16-3.4 — providers, services, availability index |

Ports avoid 3000-adjacent defaults already in use on this machine.

Cal's first boot runs its migrations and takes a few minutes; after that it
comes up in about a second. Open :3000 and complete the setup wizard to create
the first admin.

**Cal.com Inc. does not officially support Docker** — the image and compose
setup are community maintained. Fine here; for production, decide deliberately
between running the image and building from source.

## What is real, and what is a sketch

Real and verified:

- Both databases up, `db/001-availability-index.sql` applied on first start
- PostGIS 3.4 working
- **The core product query runs.** Seeded three salons and asked the actual
  question — colour, day after tomorrow, afternoon, within 5 km of a point in
  Stockholm — and got the two Stockholm salons back ordered by distance, with
  Göteborg correctly excluded
- Cal.diy healthy, serving, schema migrated

Sketch:

- `services/sync` is a README and a boundary, not an implementation
- `marketplace-api`, `search` and `payments` do not exist yet
- No frontend

## Layout

```
docker-compose.yml     cal + two databases
db/                    marketplace schema, applied on first start
docs/decisions/        ADRs — why, not what
services/sync/         the only Cal-aware component
```

## The three decisions that shape everything

Read `docs/decisions/` before changing anything structural.

1. **Cal is a dependency, not a fork.** Reached by API, webhooks and
   database-as-read-model. MIT permits forking; the upgrade treadmill is the
   reason not to.
2. **Search reads an index, never Cal.** `availability_day` narrows ten
   thousand providers to twenty; Cal answers the real question for those
   twenty, and only Cal confirms a booking.
3. **The consumer pays the platform.** Stripe Connect, commission as an
   application fee, salon KYC at onboarding. That decision is what makes this a
   marketplace rather than a directory.

## Stack

**Spring Boot on the backend, TypeScript on the frontend** — see ADR 0004. Cal is
reached over HTTP, and an HTTP boundary is a language boundary; the domain on our
side is payments, ledger, POS and audited records, which is JVM territory. The
frontends stay TypeScript because SEO requires server rendering and Cal's
embeddable React components carry the business calendar.

Start as a **Spring Modulith modular monolith** with `marketplace`, `search`,
`payments` and `sync` as enforced modules — not four deployables. Split when a
module earns its own scaling curve; `search` will be first.

One backend serves **both** frontends. The monolith is not "the B2B side" — B2C
and B2B are two TypeScript applications over the same Spring backend, and the
module boundaries inside it are by domain, not by audience.

Search launches on **PostGIS alone**; OCSS enters in phase two, for free-text
relevance, once there are query logs to tune against. See ADR 0006 — OCSS has no
geo support at all, and geo is this product's primary filter.

## Next

In the order worth doing it:

1. Provider onboarding — Team + staff + services + Stripe KYC in one flow.
   Nothing works before supply exists.
2. The reconciler in `services/sync`, with tests. It is the mechanism;
   webhooks are a latency optimisation over it.
3. The booking funnel, with the confirm-against-Cal step made explicit.
4. Payments.

## Known shape problems, recorded early

- **Cal schedules people, not rooms.** A salon with four chairs and six
  stylists has a constraint Cal does not model. Decide before onboarding a
  salon that cares.
- **A Redis slot hold is a courtesy, not a guarantee.** It stops two people at
  checkout colliding; it does not stop a walk-in, a Google Calendar sync or a
  Redis eviction. Cal refusing a booking is a normal handled path — see ADR 0005,
  which also covers the payment ordering problem.
- **Swedish specifics are not decoration.** Swish and Klarna rather than card
  rails, kassaregister obligations for physical drop-in sales, and treatment
  journals as special-category personal data kept out of booking metadata.
- **Availability accuracy is a product metric.** `availability_miss` exists to
  record "shown as available, turned out not to be". It quietly decides whether
  consumers come back and is invisible unless measured.
