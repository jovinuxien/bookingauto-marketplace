-- Dev supply for Cal: schedules and bookable event types for the three
-- seeded salons.
--
-- This writes Cal's own schema directly, which is a SEED SHORTCUT and not
-- how onboarding works. Real provider onboarding must go through Cal's API
-- so that Cal's own invariants and side effects apply. It is acceptable
-- here because a dev environment needs bookable supply before the
-- onboarding flow exists, and because everything written below is supply,
-- never availability -- availability stays computed by Cal from these rows.
--
-- Users are created first via POST /api/auth/signup (see seed/cal-dev.sh),
-- because password hashing is Cal's business and not something to
-- reimplement in SQL.
--
-- Re-runnable: matches on username and event type slug.

begin;

-- Onboarding leaves users in Europe/London with no schedule. A salon in
-- Stockholm whose availability is computed in London time is off by an
-- hour for half the year, which is exactly the class of bug that shows up
-- as "the index says 09:00 is free and Cal refuses it".
update users
   set "timeZone" = 'Europe/Stockholm',
       "completedOnboarding" = true
 where username in ('salong-sodermalm', 'klinik-vasastan', 'goteborg-harstudio');

-- One schedule per salon: Monday-Saturday, 09:00-18:00 local.
insert into "Schedule" ("userId", name, "timeZone")
select u.id, 'Öppettider', 'Europe/Stockholm'
  from users u
 where u.username in ('salong-sodermalm', 'klinik-vasastan', 'goteborg-harstudio')
   and not exists (select 1 from "Schedule" s where s."userId" = u.id);

insert into "Availability" ("scheduleId", "userId", days, "startTime", "endTime")
select s.id, s."userId", '{1,2,3,4,5,6}'::integer[], time '09:00', time '18:00'
  from "Schedule" s
 where not exists (select 1 from "Availability" a where a."scheduleId" = s.id);

update users u
   set "defaultScheduleId" = s.id
  from "Schedule" s
 where s."userId" = u.id
   and u."defaultScheduleId" is null;

-- The bookable things. Lengths match service.duration_minutes on the
-- marketplace side; a mismatch there would make the index describe slots of a
-- length Cal will not sell.
--
-- The massage is not decoration. Until ADR 0013 every imported service was
-- categorised 'har', so /massage/{city} and /hudvard/{city} could not exist --
-- and a fixture that only ever produces hairdressing is a fixture that cannot
-- notice. One salon selling something else is what makes the second landing
-- page, the category filter and the import classifier exercisable at all.
insert into "EventType" (title, slug, length, "userId", "scheduleId", price, currency,
                         "requiresConfirmation", "requiresConfirmationWillBlockSlot")
select w.title, w.slug, w.length, u.id, u."defaultScheduleId", 0, 'sek', false, false
  from (values
          ('salong-sodermalm',   'fargning-45', 'Färgning 45 min', 45),
          ('klinik-vasastan',    'fargning-45', 'Färgning 45 min', 45),
          ('goteborg-harstudio', 'fargning-45', 'Färgning 45 min', 45),
          ('klinik-vasastan',    'massage-60',  'Massage 60 min',  60)
       ) as w(username, slug, title, length)
  join users u on u.username = w.username
 where not exists (
         select 1 from "EventType" e
          where e."userId" = u.id and e.slug = w.slug);

-- Auto-accepting on purpose. Both confirmation flags are OFF, and that is a
-- decision worth understanding before flipping it.
--
-- Reserve-first needs the reservation to actually hold the slot. There are two
-- ways to get that, both verified against this Cal version:
--
--   requiresConfirmation + requiresConfirmationWillBlockSlot
--       booking is created PENDING and holds the slot (12 slots -> 11).
--       Completing the sale then needs an authenticated api-v2 confirm, and
--       authenticated api-v2 needs a PAID Cal licence -- the api key strategy
--       checks CALCOM_LICENSE_KEY before it even looks at the key.
--       NB requiresConfirmation ALONE holds nothing: the booking is PENDING
--       and the slot stays on sale, which is the worst of both.
--
--   neither flag (what we do)
--       booking is created ACCEPTED and holds the slot immediately
--       (12 slots -> 10 for a 45 minute service). Nothing to confirm, so no
--       licence is needed. Cancelling is unauthenticated on api-v2, so the
--       compensation still works.
--
-- The cost of auto-accepting is that Cal emails the customer a confirmation
-- before payment has succeeded, and a cancellation if it then fails. That is a
-- Cal workflow setting to tune, not an architectural problem -- whereas the
-- licence is neither.
update "EventType"
   set "requiresConfirmation" = false,
       "requiresConfirmationWillBlockSlot" = false
 where slug in ('fargning-45', 'massage-60');

-- EventType."userId" is only the OWNER. Cal resolves who can actually be
-- booked through the _user_eventtype join table, and an event type absent
-- from it returns an empty slot map with no error -- which reads exactly
-- like "fully booked" and is the reason this line exists.
insert into "_user_eventtype" ("A", "B")
select e.id, e."userId"
  from "EventType" e
 where e.slug in ('fargning-45', 'massage-60')
   and not exists (
         select 1 from "_user_eventtype" j
          where j."A" = e.id and j."B" = e."userId");

commit;
