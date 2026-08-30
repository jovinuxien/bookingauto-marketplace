-- "Can I bring the wheels the evening before?"
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/024-messages.sql
--
-- One thread per booking (ADR 0019). No thread without a booking, which is
-- the spam defence: writing to a workshop costs a paid appointment. Plain
-- text, bounded, stored as written.
CREATE TABLE booking_message (
    id          bigserial   PRIMARY KEY,
    booking_id  bigint      NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
    sender      text        NOT NULL CHECK (sender IN ('customer', 'provider')),
    body        text        NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX booking_message_thread_idx ON booking_message (booking_id, id);

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_kind_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_kind_check
    CHECK (kind IN (
        'booking_confirmed',
        'booking_released',
        'booking_refunded',
        'booking_needs_attention',
        'booking_cancelled',
        'booking_cancelled_refunded',
        'booking_cancelled_by_provider',
        'booking_rescheduled',
        'provider_booking_confirmed',
        'provider_booking_cancelled',
        'provider_booking_rescheduled',
        'message_to_provider',
        'message_to_customer',
        'review_requested',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
