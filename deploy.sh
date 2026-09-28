#!/usr/bin/env bash
# Start stack and wait for health. Does not run GeoJSON export.
#   ./deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

if command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
elif docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
else
  echo "Docker not found. Install Docker Desktop or docker-compose 1.29.2." >&2
  exit 1
fi

"${COMPOSE[@]}" up -d --build

echo "Waiting for health..."
for i in $(seq 1 36); do
  if curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    curl -fsS http://localhost:8080/api/info
    echo
    echo "OK: docker stack is up"
    exit 0
  fi
  sleep 5
done

echo "Backend health timeout. Logs:" >&2
"${COMPOSE[@]}" logs backend --tail 80
exit 1
