-- The workshop hears about a sale, not only about a cancellation.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/014-provider-booking-confirmed.sql
--
-- Until now a salon learned of a booking from Cal's own calendar and from
-- the console, and the only mail this system sent it was
-- provider_booking_cancelled (db/010). A salon can live with that. A
-- workshop cannot: it needs the registration number before the car arrives,
-- and a calendar entry with a customer's name on it does not carry one.
-- So the confirmation the customer already gets has a counterpart addressed
-- to the provider, carrying the plate when there is one.
--
-- A separate kind rather than a second recipient on booking_confirmed,
-- because the dedupe key is per kind and the two messages say different
-- things -- one is a receipt, the other a work order.
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
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
