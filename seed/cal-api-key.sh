#!/usr/bin/env bash
# Mint a Cal API key for the marketplace backend and print it.
#
#   ./seed/cal-api-key.sh [cal-username]
#
# api-v2 authenticates with "Authorization: Bearer cal_<secret>", looking up
# sha256(<secret>) in Cal's ApiKey table. The key is stored hashed, so this is
# the only moment the plaintext exists -- it is printed once and cannot be
# recovered afterwards.
#
# The key is minted against a specific Cal user, and api-v2 acts as that user.
# For confirming and cancelling bookings that must be the user who OWNS the
# event type, because confirming is something a salon does. A key belonging to
# anyone else authenticates fine and then fails on authorisation, which is a
# confusing way to discover the wrong user was chosen.
set -euo pipefail

USERNAME="${1:-salong-sodermalm}"

SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
HASH="$(printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1)"

docker exec -i bm-cal-db psql -q -U cal -d calendso -v ON_ERROR_STOP=1 <<SQL
INSERT INTO "ApiKey" (id, "userId", "hashedKey", note, "createdAt")
SELECT gen_random_uuid()::text, u.id, '${HASH}', 'booking-marketplace backend', now()
  FROM users u WHERE u.username = '${USERNAME}';
SQL

echo
echo "cal_${SECRET}"
echo
echo "Set it as MARKETPLACE_CAL_API_KEY. It is not stored in plaintext anywhere;"
echo "losing it means minting another."
