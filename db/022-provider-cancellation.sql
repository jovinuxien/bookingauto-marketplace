-- The salon can let go of a time too -- and then the customer is made whole.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/022-provider-cancellation.sql
--
-- A sick mechanic, a broken lift: a workshop that cannot honour a time
-- cancels it from the console. The one rule that makes this safe to offer
-- is that the refund is unconditional -- the cutoff protects the provider
-- from late customers, and it has no business protecting a provider from
-- its own cancellation. Who cancelled is written down, because "cancelled"
-- meant two very different things the day this column did not exist.
ALTER TABLE booking ADD COLUMN cancelled_by text
    CHECK (cancelled_by IN ('customer', 'provider'));

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
        'review_requested',
        'signup_verification',
        'signup_exists',
        'signup_welcome'
    ));
