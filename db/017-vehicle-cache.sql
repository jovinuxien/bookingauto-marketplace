-- A car is asked about once, and remembered.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/017-vehicle-cache.sql
--
-- ADR 0016, phase 1. Until now the registry was asked per booking, by a
-- sweep, after the sale. The regnr-first search puts a lookup in front of
-- a customer who is still deciding, and a customer who is still deciding
-- reloads the page. This table is what stands between that customer and
-- TIC's monthly quota: every lookup, from any path, reads here first, and a
-- miss is asked once and written back -- including the negative answer, so
-- a mistyped plate is not asked about on every keystroke.
--
-- What is stored is what the port's Vehicle record carries and nothing
-- more. No owner column, ever; ADR 0015 refused it and this table is where
-- the refusal would be easiest to forget.
--
-- The vehicle_* columns on booking (db/013, 016) stay: they are what was
-- sold, frozen. The sweep that fills them now copies from here instead of
-- calling out.
CREATE TABLE vehicle (
    -- Normalised: upper case, no spaces or hyphens. RegistrationNumber is
    -- the only writer.
    registration_number text        PRIMARY KEY,

    -- false: the registry was asked and did not know this plate. The row
    -- exists so that the question is not asked again for a while.
    known               boolean     NOT NULL,

    make                text,
    model               text,
    model_year          integer,
    tyre_front          text,
    tyre_rear           text,

    -- Which adapter answered ('tic', ...). For the day two disagree.
    source              text        NOT NULL,
    looked_up_at        timestamptz NOT NULL DEFAULT now(),

    CHECK (known OR make IS NULL)
);
