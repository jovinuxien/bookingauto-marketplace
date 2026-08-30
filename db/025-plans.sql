-- What a provider pays us, as a named plan.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/025-plans.sql
--
-- Three tiers, and the thing they change is money, never discovery
-- (ADR 0020): search stays distance and time whatever anyone pays. The
-- plan's numbers -- monthly price, commission -- live in configuration,
-- because they are a price list, not a fact about a provider; the column
-- holds only which row of that list applies.
ALTER TABLE provider ADD COLUMN plan text NOT NULL DEFAULT 'bas'
    CHECK (plan IN ('bas', 'plus', 'pro'));
