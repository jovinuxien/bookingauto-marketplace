-- The booking funnel's records.
--
-- Two tables and a trail, because the funnel has two different things to
-- remember and conflating them loses the important one.
--
--   booking          what was sold. The commercial record. Exists only once
--                    the sale actually happened.
--   booking_attempt  what happened while trying to sell it, including all the
--                    times it did not work. Exists from the first attempt.
--
-- Most systems keep only the first and treat failures as log lines. That is
-- exactly backwards for a marketplace: a confirmed booking explains itself,
-- while a customer who was charged and has no appointment is a question you
-- cannot answer from a table of successes.
--
-- Applied by hand on an existing database:
--   docker exec -i bm-market-db psql -U market -d marketplace < db/002-booking.sql

-- ---------------------------------------------------------------- booking ---
-- Ours, not Cal's. Cal owns whether the appointment exists; this owns what was
-- sold, for how much, and on what terms.
CREATE TABLE booking (
    id                bigserial   PRIMARY KEY,
    provider_id       bigint      NOT NULL REFERENCES provider (id),
    service_id        bigint      NOT NULL REFERENCES service  (id),

    -- The join to the authority that owns time. Unique: two marketplace
    -- bookings against one Cal booking is a bug we want the database to refuse
    -- rather than a state we want to reconcile later.
    cal_booking_uid   text        NOT NULL UNIQUE,

    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz NOT NULL,

    customer_email    text        NOT NULL,
    customer_name     text        NOT NULL,

    -- The quote, frozen at the moment of sale. Deliberately copied rather than
    -- referenced: reading service.price_minor at refund time means a later
    -- price change silently rewrites a completed sale.
    price_minor       integer     NOT NULL,
    commission_minor  integer     NOT NULL,
    currency          char(3)     NOT NULL DEFAULT 'SEK',

    status            text        NOT NULL DEFAULT 'confirmed'
                      CHECK (status IN ('confirmed', 'cancelled', 'refunded')),

    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX booking_provider_idx ON booking (provider_id, starts_at);
CREATE INDEX booking_customer_idx ON booking (customer_email);

-- -------------------------------------------------------- booking_attempt ---
-- The saga. One row per attempt to buy, successful or not.
CREATE TABLE booking_attempt (
    id                bigserial   PRIMARY KEY,

    -- The client generates this and reuses it on retry. A retried checkout must
    -- not produce a second charge, and the only safe place to enforce that is a
    -- unique constraint rather than application logic.
    idempotency_key   text        NOT NULL UNIQUE,

    provider_id       bigint      NOT NULL REFERENCES provider (id),
    service_id        bigint      NOT NULL REFERENCES service  (id),
    slot_start        timestamptz NOT NULL,

    -- Frozen at stage 5, before anything irreversible happens.
    price_minor       integer     NOT NULL,
    commission_minor  integer     NOT NULL,
    currency          char(3)     NOT NULL DEFAULT 'SEK',

    customer_email    text        NOT NULL,
    customer_name     text        NOT NULL,

    state             text        NOT NULL DEFAULT 'STARTED'
                      CHECK (state IN (
                          -- in flight
                          'STARTED',        -- quote frozen, nothing done yet
                          'RESERVED',       -- Cal holds the slot, pending
                          'VERIFIED',       -- read-back agreed; safe to charge
                          'CHARGED',        -- money has moved
                          -- terminal, sold
                          'CONFIRMED',
                          -- terminal, nothing sold and nothing owed
                          'ABANDONED',      -- gave up before reserving
                          'REFUSED',        -- Cal would not hold the slot
                          'VERIFY_FAILED',  -- read-back disagreed; reservation cancelled
                          'CHARGE_FAILED',  -- payment failed; reservation cancelled
                          -- terminal, money returned
                          'CONFIRM_FAILED', -- confirm failed after charging; refunded
                          -- terminal, a human must act
                          'NEEDS_ATTENTION'
                      )),

    cal_booking_uid   text,
    payment_ref       text,
    booking_id        bigint      REFERENCES booking (id),

    failure           text,

    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- NEEDS_ATTENTION is the state that matters operationally: it means a
-- compensation itself failed, so the customer may be owed money or holding a
-- reservation nobody will honour. Indexed on its own because "is anything
-- stuck?" must stay a cheap question as the table grows.
CREATE INDEX booking_attempt_attention_idx
    ON booking_attempt (updated_at)
    WHERE state = 'NEEDS_ATTENTION';

-- The other query that must stay cheap: attempts that started and never
-- reached a terminal state, which is how a crashed saga looks.
CREATE INDEX booking_attempt_inflight_idx
    ON booking_attempt (updated_at)
    WHERE state IN ('STARTED', 'RESERVED', 'VERIFIED', 'CHARGED');

-- --------------------------------------------------- booking_attempt_step ---
-- The trail. One row per transition, written as it happens.
--
-- Append only, and never updated. The point is to be able to reconstruct what
-- each authority said and when, so that "Cal accepted, Stripe declined, the
-- cancel succeeded" reads off the table rather than being inferred from a final
-- state that has lost the middle.
CREATE TABLE booking_attempt_step (
    id            bigserial   PRIMARY KEY,
    attempt_id    bigint      NOT NULL REFERENCES booking_attempt (id) ON DELETE CASCADE,

    from_state    text        NOT NULL,
    to_state      text        NOT NULL,

    -- Which authority was asked, and what it said. NULL authority means the
    -- transition was our own decision rather than someone else's answer.
    authority     text        CHECK (authority IN ('cal', 'stripe', 'index')),
    outcome       text        NOT NULL CHECK (outcome IN ('ok', 'refused', 'error')),
    detail        text,

    -- Whether this step was a compensating action rather than forward progress.
    compensating  boolean     NOT NULL DEFAULT false,

    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX booking_attempt_step_idx ON booking_attempt_step (attempt_id, created_at);
