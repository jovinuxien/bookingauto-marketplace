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

Real and verified end to end — Cal's real availability now reaches the search
API without anything hand-written in between:

- Three salons seeded in **Cal** (users, schedules, bookable event types) and
  matched to marketplace providers by slug
- **The reconciler fills the index from Cal.** 14 days per service, every day
  written — including the Sundays, as `has_capacity = false`, because a missing
  row cannot be told apart from one never computed
- **The core product query runs.** Saturday afternoon within 5 km of a point in
  Stockholm returns the two Stockholm salons ordered by distance; Göteborg is
  excluded at 5 km and appears at 600 km, 397 km out. Sunday returns nothing,
  because Cal says the salons are closed
- **The webhook loop closes.** A signed delivery is recorded, marks the service
  stale, and the reconciler refreshes it within one interval — observed at ~45s.
  Bad and missing signatures are rejected 401 and still recorded
- Both databases up, PostGIS 3.4 working, Cal.diy healthy and migrated

- **The booking funnel sells.** Against a real Cal with `api-v2` deployed:

  | attempt | outcome | effect |
  |---|---|---|
  | complete sale | `CONFIRMED` 201 | booking written, day 12 → 11 slots |
  | same slot again | `REFUSED` 409 | availability miss recorded |
  | declined payment | `CHARGE_FAILED` 402 | **slot released**, still 11 slots |

  Cal ends with one `accepted` booking and one `cancelled`, and the released
  attempt's trail carries its compensating step
- 27 tests, covering every compensation path including the ones that only run
  after a compensation itself fails

Sketch:

- Payments run through a dev gateway that moves no money; Stripe Connect is not
  wired
- Provider onboarding and the frontend do not exist yet
- **Confirming a booking needs a paid Cal licence**, so we create auto-accepting
  event types and never need to. See ADR 0008 — this is the constraint most
  likely to shape what comes next

```bash
./seed/cal-dev.sh                              # supply, both sides
cd backend && mvn spring-boot:run              # reconciler fills the index
curl "http://localhost:8090/api/search?lat=59.32&lon=18.06&radius=5000&day=2026-08-22&when=AFTERNOON"
```

## Layout

```
docker-compose.yml     cal, cal-api (v2), redis, two databases
db/                    marketplace schema; 002 is applied by hand
docker/                build-api-v2.sh — no published image exists
seed/                  dev fixtures — cal-dev.sh seeds both sides
docs/decisions/        ADRs — why, not what
docs/design/           booking-funnel.md — the saga, stage by stage
backend/               Spring Modulith: search, sync, booking, payments
```

## Cal's api-v2

Availability reads work against the web image. **The booking funnel does not**:
that image serves a public booking-create endpoint and no authenticated confirm
or cancel, so a funnel running without api-v2 can reserve a slot and never
release it. Every failed payment then strands a pending booking that blocks a
real slot, permanently.

There is no published image, so it is built from the monorepo — tens of minutes
and several GB:

```bash
./docker/build-api-v2.sh              # clone + build, pinned to the web image version
docker compose up -d cal-redis cal-api
./seed/cal-api-key.sh salong-sodermalm # prints the key once; it is stored hashed

export MARKETPLACE_CAL_API_V2_URL=http://localhost:3001
export MARKETPLACE_CAL_API_KEY=cal_...
```

Two things that fail silently if got wrong. The api-v2 version **must match the
web image** — they share one database and one schema. And every request must
carry `cal-api-version: 2024-08-13`; without it Cal routes to an older
controller that has a cancel route and no confirm route at all.

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

1. The booking funnel, with the confirm-against-Cal step made explicit. This
   is the first write against Cal, and it is where ADR 0007 has to be answered
   again — a write may justify building `api-v2` even though reads did not.
2. Provider onboarding — Team + staff + services + Stripe KYC in one flow,
   replacing `seed/cal-dev.sql`, which writes Cal's schema directly and is a
   dev shortcut rather than a mechanism.
3. Payments.

Done: the reconciler in `backend/.../sync`, with tests.

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
