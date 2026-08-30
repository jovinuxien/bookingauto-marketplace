-- A salon says what it sells when it registers, and the import believes it.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/012-provider-default-category.sql
--
-- ADR 0013 classifies each imported event type from its title and falls back
-- to a configured default -- 'har' -- when nothing matches. That was named as
-- a watch item there and again in ADR 0015: correct for a hairdressing
-- marketplace, and quietly wrong the first time a massage studio or a tyre
-- workshop onboards with event types named "Standard 45 min".
--
-- The fix is to ask. The signup form gains one required choice, it is carried
-- on the pending registration, and it becomes the provider's own default. The
-- configured value stays as the fallback for providers created before this
-- existed and for an operator who does not say.
--
-- Both columns reference service_category, so a category can be retired
-- (active = false) but never deleted from under a provider -- same rule as
-- service.category_slug in db/009.

-- Nullable, and null means "not chosen; use the configured default", which is
-- exactly what every existing provider gets and exactly what they had before.
-- Not backfilled to 'har': every existing provider is hairdressing, so the
-- outcome is identical either way, and a column that says 'har' because a
-- migration wrote it is indistinguishable from one that says it because a
-- person chose it. Same reasoning as db/009 not reclassifying services.
ALTER TABLE provider
    ADD COLUMN default_category_slug text REFERENCES service_category (slug);

-- Nullable for the pending rows that predate the form field, which are at most
-- a day old (the token TTL) and will be verified without a category and land
-- on the fallback. New registrations always carry one; the application checks.
ALTER TABLE provider_signup
    ADD COLUMN category_slug text REFERENCES service_category (slug);
