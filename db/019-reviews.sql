-- What the customer thought, once they have been.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/019-reviews.sql
--
-- One review per booking, and only from a booking that happened: confirmed,
-- past its end, never cancelled. That is the whole defence against fake
-- reviews -- a rating costs a paid appointment -- and it is enforced by the
-- primary key and the application together, not by moderation.
--
-- Asked for by mail a couple of hours after the appointment, through the
-- same signed link the confirmation carried: the mailbox is the account
-- (ADR 0014), so the link is the proof it was this customer.
CREATE TABLE review (
    booking_id   bigint      PRIMARY KEY REFERENCES booking (id) ON DELETE CASCADE,
    -- Denormalised from booking for the one query that matters: the
    -- provider's average, on every search hit and every landing card.
    provider_id  bigint      NOT NULL REFERENCES provider (id),
    rating       smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    -- Optional, short. A rating is the data; the sentence is for the next
    -- customer to read.
    comment      text        CHECK (comment IS NULL OR length(comment) <= 1000),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX review_provider_idx ON review (provider_id, created_at DESC);

-- When the customer was asked. Null until the sweep sends the mail; set
-- once, so nobody is asked twice.
ALTER TABLE booking ADD COLUMN review_requested_at timestamptz;

CREATE INDEX booking_review_pending_idx ON booking (ends_at)
 WHERE status = 'confirmed' AND review_requested_at IS NULL;

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_kind_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_kind_check
    CHECK (kind IN (
        'booking_confirmed',
        'booking_released',
        'booking_refunded',
        'booking_needs_attention',
        'booking_cancelled',
        'booking_cancelled_refunded',
        'provider_booking_confirmed',
        'provider_booking_cancelled',
        'review_requested',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
