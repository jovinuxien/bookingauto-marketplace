-- The availability index.
--
-- This is the piece the whole product rests on. Cal computes availability per
-- event type, on request, from calendars and buffers and existing bookings.
-- That is correct and fast enough for one booking page. It cannot answer
-- "salons near me with a free slot for a 45 minute colour on Saturday
-- afternoon" across ten thousand providers, because that would be ten thousand
-- availability computations.
--
-- So search reads this table and nothing else. It is denormalised, approximate,
-- and allowed to be stale. It narrows ten thousand providers to roughly twenty.
-- Cal is then asked the real question for those twenty, and only Cal confirms.
--
-- The distinction is not academic: a provider shown as free who is not is a bad
-- search result, while a booking confirmed against this table is a
-- double-booking that nobody can explain afterwards.

CREATE EXTENSION IF NOT EXISTS postgis;

-- --------------------------------------------------------------- providers --
-- A salon or clinic. Mirrors a Cal Team; cal_team_id is the join.
CREATE TABLE provider (
    id              bigserial PRIMARY KEY,
    cal_team_id     integer     NOT NULL UNIQUE,
    slug            text        NOT NULL UNIQUE,
    name            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft', 'active', 'suspended')),

    -- Marketing data. Deliberately not in Cal: it is not scheduling data and
    -- putting it there would mean forking Cal's schema to hold it.
    description     text,
    phone           text,
    email           text,

    address_line    text,
    postal_code     text,
    city            text,
    country         char(2)     NOT NULL DEFAULT 'SE',

    -- geography, not geometry: distances come out in metres on a sphere,
    -- which is what "within 2 km" actually means.
    location        geography(Point, 4326),

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX provider_location_idx ON provider USING gist (location);
CREATE INDEX provider_city_idx     ON provider (city) WHERE status = 'active';

-- ---------------------------------------------------------------- services --
-- A bookable service. Mirrors a Cal EventType; cal_event_type_id is the join.
CREATE TABLE service (
    id                 bigserial PRIMARY KEY,
    provider_id        bigint      NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    cal_event_type_id  integer     NOT NULL UNIQUE,

    name               text        NOT NULL,
    category_slug      text        NOT NULL,
    duration_minutes   integer     NOT NULL,

    -- The displayed price. Cal knows the booking; what it costs is a
    -- marketplace decision, and it changes without touching the calendar.
    price_minor        integer     NOT NULL,
    currency           char(3)     NOT NULL DEFAULT 'SEK',

    active             boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX service_provider_idx ON service (provider_id);
CREATE INDEX service_category_idx ON service (category_slug) WHERE active;

-- --------------------------------------------------- the index proper ------
-- One row per provider per service per day. Not per slot: slot-level rows would
-- be enormous and would go stale within seconds. Day-level answers the question
-- search actually asks — "is it worth showing this provider at all" — and the
-- exact times come from Cal for the shortlist.
CREATE TABLE availability_day (
    provider_id     bigint      NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    service_id      bigint      NOT NULL REFERENCES service (id)  ON DELETE CASCADE,
    day             date        NOT NULL,

    has_capacity    boolean     NOT NULL,
    first_free_at   timestamptz,
    free_slots      integer     NOT NULL DEFAULT 0,

    -- morning / afternoon / evening, so "Saturday afternoon" is a cheap filter
    -- rather than a scan of every slot.
    free_morning    boolean     NOT NULL DEFAULT false,
    free_afternoon  boolean     NOT NULL DEFAULT false,
    free_evening    boolean     NOT NULL DEFAULT false,

    -- Staleness is a first class property here, not an accident. Anything the
    -- reconciler has not touched recently is suspect, and this column is what
    -- lets you find it.
    computed_at     timestamptz NOT NULL DEFAULT now(),
    source          text        NOT NULL DEFAULT 'reconcile'
                    CHECK (source IN ('webhook', 'reconcile', 'backfill')),

    PRIMARY KEY (service_id, day)
);

CREATE INDEX availability_search_idx
    ON availability_day (day, has_capacity)
    WHERE has_capacity;

CREATE INDEX availability_stale_idx
    ON availability_day (computed_at);

-- ------------------------------------------------------- webhook receipts --
-- Every Cal webhook, recorded before it is acted on.
--
-- Webhooks are missed. They are missed on redeploys, on network blips, and
-- whenever the receiver is briefly slower than the sender's timeout. Treat them
-- as a latency optimisation over the reconciler, never as the mechanism, and
-- keep the receipts so a gap can be proven rather than guessed at.
CREATE TABLE webhook_receipt (
    id             bigserial   PRIMARY KEY,
    cal_event      text        NOT NULL,
    payload        jsonb       NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    error          text
);

CREATE INDEX webhook_unprocessed_idx
    ON webhook_receipt (received_at)
    WHERE processed_at IS NULL;

-- ------------------------------------------------------------- accuracy ----
-- "Shown as available, turned out not to be."
--
-- The number that quietly decides whether consumers come back, and invisible
-- unless it is recorded at the moment it happens: search offered a provider,
-- the customer chose it, Cal said no.
CREATE TABLE availability_miss (
    id            bigserial   PRIMARY KEY,
    provider_id   bigint      NOT NULL REFERENCES provider (id) ON DELETE CASCADE,
    service_id    bigint      NOT NULL REFERENCES service (id)  ON DELETE CASCADE,
    requested_at  timestamptz NOT NULL,
    index_said    boolean     NOT NULL,
    cal_said      boolean     NOT NULL,
    index_age_s   integer,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX availability_miss_provider_idx ON availability_miss (provider_id, created_at);
