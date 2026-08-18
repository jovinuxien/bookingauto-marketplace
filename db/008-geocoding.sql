-- Placing a salon on the map.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/008-geocoding.sql
--
-- A salon that registers itself gives a street address and no coordinates, and
-- the radius search that is this product's primary filter can only return rows
-- that have a point. So every self-serve signup arrived invisible to the one
-- query the product is built around, and stayed that way until a person placed
-- it by hand.
--
-- These columns are about *why* a provider has or has not got a location.
-- "location IS NULL" on its own cannot tell apart three quite different
-- situations: never attempted, attempted and refused, and deliberately left for
-- an operator. A sweep that cannot tell them apart either retries forever or
-- gives up silently.

-- How the point got there. Worth knowing before trusting it: a geocoded point
-- is an inference from a string, an operator's point is a person looking at a
-- map, and a seeded one is a fixture.
ALTER TABLE provider ADD COLUMN location_source text
    CHECK (location_source IN ('geocoded', 'operator', 'seed'));

-- When it was last placed -- not when it was last tried. A coordinate does not
-- expire, but streets are renumbered and salons move, so the age of a placement
-- is a question someone will eventually want to ask.
ALTER TABLE provider ADD COLUMN located_at timestamptz;

-- Bounded retry. A geocoder that cannot find an address today will usually not
-- find it tomorrow either: the common cause is that the address is wrong, or
-- carries an apartment number, or is not in the map data at all. None of those
-- are fixed by asking again. This exists to stop the sweep asking forever and
-- to hand the address to a person instead.
ALTER TABLE provider ADD COLUMN geocode_attempts int NOT NULL DEFAULT 0;
ALTER TABLE provider ADD COLUMN geocode_attempted_at timestamptz;

-- In words, for whoever has to place it by hand. "No result" and "only matched
-- the city" need different answers from an operator, and both look identical
-- as a null location.
ALTER TABLE provider ADD COLUMN geocode_failure text;

-- Rows that already had a point predate all of this and came from the seed.
UPDATE provider SET location_source = 'seed', located_at = created_at
 WHERE location IS NOT NULL;

-- The sweep's working set. Partial, because the rows needing placement are
-- always a small minority of the table and the index should stay that size.
CREATE INDEX provider_needs_location_idx
    ON provider (geocode_attempts, geocode_attempted_at)
 WHERE location IS NULL AND address_line IS NOT NULL;
