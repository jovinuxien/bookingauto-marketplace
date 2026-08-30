-- A workshop wants to know which car is coming.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/013-vehicles.sql
--
-- The one thing the bil & däck vertical (ADR 0015) needs that a salon never
-- did: the registration number, before the car arrives. Three parts, none of
-- them on the booking saga's path.
--
--   1. Which categories ask for it. A column on service_category rather than a
--      list in the frontend, for the reason db/009 exists at all.
--   2. The plate the customer typed, on the attempt and copied to the booking
--      the way the price is -- what was sold, frozen.
--   3. What a registry said about it, filled in afterwards by a sweep through
--      VehicleRegistryPort, with the same bounded-retry shape as db/008's
--      geocoding. Null columns mean "not looked up", and nothing waits for
--      them: a booking with a plate and no make is a booking.

-- ------------------------------------------------------------- who asks --
ALTER TABLE service_category
    ADD COLUMN asks_vehicle boolean NOT NULL DEFAULT false;

UPDATE service_category
   SET asks_vehicle = true
 WHERE slug IN ('dack', 'bilservice', 'bilvard', 'bilglas');

-- ---------------------------------------------------------- what was typed --
-- Normalised by the application before it is written: upper case, no spaces
-- or hyphens. Not constrained to the Swedish format, because a Danish car
-- gets its tyres changed in Malmö too; the application knows which plates
-- look Swedish and the registry adapter decides what it can answer for.
ALTER TABLE booking_attempt ADD COLUMN registration_number text;
ALTER TABLE booking         ADD COLUMN registration_number text;

-- ------------------------------------------------- what the registry said --
ALTER TABLE booking ADD COLUMN vehicle_make        text;
ALTER TABLE booking ADD COLUMN vehicle_model       text;
ALTER TABLE booking ADD COLUMN vehicle_model_year  integer;

-- Bounded retry, as for geocoding. A plate the registry does not know today
-- is almost always a typo, and a typo is not fixed by asking again.
ALTER TABLE booking ADD COLUMN vehicle_lookup_attempts     integer     NOT NULL DEFAULT 0;
ALTER TABLE booking ADD COLUMN vehicle_lookup_attempted_at timestamptz;
ALTER TABLE booking ADD COLUMN vehicle_lookup_failure      text;

-- The sweep's work list: bookings with a plate and no answer yet. Partial, so
-- the index is the size of the backlog rather than of the table.
CREATE INDEX booking_vehicle_pending_idx
    ON booking (vehicle_lookup_attempts, vehicle_lookup_attempted_at)
 WHERE registration_number IS NOT NULL AND vehicle_make IS NULL;
