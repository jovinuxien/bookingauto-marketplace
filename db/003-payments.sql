-- Payments, and the state the funnel gains because real money is asynchronous.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/003-payments.sql
--
-- The dev gateway settles a charge inside the request that asked for it. No
-- real payment method does. Swish -- the dominant one here -- is a *push*
-- payment: Stripe returns "requires action", the customer opens their bank app,
-- approves, and Stripe tells us later over a webhook. Cards with 3-D Secure
-- behave the same way.
--
-- So stage 7 cannot be a function call that returns success or failure. The
-- attempt has to be able to wait, and something has to notice when it waits
-- forever. That is what this migration is for.

-- --------------------------------------------------------- connected accounts
-- Where the salon's share is sent.
--
-- Nullable because a provider exists before it can be paid: onboarding creates
-- the record, Stripe KYC completes later, and a provider without a usable
-- account must be unsellable rather than sellable-and-unpayable.
ALTER TABLE provider
    ADD COLUMN stripe_account_id  text UNIQUE,
    ADD COLUMN payouts_enabled    boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN provider.payouts_enabled IS
    'Mirrors Stripe charges_enabled/payouts_enabled. Set from account.updated '
    'webhooks, never assumed: an account can be restricted at any time and the '
    'first sign is a failed transfer.';

-- ------------------------------------------------------------- the new state
-- AWAITING_PAYMENT: the slot is reserved, a PaymentIntent exists, and the
-- customer is somewhere in their banking app. Money has not moved and may never.
ALTER TABLE booking_attempt DROP CONSTRAINT booking_attempt_state_check;

ALTER TABLE booking_attempt ADD CONSTRAINT booking_attempt_state_check
    CHECK (state IN (
        'STARTED',
        'RESERVED',
        'VERIFIED',
        'AWAITING_PAYMENT',
        'CHARGED',
        'CONFIRMED',
        'ABANDONED',
        'REFUSED',
        'VERIFY_FAILED',
        'CHARGE_FAILED',
        'CONFIRM_FAILED',
        'NEEDS_ATTENTION'
    ));

-- Stripe's identifier for the intent. Distinct from payment_ref, which holds
-- the settled charge: an intent that never succeeds still needs to be findable
-- when someone asks what happened.
ALTER TABLE booking_attempt
    ADD COLUMN payment_intent_id text;

CREATE UNIQUE INDEX booking_attempt_intent_idx
    ON booking_attempt (payment_intent_id)
    WHERE payment_intent_id IS NOT NULL;

-- An abandoned checkout is the common case, not the exception -- people open
-- the app, get distracted, and never come back -- and every one of them is
-- holding a real slot. This index is what the sweeper reads.
DROP INDEX booking_attempt_inflight_idx;

CREATE INDEX booking_attempt_inflight_idx
    ON booking_attempt (updated_at)
    WHERE state IN ('STARTED', 'RESERVED', 'VERIFIED', 'AWAITING_PAYMENT', 'CHARGED');

-- ------------------------------------------------------- stripe webhook receipts
-- Same discipline as Cal's webhooks: recorded before they are acted on, and
-- kept whether or not they were understood.
--
-- Stripe redelivers on any non-2xx, and delivers out of order often enough that
-- it must be assumed. event_id is unique so a redelivery is a no-op rather than
-- a second refund.
CREATE TABLE stripe_receipt (
    id            bigserial   PRIMARY KEY,
    event_id      text        NOT NULL UNIQUE,
    event_type    text        NOT NULL,
    payload       jsonb       NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    error         text
);

CREATE INDEX stripe_receipt_unprocessed_idx
    ON stripe_receipt (received_at)
    WHERE processed_at IS NULL;

-- ---------------------------------------------------- what the reservation was
-- The saga can now be suspended at AWAITING_PAYMENT and resumed by a webhook in
-- a different request, so whatever the resume needs must be written down rather
-- than held in a local variable.
--
-- Recorded from Cal's answer rather than derived from slot_start plus duration.
-- The verify step already proves those agree today; storing what the authority
-- actually said means the record stays true if they ever stop agreeing.
ALTER TABLE booking_attempt
    ADD COLUMN reserved_end    timestamptz,
    ADD COLUMN reserved_status text;
