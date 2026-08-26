-- A customer can reach their booking, and undo it.
--
-- Until now there was exactly one consumer write endpoint and no way back:
-- booking.status has allowed 'cancelled' and 'refunded' since db/002 and
-- nothing has ever written either. This is what writes them.
--
-- No consumer table, and that is the decision rather than an omission. The
-- confirmation email already goes to an address the customer proved they can
-- read by receiving it, so the link in that email is the credential -- the same
-- reasoning ADR 0011 applies to a salon registering itself, with the mailbox
-- again standing in for an account. What this buys is not convenience: it is
-- that a marketplace which never asks a consumer to make an account has no
-- consumer credentials to leak and no consumer profile to erase on request.
-- See ADR 0014.
--
-- Applied by hand on an existing database:
--   docker exec -i bm-market-db psql -U market -d marketplace < db/010-consumer-cancellation.sql

-- ------------------------------------------------- the terms of the sale ---
-- Frozen onto the booking, for the same reason price_minor is copied rather
-- than referenced: a policy this system changes next year must not silently
-- rewrite what a customer agreed to this morning. Someone who booked when
-- cancellation was free until 24 hours before keeps that, whatever the
-- configured default becomes.
ALTER TABLE booking
    ADD COLUMN cancellation_cutoff_hours integer NOT NULL DEFAULT 24;

-- ---------------------------------------------------- what actually happened ---
ALTER TABLE booking
    ADD COLUMN cancelled_at timestamptz,

    -- Stripe's refund, when one was due and went through. Distinct from
    -- status = 'refunded': the status is the commercial fact, this is the
    -- receipt for it, and a person reconciling an account needs the second.
    ADD COLUMN refund_ref   text,

    -- Set when the slot was released and the money was not.
    --
    -- The one outcome of a cancellation that a machine cannot finish. Cal is
    -- asked first, deliberately -- the customer asked to lose the appointment,
    -- so delivering that and owing them money is recoverable, while taking the
    -- money back and leaving a live appointment nobody expects to be kept is a
    -- salon holding an empty chair with no way to find out.
    --
    -- A booking in this state is cancelled and the customer is out of pocket.
    -- Only the platform can fix it: the charge is on our Stripe account, not
    -- the salon's.
    ADD COLUMN needs_attention boolean NOT NULL DEFAULT false;

-- "Is anyone owed money?" has to stay a cheap question as the table grows, and
-- it is asked far more often than it is true.
CREATE INDEX booking_attention_idx
    ON booking (updated_at)
    WHERE needs_attention;

-- Cancellations are read by date for a salon's console; the existing
-- booking_provider_idx covers (provider_id, starts_at) and still serves it.

-- ------------------------------------------------------ notification kinds ---
-- Three new messages, and the third is the first this system has ever sent to
-- a salon rather than to a customer.
--
-- booking_cancelled           cancelled outside the free window; nothing refunded
-- booking_cancelled_refunded  cancelled in time; money on its way back
-- provider_booking_cancelled  the salon's Saturday afternoon just came free
--
-- The two customer messages are separate kinds rather than one with a
-- conditional paragraph, because the dedupe key is per kind and a customer must
-- never be able to receive both about one booking.
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_kind_check;

ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_kind_check
    CHECK (kind IN (
        'booking_confirmed',
        'booking_released',
        'booking_refunded',
        'booking_needs_attention',
        'booking_cancelled',
        'booking_cancelled_refunded',
        'provider_booking_cancelled',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
