#!/usr/bin/env bash
# Build Cal's api-v2 image.
#
#   ./docker/build-api-v2.sh [tag]
#
# There is no published image for @calcom/api-v2 -- the calcom Docker Hub
# organisation publishes cal.com, cal.diy and pgbouncer, and nothing else -- so
# it has to be built from the monorepo. This takes tens of minutes and several
# GB.
#
# Why we need it at all: the web image serves a PUBLIC booking-create endpoint
# but no authenticated confirm or cancel. A funnel that can reserve and cannot
# take it back strands a pending booking on every failed payment, blocking a
# real slot with no automated way to release it. So api-v2 is required for the
# compensations, not for the reservation. See docs/design/booking-funnel.md.
set -euo pipefail

CAL_VERSION="${CAL_VERSION:-v6.2.0}"
TAG="${1:-bm/cal-api-v2:${CAL_VERSION}}"
WORK="${WORK:-/tmp/calcom-build}"

# Must match the running web image exactly. api-v2 and the web app share one
# database and one schema; a mismatch is a migration skew between two things
# that cannot disagree.
echo "building ${TAG} from cal.com ${CAL_VERSION}"

if [ ! -d "$WORK/.git" ]; then
  rm -rf "$WORK"
  git clone --depth 1 --branch "$CAL_VERSION" https://github.com/calcom/cal.com.git "$WORK"
fi

cd "$WORK"

# Upstream asks Node for an 8 GB heap. On a developer machine already running a
# few JVMs that invites the OOM killer to pick one of them instead of the build.
# 4 GB is enough for this monorepo; raise it if the build dies on heap.
sed -i 's/--max-old-space-size=8192/--max-old-space-size='"${BUILD_HEAP_MB:-4096}"'/' \
  apps/api/v2/Dockerfile

# Prisma only needs a syntactically valid URL to generate a client; it does not
# connect during the build.
BUILD_DB="postgresql://cal:build@localhost:5432/calendso"

docker build \
  -f apps/api/v2/Dockerfile \
  -t "$TAG" \
  --build-arg DATABASE_URL="$BUILD_DB" \
  --build-arg DATABASE_DIRECT_URL="$BUILD_DB" \
  .

echo "built $TAG"
