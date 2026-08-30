# 15. A second vertical: bil & däck

**Status:** proposed

**Builds on** ADR 0013, which made categories a table so that adding one is a
row and a word rather than a code change in three modules. This is the first
time that promise is called on for something that is not hairdressing.

## Context

Everything this system knows how to do — hold a slot in Cal, charge before
confirming, find a provider within a radius at a time of day, read a sentence
and turn it into a category — was built for a chair in a salon. None of it
knows that it was.

bokadirekt.se, the reference this marketplace is modelled on, covers beauty
and wellness: hår, massage, naglar, fransar & bryn, hudvård, skönhet, hälsa.
Bokamera, its B2B cousin, lists the industries that *buy* booking software:
bilverkstad, cykelverkstad, körskola, uthyrning, camping. The first list has a
consumer marketplace on top of it. The second does not.

The question is which of those industries has the same shape as a salon, so
that the architecture carries over rather than bends. The shape is:

- **one resource, one fixed-duration slot** — a chair, a lift, a bay;
- **a price known before the visit**, so it can be charged up front;
- **chosen by proximity and time**, not by reputation or by browsing;
- **a no-show that costs the provider the slot**, so up-front payment is a
  feature to them rather than friction.

Rentals and camping fail the first point (multi-day, inventory). Driving
schools fail the second (packages, licensing). Healthcare beyond wellness
fails on patient-data rules this system has no answer to. Vehicle service
passes all four.

### Why bil & däck, specifically

Swedish law requires winter tyres between 1 December and 31 March and forbids
studded tyres after 15 April. Every car in the country changes wheels twice a
year, in the same two fortnights, and the person doing it searches "däckbyte
nära mig" with a date in mind. That is the search this system was built to
answer — `/{category}/{city}` with a radius and a part of day — and it is
worth more here than for "balayage", where the customer has a salon already.

The supply side is thousands of independent workshops on paper calendars or on
B2B tools with no consumer front. It is where salons were before 2012.

## Decision

**Four categories, seeded in `db/011`, routed in `CATEGORY_PATHS`, and
nothing else changes.**

| slug | path | label | what a workshop calls it |
|---|---|---|---|
| `dack` | `dackbyte` | Däckbyte | däckskifte, hjulskifte, däckhotell, balansering |
| `bilservice` | `bilservice` | Bilservice | oljebyte, bromsbyte, AC-service, hjulinställning, förbesiktning |
| `bilvard` | `bilvard` | Bilvård | rekond, biltvätt, polering, lackskydd |
| `bilglas` | `bilglas` | Bilglas | stenskott, vindrutebyte |

Sort order starts at 100, so the vehicle categories follow the beauty ones in
any list that shows all of them, and there is room between the two blocks for
what comes later without renumbering.

### What carries over untouched

- **Cal owns time.** A lift is an event type with a length, exactly as a chair
  is. "Däckbyte 30 min" is a title, and `Categories.classify` reads it the
  same way it reads "Klippning dam 45 min" — longest matching synonym wins.
- **The money boundary** (ADR 0003, 0005, 0009). A tyre change is a fixed
  price charged before confirmation. Nothing in `AttemptState` cares what was
  bought.
- **Search** (ADR 0006). Radius, day, part of day. The agent's vocabulary
  (ADR 0012) grows by four lines and is grounded against the same closed set.
- **Landing pages.** `/dackbyte/{city}` is the same template as
  `/frisor/{city}`. The route alternation gains four words; `CategoryRoutes`
  checks them against the table at boot as it does today.

### What is deliberately not in this ADR

- **A vehicle on the booking** — deferred here, then done in `db/013`: a
  category says whether it `asks_vehicle`, checkout asks for the plate when
  it does, the funnel refuses to sell such a service without one and freezes
  it onto the attempt and the booking as typed, and a `vehicles` module
  sweeps confirmed bookings through `VehicleRegistryPort` afterwards to fill
  in make, model and year. The workshop sees the plate in its console at
  once and the car once a registry has answered — which, with the disabled
  adapter, is never, and the booking is no worse for it.
- **A per-vertical default category** — deferred here, then done first, in
  `db/012`: the signup form asks what the salon sells, the answer becomes
  `provider.default_category_slug`, and the import prefers it over the
  configured `har`. Without it a workshop whose event types match nothing
  became a hairdresser, which is the one thing that had to be wrong before
  any workshop could onboard.
- **Seasonal copy.** `/dackbyte/stockholm` should say the dates. The label
  column holds a name, not a paragraph, and where seasonal copy lives is a
  landing-page question.
- **Cykel.** Bokamera lists bike repair and it is the same shape. It is left
  out because its natural synonyms — "punktering", "service" — collide with
  the vehicle ones, and a bicycle shop's "Punktering" landing in `dack` is
  worse than it landing in the default. It needs its own words chosen with
  care, which is a small job and a separate one.

### The one new third party goes behind a port

Every outside system this application talks to is reached through exactly one
interface on our side of the seam — `CalPort`, `PaymentPort`,
`GeocoderPort`, `SearchPort`, the model behind `search` — with the vendor
adapter beside it and, where the vendor is optional, a disabled adapter that
the application runs on without it (`DisabledGeocoder`). That is the
hexagonal rule this codebase already keeps, and this vertical does not get to
be the exception.

The vehicle vertical brings in one thing that is genuinely outside: looking a
registration number up to get make, model and tyre dimension. Transportstyrelsen,
biluppgifter.se and car.info are all plausible vendors and none of them is a
decision worth making now. The vehicle attribute (`db/013`) arrived as:

- a `vehicles` module with a `VehicleRegistryPort` — `Optional<Vehicle>
  lookup(RegistrationNumber)`, empty when the registry does not know the
  plate, `RegistryUnavailable` when the registry could not be asked, the
  same two-outcome shape `GeocoderPort` uses and for the same reason;
- a `DisabledVehicleRegistry` as the default, so that a booking proceeds
  with the plate a customer typed and nothing looked up; one adapter per
  vendor when one is chosen, selected by `marketplace.vehicles.registry`;
- **off the provisioning and booking path.** A workshop can be onboarded and
  a slot can be charged without the registry answering. The lookup enriches
  the attempt, as geocoding enriches a provider — by a sweep or on request,
  never inline in the saga (ADR 0005, 0011).

The categories themselves need no port. They are rows, and the only third
party that reads them is the model, which already sits behind ADR 0012's
seam.

## Consequences

The table earns its keep: a second industry is a migration and a constant,
and no module learns that it exists. If that turns out to be false — if some
code path assumes a salon — that is a finding worth an ADR of its own, and
this migration is how it gets found.

The categories share one flat list with beauty. A customer typing "service"
into the search box on a page about hairdressers will be offered `bilservice`,
because the agent sees one vocabulary. That is correct for the marketplace and
odd for the page, and it is the first real pressure on ADR 0013's "not a
hierarchy" — worth watching, not worth solving yet.

## Watch items

- **No registry answers yet.** `vehicle_make` stays null on every booking
  until a vendor is chosen and an adapter written. The plate is the useful
  part and it is there from the first booking; the sweep is the mechanism
  waiting for something to sweep with.
- **Synonym collisions across verticals.** `vaxning` is skincare and also
  what a detailer does to paint; it stays with `hud`, and car wax is
  `lackskydd` / `polering`. `service` on its own is claimed by `bilservice`
  because "Service 15 000 km" is how workshops name the thing, and no salon
  names an event type "Service". The day one does, the import result shows it.
- **The default, for operators.** Self-serve providers choose one; an
  operator creating a provider by hand may leave it out and gets the
  configured `har`. Visible in the import result; nothing makes them look.
- **`db/011` is applied by hand**, like everything from 002 onwards. It only
  inserts rows, so running the application first costs nothing worse than a
  boot warning about four routes with no category.
