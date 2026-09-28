#!/usr/bin/env bash
# Flat contest GeoJSON (no depth profile).
# Does not overwrite data/m11-sample.geojson.
#
# Local: Maven + JDK 11, java -jar (ContestCliRunner), no Docker:
#   ./scripts/export-flat.sh
# Docker: compose up, API upload/job, files in data/:
#   ./scripts/export-flat.sh --docker
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

compose() {
  local runner=()
  if docker info >/dev/null 2>&1; then
    runner=()
  elif sudo docker info >/dev/null 2>&1; then
    runner=(sudo)
  else
    echo "Docker not found." >&2
    exit 1
  fi
  if "${runner[@]}" docker-compose version >/dev/null 2>&1; then
    "${runner[@]}" docker-compose "$@"
  elif "${runner[@]}" docker compose version >/dev/null 2>&1; then
    "${runner[@]}" docker compose "$@"
  else
    echo "docker-compose 1.29.2 or docker compose is required." >&2
    exit 1
  fi
}

export_local() {
  local jar="$ROOT/backend/target/heatnet-tracing-service-0.1.0-SNAPSHOT.jar"
  if [[ ! -f "$jar" ]]; then
    echo "JAR missing, building: mvn -f backend/pom.xml package -DskipTests"
    mvn -f backend/pom.xml package -DskipTests
  fi
  export SPRING_PROFILES_ACTIVE=local
  export HEATNET_CONFIG_DIR="$ROOT/config"
  export HEATNET_DATA_DIR="$ROOT/data"
  java -Xmx6g -jar "$jar" \
    --heatnet.cli.input=dataset/dataset_updated.geojson \
    --heatnet.cli.output=data/result.geojson \
    --heatnet.cli.depth=false \
    --heatnet.cli.split-variants=true
}

export_docker() {
  echo "Starting stack: docker-compose up -d"
  compose up -d
  local i
  for i in $(seq 1 96); do
    if curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
      break
    fi
    if [[ "$i" -eq 96 ]]; then
      echo "Backend did not become healthy. Logs:" >&2
      compose logs backend --tail 80 || true
      exit 1
    fi
    sleep 5
  done

  local upload file_id job_json job_id status
  echo "Uploading dataset/dataset_updated.geojson"
  upload="$(curl -fsS -F "file=@dataset/dataset_updated.geojson" http://localhost:8080/api/files)"
  file_id="$(printf '%s' "$upload" | sed -n 's/.*"fileId":"\([^"]*\)".*/\1/p')"
  if [[ -z "$file_id" ]]; then
    echo "Response has no fileId: $upload" >&2
    exit 1
  fi
  job_json="$(curl -fsS -H "Content-Type: application/json" \
    -d "{\"fileId\":\"$file_id\",\"enableDepth\":false}" \
    http://localhost:8080/api/jobs)"
  job_id="$(printf '%s' "$job_json" | sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p')"
  if [[ -z "$job_id" ]]; then
    echo "Response has no jobId: $job_json" >&2
    exit 1
  fi
  echo "Job $job_id (no depth)"
  for i in $(seq 1 300); do
    status="$(curl -fsS "http://localhost:8080/api/jobs/$job_id")"
    printf '%s\n' "$status" | sed -n 's/.*"status":"\([^"]*\)".*"stage":"\([^"]*\)".*"progress":\([0-9]*\).*/\1 \2 \3%/p'
    if printf '%s' "$status" | grep -q '"status":"FAILED"'; then
      echo "Job failed: $status" >&2
      exit 1
    fi
    if printf '%s' "$status" | grep -q '"status":"DONE"'; then
      break
    fi
    if [[ "$i" -eq 300 ]]; then
      echo "Job did not finish within 15 minutes" >&2
      exit 1
    fi
    sleep 3
  done

  mkdir -p data
  curl -fsS -o data/result.geojson "http://localhost:8080/api/jobs/$job_id/result"
  local variant
  for variant in vA vB vC; do
    curl -fsS -o "data/result_${variant}.geojson" \
      "http://localhost:8080/api/jobs/$job_id/result?variant=${variant}"
    echo "Saved data/result_${variant}.geojson"
  done
  echo "Done: data/result.geojson and result_vA.geojson, _vB, _vC"
}

case "${1:-}" in
  --docker)
    echo "Mode: Docker, flat (no depth)"
    export_docker
    ;;
  ""|--local)
    echo "Mode: local JAR. For Docker use: ./scripts/export-flat.sh --docker"
    export_local
    ;;
  *)
    echo "Usage: $0 [--local | --docker]" >&2
    exit 1
    ;;
esac
