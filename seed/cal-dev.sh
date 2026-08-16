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

echo "seeding marketplace providers and services"
docker exec -i bm-market-db psql -q -U market -d marketplace -v ON_ERROR_STOP=1 < seed/dev.sql

echo
echo "done. Start the backend and the reconciler will fill availability_day."
