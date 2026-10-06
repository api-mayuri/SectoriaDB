#!/usr/bin/env bash
# Shared helpers for run.sh / matrix.sh / preload.sh / compare.sh. Source it, do not execute.
# shellcheck disable=SC2034

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$BENCH_DIR/.." && pwd)"
OBS_ENV="${OBS_ENV:-$BENCH_DIR/observability/.env}"
RESULTS_DIR="${RESULTS_DIR:-$BENCH_DIR/results}"

# Load bench/observability/.env without overriding variables that are already set in the environment.
load_env() {
  [ -f "$OBS_ENV" ] || return 0
  local k v
  while IFS='=' read -r k v; do
    [[ "$k" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
    [ -z "${!k+x}" ] && export "$k=$v"
  done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$OBS_ENV")
  return 0
}
load_env

# ---- connection settings (override via environment) -------------------------------------------------------
# Runner: "local" uses a warp binary from PATH, "docker" runs minio/warp on the stand's network.
if [ -z "${WARP_RUNNER:-}" ]; then
  if command -v warp >/dev/null 2>&1; then WARP_RUNNER=local; else WARP_RUNNER=docker; fi
fi
WARP_IMAGE="${WARP_IMAGE:-minio/warp:latest@sha256:72ae1b02216b51bd102b73a6923b597c092a5acc7a655c936c2f31bee897a7c5}"  # warp 1.3.1
WARP_NETWORK="${WARP_NETWORK:-sectoria-bench}"
if [ "$WARP_RUNNER" = docker ]; then
  : "${WARP_HOST:=sectoriadb:8080}"
  : "${INFLUX_HOST:=influxdb:8086}"
else
  : "${WARP_HOST:=localhost:8080}"
  : "${INFLUX_HOST:=localhost:8086}"
fi
: "${WARP_ACCESS_KEY:=${SECTORIADB_ACCESS_KEY:-BENCHACCESSKEY0001}}"
: "${WARP_SECRET_KEY:=${SECTORIADB_SECRET_KEY:-benchsecretkey0123456789abcdef}}"
: "${SECTORIADB_CONTAINER:=sectoriadb-bench}"
: "${GRAFANA_URL:=http://localhost:${GRAFANA_PORT:-3000}}"
: "${GRAFANA_USER:=${GRAFANA_ADMIN_USER:-admin}}"
: "${GRAFANA_PASSWORD:=${GRAFANA_ADMIN_PASSWORD:-}}"
: "${INFLUX_ORG:=sectoria}"
: "${INFLUX_BUCKET:=warp}"

die() { echo "error: $*" >&2; exit 2; }

# warp_exec ARGS...: run warp. For docker, $WARP_MOUNT (host dir) is visible as /out.
warp_exec() {
  if [ "$WARP_RUNNER" = local ]; then
    warp "$@"
  else
    docker run --rm --network "$WARP_NETWORK" -v "${WARP_MOUNT:-$RESULTS_DIR}:/out" "$WARP_IMAGE" "$@"
  fi
}
# Path of a file inside the results directory as warp sees it.
warp_path() { if [ "$WARP_RUNNER" = local ]; then echo "$WARP_MOUNT/$1"; else echo "/out/$1"; fi; }

warp_version() { warp_exec --version 2>/dev/null | head -1; }

# size_args CLASS -> prints warp flags for the size class; sets SIZE_LABEL (used in directory and bucket names)
#   small  : --obj.size 64KiB --obj.randsize  (warp 1.3.1 has no weighted/range syntax: sizes are random from 1 B up
#            to 64 KiB, mean about 12 KiB; both the small-object path (< chunk size) and the chunk path are hit)
#   s4k    : 4 KiB fixed        s64k  : 64 KiB fixed
#   medium : 1 MiB              large : 16 MiB
#   <N>KiB|MiB|GiB : fixed size
size_args() {
  case "$1" in
    small) SIZE_LABEL=small; SIZE_BYTES_HINT=12000; echo "--obj.size 64KiB --obj.randsize" ;;
    s4k)   SIZE_LABEL=s4k;   SIZE_BYTES_HINT=4096;  echo "--obj.size 4KiB" ;;
    s64k)  SIZE_LABEL=s64k;  SIZE_BYTES_HINT=65536; echo "--obj.size 64KiB" ;;
    medium) SIZE_LABEL=medium; SIZE_BYTES_HINT=1048576; echo "--obj.size 1MiB" ;;
    large) SIZE_LABEL=large; SIZE_BYTES_HINT=16777216; echo "--obj.size 16MiB" ;;
    [0-9]*[KMG]iB) SIZE_LABEL="$(echo "$1" | tr 'A-Z' 'a-z')"; SIZE_BYTES_HINT=0; echo "--obj.size $1" ;;
    *) die "unknown size class '$1' (small|s4k|s64k|medium|large|<N>KiB|MiB|GiB)" ;;
  esac
}

utc_now() { date -u +%Y%m%dT%H%M%SZ; }
git_short() { git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo nogit; }
git_dirty() { [ -n "$(git -C "$REPO_DIR" status --porcelain -- . ':!bench/results' 2>/dev/null)" ] && echo true || echo false; }

# ---- Grafana annotations (best effort: never fails the run) --------------------------------------------------
GRAFANA_ANN_ID=""
grafana_annotate_start() { # text tags(json array)
  [ -n "${GRAFANA_PASSWORD}" ] || return 0
  local now body
  now=$(( $(date +%s) * 1000 ))
  body=$(python3 -c 'import json,sys; print(json.dumps({"time":int(sys.argv[1]),"timeEnd":int(sys.argv[1]),"text":sys.argv[2],"tags":json.loads(sys.argv[3])}))' "$now" "$1" "$2")
  GRAFANA_ANN_ID=$(curl -sS -m 5 -u "$GRAFANA_USER:$GRAFANA_PASSWORD" -H 'Content-Type: application/json' \
      -X POST "$GRAFANA_URL/api/annotations" -d "$body" 2>/dev/null \
      | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)
}
grafana_annotate_end() { # text tags(json array)
  [ -n "${GRAFANA_PASSWORD}" ] && [ -n "$GRAFANA_ANN_ID" ] || return 0
  local now body
  now=$(( $(date +%s) * 1000 ))
  body=$(python3 -c 'import json,sys; print(json.dumps({"timeEnd":int(sys.argv[1]),"text":sys.argv[2],"tags":json.loads(sys.argv[3])}))' "$now" "$1" "$2")
  curl -sS -m 5 -o /dev/null -u "$GRAFANA_USER:$GRAFANA_PASSWORD" -H 'Content-Type: application/json' \
      -X PATCH "$GRAFANA_URL/api/annotations/$GRAFANA_ANN_ID" -d "$body" 2>/dev/null || true
}
