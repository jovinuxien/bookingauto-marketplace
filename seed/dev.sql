-- Development fixtures. Not schema — kept out of db/ so it never runs
-- automatically on a fresh database.
--
--   docker exec -i bm-market-db psql -U market -d marketplace < seed/dev.sql
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

INSERT INTO service (provider_id, cal_event_type_id, name, category_slug, duration_minutes, price_minor)
SELECT id, 200 + id, 'Färgning 45 min', 'har', 45, 60000 FROM provider
ON CONFLICT (cal_event_type_id) DO NOTHING;

-- Availability two days out, afternoons only. In production these rows come
-- from the reconciler; here they stand in for it.
INSERT INTO availability_day
  (provider_id, service_id, day, has_capacity, first_free_at, free_slots, free_morning, free_afternoon, source)
SELECT s.provider_id, s.id, current_date + 2, true, now() + interval '2 day', 4, false, true, 'backfill'
FROM service s
ON CONFLICT (service_id, day) DO NOTHING;
