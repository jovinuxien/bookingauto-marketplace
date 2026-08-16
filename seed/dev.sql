-- Development fixtures. Not schema — kept out of db/ so it never runs
-- automatically on a fresh database.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < seed/dev.sql
--
-- Run seed/cal-dev.sh first: this file references Cal event type ids, and
-- pointing a service at an event type Cal does not have produces an empty
-- slot map, which is indistinguishable from a fully booked salon.
--
-- Three salons: two in Stockholm a couple of kilometres apart, one in Göteborg
-- that any "near me in Stockholm" search must exclude. That third row is the
-- point of the fixture — a geo filter that returns everything looks identical
-- to one that works until something is deliberately out of range.

INSERT INTO provider (cal_team_id, slug, name, status, city, location) VALUES
  (101, 'salong-sodermalm',   'Salong Södermalm',   'active', 'Stockholm', ST_MakePoint(18.0686, 59.3131)::geography),
  (102, 'klinik-vasastan',    'Klinik Vasastan',    'active', 'Stockholm', ST_MakePoint(18.0500, 59.3400)::geography),
  (103, 'goteborg-harstudio', 'Göteborg Hårstudio', 'active', 'Göteborg',  ST_MakePoint(11.9746, 57.7089)::geography)
ON CONFLICT (cal_team_id) DO NOTHING;

-- Matched to Cal by slug rather than by arithmetic on the id. The two id
-- sequences are independent and only happen to line up on a fresh pair of
-- databases; a seed that relies on that coincidence breaks silently the first
-- time either side is seeded twice.
INSERT INTO service (provider_id, cal_event_type_id, name, category_slug, duration_minutes, price_minor)
SELECT p.id, c.cal_event_type_id, 'Färgning 45 min', 'har', 45, 60000
FROM provider p
JOIN (VALUES
        ('salong-sodermalm',   1),
        ('klinik-vasastan',    2),
        ('goteborg-harstudio', 3)
     ) AS c(slug, cal_event_type_id) ON c.slug = p.slug
ON CONFLICT (cal_event_type_id) DO NOTHING;

-- availability_day is deliberately NOT seeded.
--
-- It used to be, back when the reconciler was a README. Now that the mechanism
-- exists, hand-written rows are worse than no rows: they make search return
-- results whether or not sync works, which is precisely the failure the index
-- was built to make visible. Start the backend and let the reconciler fill it —
-- services with no rows count as maximally stale and are picked up on the first
-- pass.
