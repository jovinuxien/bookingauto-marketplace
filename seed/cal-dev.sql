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

-- The bookable thing. 45 minutes to match service.duration_minutes on the
-- marketplace side; a mismatch there would make the index describe slots
-- of a length Cal will not sell.
insert into "EventType" (title, slug, length, "userId", "scheduleId", price, currency)
select 'Färgning 45 min', 'fargning-45', 45, u.id, u."defaultScheduleId", 0, 'sek'
  from users u
 where u.username in ('salong-sodermalm', 'klinik-vasastan', 'goteborg-harstudio')
   and not exists (
         select 1 from "EventType" e
          where e."userId" = u.id and e.slug = 'fargning-45');

-- EventType."userId" is only the OWNER. Cal resolves who can actually be
-- booked through the _user_eventtype join table, and an event type absent
-- from it returns an empty slot map with no error -- which reads exactly
-- like "fully booked" and is the reason this line exists.
insert into "_user_eventtype" ("A", "B")
select e.id, e."userId"
  from "EventType" e
 where e.slug = 'fargning-45'
   and not exists (
         select 1 from "_user_eventtype" j
          where j."A" = e.id and j."B" = e."userId");

commit;
