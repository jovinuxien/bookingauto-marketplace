-- The bike shop, and a lesson about generic words.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < db/015-cykel.sql
--
-- ADR 0015 left cykel out because its natural words collide: a bicycle
-- shop's "Punktering" or "Service" would have landed in dack or bilservice,
-- which is worse than landing nowhere. What changed is db/012 -- a provider
-- now says at signup what it sells, and an event type whose title matches
-- nothing falls to *that* rather than to 'har'.
--
-- So the rule becomes: synonyms are for words that identify a category on
-- their own, and generic words are nobody's. "Service 15 000 km" at a car
-- workshop and "Service" at a bike shop both match nothing and both land
-- where the provider said they should. bilservice gives up 'service',
-- 'reparation' and 'verkstad' for the same reason; it keeps everything
-- that only a car has.
--
-- Where a bicycle word contains a car word -- "däckbyte cykel" contains
-- "däckbyte" -- the longer synonym wins in Categories.classify, which is
-- why the cykel list spells those out in full.
--
-- No plate: a bicycle has no registration number, and asks_vehicle stays
-- false. Sort order 140, after the car block.

INSERT INTO service_category (slug, path, label, synonyms, sort_order) VALUES
    ('cykel', 'cykelservice', 'Cykelservice', ARRAY[
        'cykel', 'cykelservice', 'cykelreparation', 'cykelverkstad',
        'elcykel', 'elcykelservice', 'cykeldäck', 'däckbyte cykel',
        'slangbyte', 'punktering cykel', 'växeljustering', 'bromsjustering',
        'kedjebyte', 'ekerriktning', 'hjulriktning', 'vårservice cykel',
        'lådcykel', 'mountainbike', 'racercykel'
    ], 140);

-- The generic words go. A workshop typing "Service" gets bilservice because
-- it said so at signup, not because the word said so.
UPDATE service_category
   SET synonyms = array_remove(array_remove(array_remove(synonyms,
                    'service'), 'reparation'), 'verkstad')
 WHERE slug = 'bilservice';
