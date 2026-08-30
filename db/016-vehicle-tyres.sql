-- The tyre dimension, which is why a registry was worth asking at all.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/016-vehicle-tyres.sql
--
-- db/013 gave a booking make, model and year. A däckverkstad wants one more
-- thing: what size is on the car, front and rear, so the right tyres are on
-- the rack before it arrives. Transportstyrelsen has it and TIC exposes it
-- (dackdimensionFram / dackdimensionBak); the first registry adapter writes
-- it here. As free text -- "205/55 R16" -- because that is what the rack
-- is labelled with, and parsing it into width/profile/rim would be
-- inventing structure nobody has asked for.
ALTER TABLE booking ADD COLUMN vehicle_tyre_front text;
ALTER TABLE booking ADD COLUMN vehicle_tyre_rear  text;
