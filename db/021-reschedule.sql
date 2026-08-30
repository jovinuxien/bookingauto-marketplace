-- A time can be moved, by the customer, before the cutoff.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/021-reschedule.sql
--
-- Same service, same salon, same money: the booking keeps its price and its
-- charge and gets a new slot. Cal is asked to hold the new time first and to
-- release the old one only once it has; if either fails the booking stays
-- where it was. The cutoff is the cancellation cutoff -- a time that can no
-- longer be given back for free can no longer be moved for free either, and
-- the honest thing then is to say so rather than to invent a second rule.
ALTER TABLE booking ADD COLUMN rescheduled_count integer     NOT NULL DEFAULT 0;
ALTER TABLE booking ADD COLUMN rescheduled_at    timestamptz;
-- Where it was before the last move, for the mails and for anyone reading
-- the row later and wondering why the Cal uid changed.
ALTER TABLE booking ADD COLUMN rescheduled_from  timestamptz;

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_kind_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_kind_check
    CHECK (kind IN (
        'booking_confirmed',
        'booking_released',
        'booking_refunded',
        'booking_needs_attention',
        'booking_cancelled',
        'booking_cancelled_refunded',
        'booking_rescheduled',
        'provider_booking_confirmed',
        'provider_booking_cancelled',
        'provider_booking_rescheduled',
        'review_requested',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
