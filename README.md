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
./run.sh
```

That is the whole thing: containers, frontend build, backend. Everything the
application needs is either defaulted in `application.yml` or read from `.env`.

| | |
|---|---|
| consumer site | http://localhost:8090 |
| salon console | http://localhost:8090/logga-in |
| mail (MailHog) | http://localhost:8026 |
| Cal | http://localhost:3000 |

Bootstrap the first operator once, then remove the variables:

```bash
MARKETPLACE_CONSOLE_BOOTSTRAP_ADMIN_EMAIL=you@example.se \
MARKETPLACE_CONSOLE_BOOTSTRAP_ADMIN_PASSWORD='...' ./run.sh
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

- **Payments are wired to Stripe Connect** as destination charges — commission
  as an application fee, the balance destined for the salon's account. Verified
  against `stripe-mock`: `amount 60000`, `application_fee_amount 9000`,
  `payment_method_types [swish card]`, `transfer_data.destination`
- **The funnel can wait.** Swish is a push payment, so a checkout returns
  `202 AWAITING_PAYMENT` with a client secret; a webhook completes the sale, a
  redelivery is a no-op, and an abandoned checkout is swept — slot held 12 → 11,
  returned 11 → 12, with no webhook involved
- 33 tests

- **Provider onboarding works.** A new salon was created (Cal user + Stripe
  connected account), built a service in Cal's UI, had it imported — with an
  unsafe event type skipped and the reason returned — activated on KYC, was
  indexed by the reconciler in ~5s, and appeared **first in search at 361 m**
- Module boundaries are verified by a test. It caught a real cycle the moment it
  was added
- **The consumer site exists.** React + TypeScript SPA in the JHipster layout —
  Redux Toolkit, react-router, axios interceptors — built by Maven into
  `target/classes/static` and served from the same jar, so there is one artefact
  and one origin. Search → salon → real times from Cal → checkout, with each
  funnel outcome given its own screen
- **The business console exists**, and with it the system's first authentication.
  A platform admin creates a salon login; the salon sees whether it is sellable,
  what it has earned, what the platform kept, its upcoming bookings, and
  anything stuck in `NEEDS_ATTENTION`. Verified: a salon cannot mint logins (403)
  and every console query is scoped to the session's provider, never to an id
  from the caller
- 34 tests

- **The pages a search engine has to read are HTML.** `/orter`,
  `/frisor/{city}` and `/salong/{slug}` are server-rendered with canonicals and
  JSON-LD, plus a generated sitemap. Every sitemap URL resolves 200 and every
  JSON-LD block parses — both were broken when first written, and neither
  failure was visible from the page

- **Customers now hear from us.** A transactional outbox: the message is
  written in the same transaction as the event that owes it, and a dispatcher
  delivers it. Verified over real SMTP into MailHog, with the Swedish subject
  correctly UTF-8 encoded

- **A salon can register itself**, and the ordering is the point: registration
  writes one row and sends one link, and creates no provider, no Cal user and no
  Stripe account. Clicking the link is what provisions. Verified end to end — a
  form at `/registrera`, a 202, a verification mail in MailHog, and after the
  click a Cal user, a Stripe connected account, a console login and a salon that
  can sign in and see its own summary. See ADR 0011
- 71 tests and 17 browser tests

- **Addresses become coordinates.** A sweep places salons that registered
  without them — never inline, so an OSM outage cannot fail a signup. Results
  coarser than a street are refused, because a geocoder answers an address it
  cannot find with the city centre, and a point that is wrong by kilometres is
  worse than no point at all. Verified against real signups: two Stockholm
  addresses matched at rooftop precision, and `Munins gata 6 lgh 1101` — which
  returns nothing at all as written — matched its street once the apartment was
  stripped. What no geocoder can place, an operator places by hand, and that
  placement then outranks the sweep permanently
- 84 tests

- **Search takes a sentence.** `/api/search/ask` puts an Embabel agent in front
  of the same PostGIS query: it reads the categories that actually exist, asks a
  model to read the text against that closed set, and then checks the answer —
  a category we do not have, a date in the past or past the index horizon, a
  part of day that is not one of ours are each dropped and named in the
  response. The plan is three steps and only the middle one is a model, so what
  reaches SQL is decided by ordinary Java. Verified: on Spring Boot 3.5 with the
  gate off and no API key, the backend starts, `/api/search/ask` answers 200
  with the plain geo query and the two Stockholm salons at 911 m and 2300 m, and
  `/api/search` is byte-for-byte unchanged. See ADR 0012, which lists what an
  agent may never do
- **Interpreted search is rate limited**, at 60 per hour per socket address.
  Over the limit is not a 429 — the search runs unfiltered, because what is
  being protected is an invoice rather than a resource. The counter is the one
  `signup` was already using; a second caller is what moved it into its own
  module. Verified with the limit set to 1 and a deliberately invalid API key:
  the first call reaches Anthropic and comes back 200 with `"we could not read
  that"`, the second and third are refused and come back 200 with the same
  salons, and the counter still increments on a refusal, so hammering the
  endpoint cannot reset the window
- **Every failure of the model is still a search.** A bad key returned HTTP 500
  before this was true: Embabel is Kotlin, and a failed call arrives as a
  *checked* `ExecutionException` through a signature that declares nothing, so
  `catch (RuntimeException)` compiled and missed it. The invocation now sits
  behind a seam declared `throws Exception`, which makes the compiler insist
- **The consumer site has the box.** A sentence sets the filters and then the
  filters are what the URL carries — so a refresh or a back button cannot
  silently buy another interpretation, and what gets shared is the search rather
  than the phrasing. What was understood appears above the results with the
  category as a one-click chip to remove, because a filter the customer cannot
  see is one they cannot correct. Touching any filter by hand takes the note
  down rather than letting us take credit for their edit. Verified in a browser
  with the gate off and again with a deliberately invalid key: the box appears,
  the sentence submits, the Swedish note renders, no error, and the results are
  the ones the plain search would have given
- **Categories are a table, and the marketplace sells more than hair.** Every
  service this system had ever imported was `har`, because the import wrote the
  configured default for every event type — so `/massage/{city}` and
  `/hudvard/{city}` were pages that could not exist, and the sitemap correctly
  omitted what 404s, which is exactly why nobody noticed. `service_category`
  now holds the slug, the URL path, the Swedish label and the words customers
  actually type; `service.category_slug` has a foreign key to it; and the
  import classifies from the event type's title instead of defaulting. The
  agent's prompt is the same list, so "balayage" is a lookup rather than a
  guess — and the grounding step still overrules it against the slugs. Verified
  end to end: `/massage/stockholm` renders "Massage i Stockholm" at 850 kr
  where the hair page shows 600, `/hudvard/stockholm` is still a 404 because
  nobody sells it, the sitemap lists both real pages, and
  `?category=massage` returns one salon where `?category=har` returns three.
  See ADR 0013
- **The login endpoint is counted**, at 30 attempts per hour per socket
  address, and the count happens *before* the password is checked. That
  ordering is the whole feature: verifying a password is deliberately slow, so
  an endpoint that checks first has already paid for the request it is about to
  refuse. Over the limit is a 429 — unlike interpreted search, there is no
  degraded answer to give someone signing in, and the SPA says so in Swedish
  rather than repeating "wrong password" at someone whose password is right.
  There is deliberately **no per-account limit**: refusing before the password
  is checked cannot tell a salon from someone guessing at it, so a bucket keyed
  on the email address would be a lockout anyone could trigger against any
  salon whose address they knew. A distributed guess at one account is
  therefore bounded by password strength and not by this — a gap written down
  rather than closed, because closing it that way costs more than it buys
- **A customer can reach their booking and undo it.** The confirmation email
  carries a signed link, and that link is the whole of consumer identity — no
  account, no password, no `consumer` table. An HMAC over the booking id and the
  address it was sent to, derived rather than stored, because unlike a
  verification link it is opened repeatedly and has no state anyone would spend.
  Cancelling is free until 24 hours before and after that the slot still comes
  back while the money does not; the number is shown before the button, and is
  frozen onto the booking at sale time so a later policy change cannot rewrite a
  completed sale. `booking.status` has allowed `cancelled` and `refunded` since
  db/002 and this is the first thing that writes them. See ADR 0014.

  The order is the design: claim the row, release the slot, then refund. Claim
  first because two open tabs both read a confirmed booking and a status check
  in Java lets both past. **Cal before Stripe**, which is the reverse of the
  funnel's own `refundAndStop` and right in both places — there the sale never
  completed, here the customer has asked to lose the appointment, so delivering
  that and owing them money is flagged and fixable, while the opposite leaves a
  live appointment nobody expects to be kept.

  Verified end to end against real Cal: booking 14 two days out came back
  `refunded` with Cal's row moving `accepted` → `cancelled` and a control
  booking untouched; a second click returned 200 with exactly one refund and one
  email; a booking moved inside the 24-hour window cancelled as `cancelled` with
  no refund and mailed both the customer and the salon; a forged signature, a
  valid signature pointed at a neighbouring id, and an unknown booking are all
  404 and told apart nowhere the caller can see
- **The browser suite earned its place a second time.** `/bokning` was permitted
  in `SecurityConfig` and routed in the SPA and returned a plain 404, because
  nothing reaches React until the path is also forwarded to `index.html` in
  `WebConfig` — whose existing comment predicts exactly this mistake. Every
  backend test passed throughout
- **A salon hears when a slot comes free**, which is the first message this
  system has ever sent to a salon rather than a customer. It reads
  `provider.contact_email`, not `provider.email`: the latter is db/001's
  marketing column and is null on every row, so the obvious query compiles,
  runs, and silently never notifies anyone
- 146 tests and 26 browser tests

Sketch:

- **Nothing tests the seed, and it had been broken for a while.**
  `seed/dev.sql` inserted providers
  as `active` with no Stripe account, which `provider_sellable_check` has
  forbidden since db/004 — and `ON CONFLICT` does not save it, because a CHECK
  is evaluated before conflict arbitration. It also wrote Cal event type ids as
  literal 1, 2, 3, which holds only on a Cal nobody has onboarded into; any
  import takes those ids first and the fixture then points its services at
  someone else's event types, which Cal answers with an empty slot map and no
  error. Both are fixed — the ids are read back from Cal, and the fixture
  satisfies the invariant rather than routing around it — but both were found by
  running it, and nothing would catch the next one

- **No model has ever answered.** The path is proven as far as the API boundary
  — with the gate on and a deliberately invalid key, a real Swedish sentence
  reaches `api.anthropic.com`, is rejected 401, and comes back to the customer
  as results — so the wiring, the retry, the counter and the fallback are all
  exercised. What has never happened is a model reading the sentence and the
  grounding step overruling it on live output. The 13 grounding tests use
  hand-written interpretations, which proves the rules and not the prompt. Set a
  real `ANTHROPIC_API_KEY` with `MARKETPLACE_AI_ENABLED=true` and the first
  query is the first proof

- **A salon still cannot cancel.** The console is read-only, so a salon that
  needs to drop an appointment does it in Cal's own UI, and our `booking` row
  goes on reading `confirmed` for something that no longer exists. The webhook
  that marks availability stale does not touch `booking`. True before ADR 0014
  and made more visible by it

- **Nothing reads `needs_attention`.** A cancellation whose refund failed sets
  the flag and logs loudly, and is then found by reading logs. The console's
  attention screen shows stuck *attempts* and not stuck cancellations — the same
  gap `availability_miss` has

- Stripe is exercised only against `stripe-mock`, which validates request shape
  and nothing else. Real Swish redirection, webhook signatures, Connect
  onboarding and payouts need a Stripe test account
- Provider onboarding leaves the salon with two logins, ours and Cal's — the
  cost of not having a Cal licence. See ADR 0010
- Geocoding uses the **public** Nominatim instance by default, which permits one
  request per second and forbids bulk use. Fine for a handful of salons a day;
  point `MARKETPLACE_GEOCODING_NOMINATIM_URL` at a self-hosted instance before
  that stops being true
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
db/                    marketplace schema; 002 onwards are applied by hand
docker/                build-api-v2.sh — no published image exists
seed/                  dev fixtures — cal-dev.sh seeds both sides
docs/decisions/        ADRs — why, not what
docs/design/           booking-funnel.md — the saga, stage by stage
db/010                 consumer cancellation -- the terms, and what happened
db/011                 bil & däck -- four categories, ADR 0015
db/012                 the salon says what it sells; the import believes it
db/013                 which car is coming -- the plate, and a port for the rest
backend/               Spring Modulith: search, sync, booking, payments,
                       onboarding, console, signup, landing, notifications, geo,
                       ai (whether a model may be called; no agents live there),
                       ratelimit (a bucket, a window and a count),
                       categories (what a salon sells, and its URL),
                       vehicles (which car is coming; a port with no vendor yet)
  src/main/webapp/app/ React SPA — config/, shared/, modules/; built into the jar
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

1. A Stripe test account, to verify the half that `stripe-mock` cannot: real
   Swish redirection, webhook signatures, Connect onboarding, payouts.
2. Reschedule, and letting a salon cancel. ADR 0014 deliberately did neither.
   Rescheduling is a cancel and a re-book against a live availability query,
   with a failure the two halves do not have on their own; a salon cancelling
   is a question about who owes the customer what.
3. HTML email. The messages are plain text, which is honest and legible but
   not what a consumer brand ships.
4. Read `needs_attention` and `availability_miss`. Both are written faithfully
   and read by nothing, so a customer owed money is currently found by reading
   logs.

Done: the availability reconciler, the booking funnel with its compensations,
Stripe Connect with the asynchronous payment path, provider onboarding,
self-serve signup, geocoding, the limit on the login endpoint, and consumer
cancellation.

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

## Running the frontend

```bash
cd backend && npm install
npx vite            # dev server on :3002, proxies /api to :8090
npx vite build      # builds into target/classes/static
mvn -Dskip.npm=false package   # or let Maven do it
```

Backend-only builds pass `-Dskip.npm=true`, which is the default.

## Tests

```bash
cd backend
mvn test              # 146 — logic, module boundaries, every compensation path
npx playwright test   # 26 — what a person actually sees, needs a running stack
```

The browser suite exists for one reason. The landing pages once returned
correct HTML with a 200 on every URL, and showed "Sidan finns inte" in a
browser: the SPA mounted into the element the server had rendered into and the
router had no matching route. Nothing else here runs JavaScript, so nothing
else could have caught it — verified by reintroducing the bug and watching curl
stay green while the browser tests failed.

It uses the Chrome already installed rather than downloading its own, and steps
forward to an open day rather than assuming today is one: the seeded salons are
closed at weekends, and a suite that fails two days in seven gets ignored. That
day-stepping is the suite's one flaky spot — under parallel load it can walk
past an open day whose slots have not rendered yet, and passes on a rerun.

The signup tests stop deliberately before clicking a verification link. That
half creates a Cal user and a Stripe connected account, neither of which is
cleaned up, and a browser suite that leaves accounts behind in other systems on
every run is one people start skipping. What they do cover is the property that
makes the endpoint safe to expose: registering creates nothing.

## The security model

Adding Spring Security made everything deny-by-default, so the public surface is
now listed on purpose in `console/SecurityConfig`. Four categories:

| | |
|---|---|
| the SPA and the consumer journey | anonymous — requiring an account to see availability would cost more bookings than it could protect |
| `/internal/**` | verified by signature, not by session |
| `/api/console/**` | authenticated, scoped to the session's provider |
| `/api/signup/**` | anonymous, rate limited, and provisions nothing until a link sent to the address is clicked |
| `POST /api/auth/login` | anonymous, and counted per source address before the password is checked |
| `POST /api/bookings/lookup`, `/cancel` | anonymous, authorised by an HMAC in the body — never in a URL, so it stays out of access logs and `Referer` |
| `POST /api/providers` | platform admin only — it creates the Cal and Stripe accounts immediately, which is exactly why the public path is `/api/signup` |

Sessions rather than tokens: the SPA ships in the same jar, so the cookie is
first-party and `HttpOnly` and no script can read it. CSRF protection is
therefore real and stays on — disabled only where a cookie plays no part.

Bootstrap the first operator with
`MARKETPLACE_CONSOLE_BOOTSTRAP_ADMIN_EMAIL` / `..._PASSWORD`. It runs once,
does nothing if an admin exists, and logs a warning telling you to remove it.

## Notifications

Cal sends nothing: its image has no `sendmail` binary, so its own confirmations
fail silently — which is why, until now, a customer who booked heard nothing at
all. If Cal is ever given working SMTP, both will send and the duplicate has to
be resolved deliberately rather than discovered.

```bash
docker compose up -d mailhog          # :8026 for the web UI
MARKETPLACE_NOTIFICATIONS_TRANSPORT=smtp mvn spring-boot:run
```

The transport defaults to `smtp` at `spring.mail.host`, which itself defaults to
localhost — where docker-compose runs MailHog. That is safe for the reason that
matters: production has no relay on localhost, so a misconfigured deployment
fails loudly into the outbox and retries, and reaching real customers still
means deliberately pointing `MAIL_HOST` at a real server. Set
`MARKETPLACE_NOTIFICATIONS_TRANSPORT=log` to silence delivery entirely.

## Placing a salon on the map

A salon that registers itself gives a street address. The radius search that is
this product's primary filter needs a point, so something has to turn one into
the other.

```bash
MARKETPLACE_GEOCODING_PROVIDER=nominatim ./run.sh
```

Off by default. Enabling a geocoder by default would point every developer
machine and every CI run at a public service with a strict usage policy, from an
application whose whole job is to submit addresses to it.

Three things about it are deliberate:

- **It never runs during signup.** A sweep picks up salons that arrived without
  coordinates, in small spaced batches. Geocoding inline would put a third
  party's uptime directly in front of a registration, so an OSM outage would
  become a salon that could not join. Here it becomes a salon that is unfindable
  for a few minutes longer.
- **It refuses to guess.** Asked for an address it cannot find, a geocoder
  answers with the city — a point that looks right on a map and is wrong by
  kilometres. Only matches at street or building precision are stored, on an
  allow-list of result types rather than a deny-list, because the failure mode
  being guarded against is the one nobody anticipated. Everything else is left
  null and handed to a person.
- **A person always outranks the sweep.** Real addresses exist that no geocoder
  will match: a new building, a unit inside a shopping centre, a salon in
  someone's home. Once an operator has placed one, no later geocode moves it.

Addresses are normalised before being sent — apartment, floor and care-of are
removed, because map data does not model them. This is not cosmetic:
`Munins gata 6 lgh 1101` is a real registration on this platform and returns
nothing at all as written.

```bash
# what still has no point, and why
curl -b cookies http://localhost:8090/api/placements

# place one by hand
curl -b cookies -X PUT http://localhost:8090/api/placements/10 \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"latitude":55.5601,"longitude":12.9830}'
```

Both are platform-admin only, and both sit under `/api/placements` rather than
`/api/providers` on purpose: that prefix carries a public GET rule for the
consumer catalogue, and an operator listing of unplaced salons — addresses
included — behind an earlier `permitAll` matcher is a leak that compiles, passes
every test and is invisible.

## Self-serve signup

A salon registers itself at `/registrera`. The ordering is the entire design and
is inverted from the usual one:

**Registration creates nothing.** It writes one row to `provider_signup` and
sends one link. No provider, no Cal user, no Stripe connected account. Clicking
the link is what provisions.

The common shape — create the account, mark it unverified, sweep up later — is
easier and wrong here, because the sweeping would have to happen in systems we
cannot sweep. A Stripe connected account created for an address nobody owns is
not ours to delete. See ADR 0011.

```bash
curl -X POST http://localhost:8090/api/signup -H 'Content-Type: application/json' \
  -d '{"salonName":"Klipp & Kaffe","email":"nina@example.se",
       "password":"ett-riktigt-langt-losenord","addressLine":"Bondegatan 12",
       "postalCode":"116 33","city":"Stockholm"}'
# 202, always. Then read the link out of http://localhost:8026
```

Three things about it are deliberate and easy to undo by accident:

- **It answers 202 whether or not the address already has an account.** Anything
  else turns the form into a way to ask which salons are on the platform, which
  is what the login endpoint already refuses to answer. The difference is told
  only to the mailbox that owns the address.
- **Rate limits are in the database, keyed on the socket address.** In memory
  they would reset on every deploy; keyed on `X-Forwarded-For` the caller would
  set them for themselves. Behind a proxy, set `server.forward-headers-strategy`.
- **A failed verification leaves the link working.** The address was proved by
  the click and does not become unproved because Stripe timed out, so
  provisioning is resumable and a transient outage is a second click rather than
  a support ticket.

The salon ends up with two passwords — ours, which it chose, and Cal's, which is
generated, shown once and emailed once. Different on purpose: reusing the
console password on a third-party system would make one breach into two.

```bash
# what is in flight, and what got stuck
docker exec -it bm-market-db psql -U market -d marketplace \
  -c "SELECT id, email, slug, state, provider_id, attempts, failure FROM provider_signup ORDER BY id"
```
