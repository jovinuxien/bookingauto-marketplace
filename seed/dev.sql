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

-- Payable, because active means payable.
--
-- These rows used to be inserted 'active' with no Stripe account, and
-- db/004 has forbidden that since it added provider_sellable_check: an active
-- provider takes a customer's money with nowhere to send it. So this file has
-- not run at all on any database carrying that migration -- ON CONFLICT does
-- not save it, because a CHECK is evaluated before conflict arbitration.
--
-- The fix is for the fixture to satisfy the invariant rather than to route
-- around it. A dev salon that is sellable is a dev salon that can be paid, and
-- pretending otherwise would seed a state the application is built to prevent.
-- The account ids are obviously fake and go nowhere; the payments gateway is
-- 'dev' by default and moves no money.
INSERT INTO provider (cal_team_id, slug, name, status, city, location,
                      stripe_account_id, payouts_enabled) VALUES
  (101, 'salong-sodermalm',   'Salong Södermalm',   'active', 'Stockholm', ST_MakePoint(18.0686, 59.3131)::geography, 'acct_dev_sodermalm', true),
  (102, 'klinik-vasastan',    'Klinik Vasastan',    'active', 'Stockholm', ST_MakePoint(18.0500, 59.3400)::geography, 'acct_dev_vasastan',  true),
  (103, 'goteborg-harstudio', 'Göteborg Hårstudio', 'active', 'Göteborg',  ST_MakePoint(11.9746, 57.7089)::geography, 'acct_dev_goteborg',  true)
ON CONFLICT (cal_team_id) DO NOTHING;

-- Matched to Cal by slug rather than by arithmetic on the id. The two id
-- sequences are independent and only happen to line up on a fresh pair of
-- databases; a seed that relies on that coincidence breaks silently the first
-- time either side is seeded twice.
-- Event type ids come from Cal, looked up by cal-dev.sh and passed in.
--
-- They used to be written here as 1, 2, 3, which is the coincidence the comment
-- above warns about and which duly broke: a Cal that had been used for anything
-- else -- an onboarding import, say -- has those ids taken, and the fixture
-- then points three services at somebody else's event types. Nothing complains.
-- The slot map comes back empty, and empty is indistinguishable from a salon
-- with no free time.
INSERT INTO service (provider_id, cal_event_type_id, name, category_slug, duration_minutes, price_minor)
SELECT p.id, c.cal_event_type_id, 'Färgning 45 min', 'har', 45, 60000
FROM provider p
JOIN (VALUES
        ('salong-sodermalm',   :et_sodermalm_fargning),
        ('klinik-vasastan',    :et_vasastan_fargning),
        ('goteborg-harstudio', :et_goteborg_fargning)
     ) AS c(slug, cal_event_type_id) ON c.slug = p.slug
ON CONFLICT (cal_event_type_id) DO NOTHING;

-- One salon that sells something other than hair.
--
-- The point of it is the category. Until ADR 0013 every service in this system
-- was 'har', so /massage/{city} and /hudvard/{city} were pages that could not
-- exist and nothing noticed, because a fixture that only produces hairdressing
-- cannot tell the difference. This row is what makes the second landing page
-- reachable, the category filter something with more than one answer, and the
-- import classifier checkable against real data.
INSERT INTO service (provider_id, cal_event_type_id, name, category_slug, duration_minutes, price_minor)
SELECT p.id, :et_vasastan_massage, 'Massage 60 min', 'massage', 60, 85000
FROM provider p
WHERE p.slug = 'klinik-vasastan'
ON CONFLICT (cal_event_type_id) DO NOTHING;

-- availability_day is deliberately NOT seeded.
--
-- It used to be, back when the reconciler was a README. Now that the mechanism
-- exists, hand-written rows are worse than no rows: they make search return
-- results whether or not sync works, which is precisely the failure the index
-- was built to make visible. Start the backend and let the reconciler fill it —
-- services with no rows count as maximally stale and are picked up on the first
-- pass.
