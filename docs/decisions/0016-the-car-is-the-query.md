# 16. The car is the query, and the price depends on it

**Status:** accepted — phase 1 (the cache and `GET /api/vehicles/{plate}`) built in `db/017`; phases 2–3 (the search box, price rules) pending

**Builds on** ADR 0015, which put the registration number on the booking and
a registry behind a port, and on ADR 0003, which says money is written only
here. This ADR is what the bil & däck vertical needs to sell *bilservice*
rather than only tyres and glass: a front door that starts with the car, and
a price that is allowed to depend on it.

## Context

Today the customer is asked three things — what, where, when — and the plate
comes last, at checkout, as an attribute for the workshop. The service has one
price, imported from its Cal event type, and that price is what the customer
sees and what is frozen onto the attempt.

That is exactly right for a salon and nearly right for a däckverkstad. It is
wrong for bilservice, where "Service" on a Fiat 500 and on a Range Rover are
different jobs at different prices, and where every marketplace that works —
Lasingoo above all — begins with the registration number for that reason. A
workshop asked to publish one price for "Service 15 000 km" will either
publish a price that loses money on half its customers or refuse to publish
at all, and the second is what our one `bilservice` provider would do.

Two things are missing, and they are separable:

1. **A way in that starts with the car.** Type the plate first; know the car
   before any offer is made.
2. **A price that can vary by car.** A rule per service, matched against what
   the registry said.

The first without the second is cosmetic. The second without the first is
unreachable — nobody can be quoted a per-car price before the car is known.
They are one ADR because they are one feature; they are two phases because
the first is small and the second is where the product is.

## Decision

### 1. The plate is looked up once, and remembered

**A `vehicle` table, keyed by normalised plate,** holding what the registry
answered — make, model, model year, tyre dimensions front and rear — with
when it was looked up and by which adapter. Nothing about the owner, as
ADR 0015 already refuses.

Every lookup, from any path, goes through this table first. A hit is served
from it; a miss asks `VehicleRegistryPort` and writes the answer, including
the *negative* answer ("registry does not know this plate", with a
timestamp) so a mistyped plate is not asked about on every keystroke.

This is the difference between a registry on the booking path and a
registry off it. ADR 0015 kept lookups to a sweep because the entry-tier
plan is 3 000 calls a month and a synchronous call can fail. Both remain
true. What changes is that a car is asked about **once per car**, not once
per booking and not once per page view; a December full of returning
customers costs one lookup per car per twelve months (rows older than a
year are re-asked, since tyres and plates change hands). The sweep in
`BookingVehicles` becomes a second reader of the same cache and stops
calling the registry itself.

**`GET /api/vehicles/{plate}`** is the public face of it: plate in, make /
model / year / tyres out, 404 when unknown, **503 with `Retry-After` when
the registry cannot be asked and the cache has nothing.** Rate-limited per
IP like every public endpoint, and harder than most — it is a paid call
behind a free URL, the same shape as `/api/search/ask` and guarded the same
way (ADR 0012's `AiGate` reasoning applies: a cost the platform pays on
behalf of anonymous traffic needs a ceiling).

**Privacy.** A plate alone is not personal data; a plate joined to an
e-mail and a time is, and that join already exists on `booking` and is
already covered. The `vehicle` table adds no join. What it must never grow
is an owner column, and the port's `Vehicle` record is what enforces that.

### 2. The search page starts with the car when the category asks

`/sok` gains a registration-number field, shown first for the vehicle
categories and not at all for the others. Entering a plate calls
`/api/vehicles/{plate}` and the page then knows the car. The search request
itself does not change — `SearchRequest` is still place, category, day and
part of day — because the car does not change *which workshops* are near
you. It changes what they will charge, which is the next section, and it
follows the customer to checkout so the plate is never typed twice.

The provider page (`/salong/{slug}?regnr=ABC123`) and the slots call carry
the plate the same way, and checkout pre-fills it. The funnel's existing
rule — a vehicle category refuses to sell without a plate — is untouched;
the plate simply arrives earlier.

### 3. A service has a list price and zero or more rules

**A `service_price_rule` table.** Each row belongs to one service and says:
*for a car matching these constraints, the price is this.*

| column | meaning |
|---|---|
| `make` | exact, case-folded; null = any |
| `model_prefix` | "V70", "MODEL 3"; matched as a prefix of the registry's model; null = any |
| `model_year_from`, `model_year_to` | inclusive; null = open |
| `rim_inches_from`, `rim_inches_to` | parsed from the tyre dimension ("215/55R16" → 16); null = open |
| `price_minor` | the price for a match |
| `label` | what the customer sees: "16–17 tum", "Volvo 2015–2019" |

**Matching is deterministic and modelless**, like `Categories.classify` and
for the same reason (ADR 0013): it runs on the checkout path and on a page
a customer is looking at, and it must not depend on a model being reachable
or paid for. The most specific matching rule wins — specificity is the count
of non-null constraints — and ties break on the lower price, which is the
tie a customer would break. No match, or no rules at all, means the list
price. **A service with no rules behaves exactly as it does today**, which
is what keeps every salon and every existing booking outside this ADR.

Two dimensions were chosen and two were left out. Make/model/year covers
bilservice; rim size covers däck, where the wheel and not the car is what
the price follows. Fuel type and engine were left out because no provider
has asked for them and each column is a column forever. Adding one later is
a migration and a line in the matcher.

**The price is computed on the server, from the plate, at the moment of
quoting.** The client never sends a price. Checkout shows the price the rule
gives for the plate entered; the funnel recomputes it from the same rule and
the same cached vehicle when the attempt is started, and *that* is what is
frozen (ADR 0005's stage 5, unchanged in spirit). If the two disagree — a
rule edited between the page and the click — the attempt is refused with
the new price shown, not silently charged at either.

**When the car is unknown**, because the registry does not know the plate or
could not be asked and the cache is empty, the list price applies and the
page says so: *"Pris för din bil kunde inte hämtas — listpris visas."* A
workshop that would rather not sell at list price to an unknown car sets
its list price to what it charges an unknown car, which is the honest
number.

### 4. Rules are the provider's, and edited in the console

Money is written only here (ADR 0003), so the rules do not live in Cal, and
the import (ADR 0010) does not touch them: an event type's price stays the
list price, and re-importing never deletes a rule. The console gains a
pricing page per service — list price, rules, and a **"vad kostar det för
ABC 123?"** box that runs the real matcher so a workshop can check its own
rules against a real car before a customer does.

Operator-entered rules through the same endpoint, role-checked, for the
first workshops we onboard by hand.

## What this does not do

- **It does not compute prices from manufacturer service schedules.** That
  is Lasingoo's calculator — parts lists, hourly rates, fluids — and it is
  years of data licensing, not a table. A workshop here types the rules it
  already has in its head ("Volvo 2015–19, 2 490 kr"). Lower ceiling, no
  dependency, and honest about what a marketplace with one workshop can
  promise.
- **It does not change search ranking.** Results are still ordered by
  distance and time. Price appears on the card once the car is known; it
  does not sort the card. Sorting by price is a product decision that
  interacts with reviews, which do not exist (ADR pending), and it waits
  for them.
- **It does not add mileage.** Lasingoo asks for it because service
  intervals depend on it. Our rules do not, yet; asking for a number nothing
  uses would be theatre.
- **It does not put the registry on the *booking* saga.** The funnel reads
  the cache. If the cache is empty at that point — possible only if the
  customer bypassed the page — the list price applies, which is the same
  rule as above.

## Consequences

The hexagonal rule (ADR 0015) holds and is why this is cheap: the registry
is still `VehicleRegistryPort`, now with a cache in front of it and two
readers behind. TIC's quota stops being a per-booking cost and becomes a
per-car cost, which is the difference between an entry plan and an
enterprise one.

The quote stops being a column and becomes a function of (service, vehicle).
Everything downstream — commission, the attempt, the receipt, the refund —
already reads the frozen number and does not care where it came from.

`bilservice` becomes sellable. That was the point.

## Watch items

- **A stale cache is a wrong tyre size.** A car re-registered on a new
  plate, or re-shod on different rims, is answered from a row up to a year
  old. The console's "vad kostar det för…" box shows the cached car, so a
  workshop can see the staleness; nothing yet lets a customer say "that is
  not my car". A "fel bil?" link that forces a fresh lookup is the obvious
  next step and deliberately not in this ADR.
- **Rules can contradict.** Two rules of equal specificity and equal price
  are indistinguishable and harmless; two of equal specificity and different
  prices resolve to the cheaper, silently. The console should show the
  matcher's choice, and does, but only for plates someone types in.
- **The 503 is a customer-visible outage of a third party.** If TIC is down
  on the first of December, the regnr box says so and the page still works
  at list prices. Worth a log line counted per hour, so the day it happens
  is a number and not an impression.
- **`db/013`'s `vehicle_*` columns on `booking`** become a copy of a
  `vehicle` row. They stay — the booking is what was sold, frozen, and a
  later re-lookup must not rewrite history — but the sweep that filled them
  now copies from the cache rather than calling out, and the migration that
  adds the table should say so in its header.
