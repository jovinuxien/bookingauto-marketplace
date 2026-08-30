# What the platform does

An inventory, kept current: every feature that exists in the code, grouped by
who it is for. Derived from the controllers, schedulers, templates and
migrations rather than from memory — if it is not here, it is not built.
Update this file in the same commit as the feature.

Last revised: 2026-08-30 (commit `07a139e`).

## 1. For the customer (consumer site, no account needed)

| # | Feature | Where |
|---|---|---|
| 1.1 | **Search by category, city/radius, day and part of day** (morning/afternoon/evening) over a PostGIS availability index | `GET /api/search`, `/sok` |
| 1.2 | **Free-text search in Swedish** ("däckbyte imorgon förmiddag i Solna") — a model turns the sentence into a structured query, grounded against the real category list and synonyms; the page shows how it read the sentence | `GET /api/search/ask` (ADR 0012, 0013) |
| 1.3 | **Provider page** with services, prices, durations and **real bookable times read live from Cal** | `GET /api/providers/{slug}`, `GET /api/services/{id}/slots`, `/salong/{slug}` |
| 1.4 | **Checkout that holds the slot while paying**: reserve in Cal → verify → charge → confirm, with every failure compensated (release / refund) and idempotent on retry | `POST /api/bookings` (ADR 0005, 0009) |
| 1.5 | Swish-style **asynchronous payment**: slot held, `AWAITING_PAYMENT`, confirmed by webhook; abandoned checkouts released after 15 min | `AbandonedCheckoutSweeper`, Stripe webhook |
| 1.6 | **Registration number at checkout** when the service's category asks for one (bil & däck); refused with a 400 otherwise-empty, ignored for salons | `db/013` (ADR 0015) |
| 1.7 | **Confirmation e-mail** with a signed link to the booking — the mailbox is the account | `booking_confirmed` (ADR 0014) |
| 1.8 | **My booking page**: see the booking, the plate, and **cancel it** within the provider's cutoff; refund when due; salon told the slot is free | `POST /api/bookings/lookup`, `/cancel`, `/bokning` (db/010) |
| 1.9 | E-mails for every outcome: released, refunded, cancelled (with/without refund), needs attention | `notification_outbox` kinds |
| 1.10 | **SEO landing pages** per category × city (`/frisor/stockholm`, `/dackbyte/malmo` …) with JSON-LD `ItemList` (`HealthAndBeautyBusiness` or `AutoRepair`), canonical, sitemap, robots; only pages with providers exist | `landing`, `/sitemap.xml`, `/robots.txt` |
| 1.11 | **Seasonal tyre notice** on `/dackbyte/{city}` with the legal deadline (1 Dec / 15 Apr) chosen by today's date | `TyreSeason` |
| 1.12 | City index page `/orter` | `landing` |
| 1.13 | Rate limits on every public write (bookings, lookup, cancel, signup, login) | `ratelimit` |

## 2. Categories (the taxonomy)

| # | Feature | Where |
|---|---|---|
| 2.1 | **Eight categories in one table** with Swedish labels, URL paths and customer synonyms: `har`, `massage`, `hud`, `dack`, `bilservice`, `bilvard`, `bilglas`, `cykel` | `service_category` (db/009, 011, 015) |
| 2.2 | Which categories **ask for a vehicle** (`asks_vehicle`) — drives checkout, the funnel, the landing-page schema type and the noun (salonger/verkstäder) | db/013 |
| 2.3 | **One list of routed URLs** read by the route, the boot check and the security permit rule | `CategoryPaths.ROUTED` |
| 2.4 | Boot-time check that every category has a route and every route a category | `CategoryRoutes` |
| 2.5 | Public `GET /api/categories` for forms | `categories` |

## 3. For the provider (salon / workshop / bike shop)

| # | Feature | Where |
|---|---|---|
| 3.1 | **Self-serve signup**: name, address, e-mail, password, **what you sell** (category); nothing is created until the e-mailed link is clicked | `POST /api/signup`, `/verify`, `/registrera`, `/verifiera` (ADR 0011, db/007, 012) |
| 3.2 | On verification: **Cal account** created (username = slug), **Stripe Connect account** created, KYC link e-mailed, console login created | `onboarding`, `payments` |
| 3.3 | **Import services from Cal**: each event type becomes a service, **classified by title against synonyms**, unmatched titles fall to the provider's own signup default; unsafe event types (confirmation without slot hold) skipped and reported | `POST /api/providers/{id}/import-services` (ADR 0010, 0013) |
| 3.4 | Activation only when Stripe payouts are enabled and at least one service exists; deactivation when Stripe blocks payouts | `ConnectedAccountListener` |
| 3.5 | **Console**: summary (earned, commission, upcoming), upcoming bookings with customer, plate and — once looked up — make/model/year/tyres, and a list of attempts needing a human | `GET /api/console/*`, `/konsol` |
| 3.6 | Console **login** with lockout after repeated failures; owner and platform-admin roles; owner can add users | `/api/auth/login`, `/api/console/users` |
| 3.7 | **Work-order e-mail on every sale** with the registration number; e-mail on customer cancellation | `provider_booking_confirmed`, `provider_booking_cancelled` (db/014) |
| 3.8 | **Automatic geocoding** of the address after signup (sweep, bounded retries, never guesses a city centroid); operator can place by hand | `geo` (db/008) |

## 4. Vehicles (bil & däck)

| # | Feature | Where |
|---|---|---|
| 4.1 | Plate normalised (`abc-123` → `ABC123`), Swedish format recognised, foreign plates accepted | `RegistrationNumber` |
| 4.2 | **Registry lookup after confirmation** (never on the booking path): make, model, year, **tyre dimension front/rear** written onto the booking by a sweep with bounded retries | `BookingVehicles`, db/013, 016 |
| 4.3 | **`VehicleRegistryPort`** with two adapters: `DisabledVehicleRegistry` (default) and **`TicVehicleRegistry`** (api.tic.io, key in header, 404 = unknown, everything else = unavailable, foreign plates never sent) | `marketplace.vehicles.registry=none|tic` |

## 5. Platform internals (what keeps the above true)

| # | Feature | Where |
|---|---|---|
| 5.1 | **Cal owns time, we own money** — meet only at the booking; availability index rebuilt from Cal by a reconciler and by webhooks | ADR 0001, 0002, 0003 |
| 5.2 | Stripe Connect with platform commission (bps) frozen onto every attempt at sale time | `payments` (ADR 0003) |
| 5.3 | Full **attempt state machine** with an audit trail per transition (`booking_attempt_step`) | ADR 0005 |
| 5.4 | **Transactional outbox** for all e-mail; SMTP or logging transport; dedupe per event and kind | `notifications` (db/006) |
| 5.5 | **Hexagonal seams** for every third party: `CalPort`, `CalBookingPort`, `CalProvisioningPort`, `PaymentPort`, `StripeConnectPort`, `GeocoderPort`, `VehicleRegistryPort`, `SearchPort`, model access behind `AiGate` — each with a disabled/dev adapter | package `*Port.java` |
| 5.6 | Spring Modulith with declared dependencies, verified by a test | `ModuleStructureTest` |
| 5.7 | Dev stack in one command: containers, MailHog, stripe-mock, seeds | `./run.sh`, `seed/` |
| 5.8 | Bootstrap platform admin from environment on first start | `ConsoleBootstrap` |

## Not built (so nobody assumes it)

Reviews/ratings · per-vehicle pricing and regnr-first search (designed, ADR 0016) · add-on services ·
reschedule · provider-initiated cancellation · workshop widget · messaging ·
provider subscription tiers · mobile app · consumer accounts (by design, ADR 0014).
