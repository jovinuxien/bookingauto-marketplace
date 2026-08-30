-- Where a booking came from.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/023-booking-channel.sql
--
-- The widget (ADR 0018) hands off to the marketplace checkout, and this is
-- how the hand-off stays visible: a channel on the attempt, copied to the
-- booking. Reporting only -- it decides no price and no access, which is
-- why a client-supplied value is acceptable here and nowhere else.
ALTER TABLE booking_attempt ADD COLUMN channel text NOT NULL DEFAULT 'marketplace'
    CHECK (channel IN ('marketplace', 'widget'));
ALTER TABLE booking ADD COLUMN channel text NOT NULL DEFAULT 'marketplace'
    CHECK (channel IN ('marketplace', 'widget'));
