-- Messages we owe people.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/006-notifications.sql
--
-- An outbox, not a mail queue in a broker. The reason is the same one that
-- shapes the booking funnel: there is no transaction spanning our database and
-- an SMTP server, so the only way to guarantee "if the booking exists, the
-- confirmation is owed" is to write the message in the same transaction as the
-- thing that caused it, and deliver it afterwards.
--
-- The alternative -- sending inline while handling the request -- loses the
-- message on any crash between commit and send, and the customer discovers it
-- by not receiving a confirmation for a booking that definitely happened.

CREATE TABLE notification_outbox (
    id              bigserial   PRIMARY KEY,

    -- One message per thing-that-happened. A retried webhook, a redelivered
    -- Stripe event or a second call into the same code path must not produce a
    -- second email, and a unique key is the only place that can be enforced
    -- without trusting every caller.
    dedupe_key      text        NOT NULL UNIQUE,

    kind            text        NOT NULL
                    CHECK (kind IN (
                        'booking_confirmed',
                        'booking_released',   -- the slot was let go; no money moved
                        'booking_refunded',
                        'booking_needs_attention'
                    )),

    recipient       text        NOT NULL,
    subject         text        NOT NULL,

    -- Rendered at enqueue time, not at send time.
    --
    -- A receipt should say what was true when it was earned. Rendering at send
    -- time would let a price change or a renamed service rewrite the contents
    -- of a message about a sale that already happened -- the same reason the
    -- quote is frozen onto booking_attempt.
    body_text       text        NOT NULL,
    body_html       text,

    booking_id      bigint      REFERENCES booking (id) ON DELETE SET NULL,
    provider_id     bigint      REFERENCES provider (id) ON DELETE SET NULL,

    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    -- Set when we stop trying. Distinct from sent_at being null, which just
    -- means "not yet": a message nobody will ever retry needs to be visible as
    -- a failure rather than sitting in the queue looking pending forever.
    failed_at       timestamptz,
    last_error      text,

    created_at      timestamptz NOT NULL DEFAULT now()
);

-- The dispatcher's query. Partial so it stays small: the table grows forever,
-- the set of undelivered messages should not.
CREATE INDEX notification_pending_idx
    ON notification_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND failed_at IS NULL;

-- "What did we fail to tell people?" must stay a cheap question.
CREATE INDEX notification_failed_idx
    ON notification_outbox (failed_at)
    WHERE failed_at IS NOT NULL;

CREATE INDEX notification_booking_idx ON notification_outbox (booking_id);
