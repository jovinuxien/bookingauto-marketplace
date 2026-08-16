#!/usr/bin/env bash
# Run the whole stack.
#
#   ./run.sh
#
# Exists because the application briefly required a handful of environment
# variables that were documented nowhere and lived only in one developer's
# shell. Everything needed is now either defaulted in application.yml or read
# from .env, and this script is the single place that says so.
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "no .env — copy .env.sample and set the passwords" >&2
  exit 1
fi

set -a && . ./.env && set +a

echo "starting containers"
docker compose up -d

echo "waiting for the databases"
until docker compose exec -T market-db pg_isready -U market -d marketplace >/dev/null 2>&1; do
  sleep 2
done

echo "building the frontend"
( cd backend && npm install --no-audit --no-fund --silent && npx vite build )

echo
echo "  consumer site   http://localhost:8090"
echo "  salon console   http://localhost:8090/logga-in"
echo "  mail (mailhog)  http://localhost:8026"
echo "  cal             http://localhost:3000"
echo

cd backend && exec mvn -B spring-boot:run
