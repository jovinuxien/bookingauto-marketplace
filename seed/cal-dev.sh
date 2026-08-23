#!/usr/bin/env bash
# Seed Cal with the three dev salons, then the marketplace side to match.
#
#   ./seed/cal-dev.sh
#
# Idempotent: users are skipped if signup reports them as existing, and every
# statement in the SQL files is guarded.
set -euo pipefail

cd "$(dirname "$0")/.."

CAL_URL="${CAL_URL:-http://localhost:3000}"

# Users go through Cal's own signup endpoint rather than SQL, because password
# hashing is Cal's business and not something to reimplement in a seed script.
for entry in \
  "salong-sodermalm:sodermalm@example.se" \
  "klinik-vasastan:vasastan@example.se" \
  "goteborg-harstudio:goteborg@example.se"
do
  username="${entry%%:*}"
  email="${entry##*:}"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CAL_URL/api/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$username\",\"email\":\"$email\",\"password\":\"Str0ng-Passw0rd!42\"}")
  printf '  %-20s signup -> %s\n' "$username" "$code"
done

echo "seeding cal schedules and event types"
docker exec -i bm-cal-db psql -q -U cal -d calendso -v ON_ERROR_STOP=1 < seed/cal-dev.sql

# Event type ids are read back from Cal rather than assumed.
#
# They were written into dev.sql as 1, 2, 3 -- which holds only on a Cal that
# has never been used for anything else. Any onboarding import takes those ids
# first, and the fixture then points its services at event types belonging to
# somebody else. Cal answers with an empty slot map and no error, which reads
# exactly like a salon with nothing free, so the fixture would look seeded and
# search would look broken.
event_type_id() {
  local username="$1" slug="$2" id
  id=$(docker exec bm-cal-db psql -tAq -U cal -d calendso -c \
    "select e.id from \"EventType\" e join users u on u.id = e.\"userId\"
      where u.username = '$username' and e.slug = '$slug' order by e.id limit 1")

  if [ -z "$id" ]; then
    echo "no event type '$slug' for $username -- cal-dev.sql did not create it" >&2
    exit 1
  fi

  printf '%s' "$id"
}

echo "seeding marketplace providers and services"
docker exec -i bm-market-db psql -q -U market -d marketplace -v ON_ERROR_STOP=1 \
  -v et_sodermalm_fargning="$(event_type_id salong-sodermalm fargning-45)" \
  -v et_vasastan_fargning="$(event_type_id klinik-vasastan fargning-45)" \
  -v et_goteborg_fargning="$(event_type_id goteborg-harstudio fargning-45)" \
  -v et_vasastan_massage="$(event_type_id klinik-vasastan massage-60)" \
  < seed/dev.sql

echo
echo "done. Start the backend and the reconciler will fill availability_day."
