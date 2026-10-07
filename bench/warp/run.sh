#!/usr/bin/env bash
# Single warp run against SectoriaDB. Results go to bench/results/<UTC>_<sha>_<mode>_<size>_c<conc>/ (local files only).
#
#   ./run.sh --mode mixed --size small --conc 50 --duration 5m --influx
#
# Modes: get put mixed delete list stat multipart multipart-put
# Size classes: small (1 B..64 KiB random, mean ~12 KiB) | s4k | s64k | medium (1 MiB) | large (16 MiB) | <N>KiB|MiB
# Options (also as environment variables, e.g. DURATION=10m):
#   --mode M          warp mode                                  (MODE,     default mixed)
#   --size S          size class                                 (SIZE,     default small)
#   --conc N          warp --concurrent                          (CONC,     default 20)
#   --duration D      e.g. 30s, 5m, 2h                           (DURATION, default 30s: smoke; use hours for drift)
#   --objects N       prepared objects (get/mixed/stat/list/delete)  (OBJECTS, default depends on size)
#   --parts N / --part-size S    multipart modes                 (PARTS=20, PART_SIZE=5MiB)
#   --influx          stream live results to InfluxDB (INFLUX_TOKEN etc. from bench/observability/.env)
#   --autoterm        stop when stable (compare MEDIANS, not the whole run)
#   --bucket B        use this bucket (default warp-bench-<mode>-<size>-c<conc>-<HHMMSS>; warp WIPES it)
#   --keep-bucket     do not delete the bucket after the run (default: a bucket created by this script is deleted)
#   --list-existing   do not prepare data, use objects already in --bucket (see preload.sh); never clears the bucket
#   --no-annotate     do not post Grafana annotations
#   --label TEXT      free-text note stored in meta.json
#   --extra "ARGS"    additional warp flags (e.g. "--disable-multipart --md5")
# Exit code: 0 ok, 3 run finished but reported errors (results are NOT error-free: do not compare), 2 usage/other.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MODE="${MODE:-mixed}"; SIZE="${SIZE:-small}"; CONC="${CONC:-20}"; DURATION="${DURATION:-30s}"
OBJECTS="${OBJECTS:-}"; PARTS="${PARTS:-20}"; PART_SIZE="${PART_SIZE:-5MiB}"
INFLUX="${INFLUX:-0}"; AUTOTERM="${AUTOTERM:-0}"; BUCKET="${BUCKET:-}"; LIST_EXISTING="${LIST_EXISTING:-0}"
ANNOTATE="${ANNOTATE:-1}"; LABEL="${LABEL:-}"; EXTRA="${EXTRA:-}"; KEEP_BUCKET="${KEEP_BUCKET:-0}"; OWN_BUCKET=1

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE=$2; shift 2 ;;
    --size) SIZE=$2; shift 2 ;;
    --conc) CONC=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --objects) OBJECTS=$2; shift 2 ;;
    --parts) PARTS=$2; shift 2 ;;
    --part-size) PART_SIZE=$2; shift 2 ;;
    --influx) INFLUX=1; shift ;;
    --autoterm) AUTOTERM=1; shift ;;
    --bucket) BUCKET=$2; OWN_BUCKET=0; shift 2 ;;
    --keep-bucket) KEEP_BUCKET=1; shift ;;
    --list-existing) LIST_EXISTING=1; shift ;;
    --no-annotate) ANNOTATE=0; shift ;;
    --label) LABEL=$2; shift 2 ;;
    --extra) EXTRA=$2; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

case "$MODE" in get|put|mixed|delete|list|stat|multipart|multipart-put) ;; *) die "unknown mode '$MODE'" ;; esac
SZ_FLAGS=""; SZ_FLAGS="$(size_args "$SIZE")"   # sets SIZE_LABEL (subshell: recompute below)
case "$SIZE" in small) SIZE_LABEL=small ;; s4k) SIZE_LABEL=s4k ;; s64k) SIZE_LABEL=s64k ;; medium) SIZE_LABEL=medium ;;
                large) SIZE_LABEL=large ;; *) SIZE_LABEL="$(echo "$SIZE" | tr 'A-Z' 'a-z')" ;; esac
case "$MODE" in multipart|multipart-put) SIZE_LABEL="part$(echo "$PART_SIZE" | tr 'A-Z' 'a-z')" ;; esac

# default object counts: enough to not run dry in a short run, small enough to prepare quickly
if [ -z "$OBJECTS" ]; then
  case "$SIZE" in
    small|s4k) OBJECTS=10000 ;; s64k) OBJECTS=5000 ;; medium) OBJECTS=1000 ;; large) OBJECTS=200 ;; *) OBJECTS=1000 ;;
  esac
  [ "$MODE" = delete ] && OBJECTS=$(( OBJECTS * 3 ))
fi

STAMP="$(utc_now)"; SHA="$(git_short)"; DIRTY="$(git_dirty)"
[ "$DIRTY" = true ] && SHA="${SHA}-dirty"
RUN_DIR="$RESULTS_DIR/${STAMP}_${SHA}_${MODE}_${SIZE_LABEL}_c${CONC}"
mkdir -p "$RUN_DIR"
export WARP_MOUNT="$RUN_DIR"

if [ -n "$BUCKET" ]; then OWN_BUCKET=0; else BUCKET="warp-bench-${MODE}-${SIZE_LABEL}-c${CONC}-${STAMP:9:6}"; fi
BUCKET="$(echo "$BUCKET" | tr 'A-Z_' 'a-z-')"

ARGS=("$MODE" --no-color --host "$WARP_HOST" --access-key "$WARP_ACCESS_KEY" --secret-key "$WARP_SECRET_KEY"
      --bucket "$BUCKET" --concurrent "$CONC" --duration "$DURATION" --benchdata "$(warp_path warp)")
# shellcheck disable=SC2206
case "$MODE" in
  multipart)     ARGS+=(--part.size "$PART_SIZE" --parts "$PARTS") ;;
  multipart-put) ARGS+=(--part.size "$PART_SIZE" --parts "$PARTS" --part.concurrent "$(( CONC < PARTS ? CONC : PARTS ))") ;;
  put)           ARGS+=($SZ_FLAGS) ;;
  *)             ARGS+=($SZ_FLAGS --objects "$OBJECTS") ;;
esac
[ "$LIST_EXISTING" = 1 ] && ARGS+=(--list-existing)
[ "$AUTOTERM" = 1 ] && ARGS+=(--autoterm)
if [ "$INFLUX" = 1 ]; then
  [ -n "${INFLUX_TOKEN:-}" ] || die "--influx needs INFLUX_TOKEN (bench/observability/.env)"
  ARGS+=(--influxdb "http://${INFLUX_TOKEN}@${INFLUX_HOST}/${INFLUX_BUCKET}/${INFLUX_ORG}")
fi
# shellcheck disable=SC2206
[ -n "$EXTRA" ] && ARGS+=($EXTRA)

WARP_VER="$(warp_version || true)"
LOGGED_ARGS=()   # never store the secret key / influx token
for a in "${ARGS[@]}"; do
  [ "$a" = "$WARP_SECRET_KEY" ] && a='***'
  [ -n "${INFLUX_TOKEN:-}" ] && a="${a//$INFLUX_TOKEN/***}"
  LOGGED_ARGS+=("$a")
done
echo "== run: $MODE size=$SIZE conc=$CONC duration=$DURATION bucket=$BUCKET"
echo "== warp: $WARP_VER ($WARP_RUNNER)  results: $RUN_DIR"

TAGS="[\"warp\",\"mode:$MODE\",\"size:$SIZE_LABEL\",\"conc:$CONC\",\"commit:$SHA\"]"
START_UTC="$(date -u +%FT%TZ)"
[ "$ANNOTATE" = 1 ] && grafana_annotate_start "warp $MODE $SIZE_LABEL c$CONC @ $SHA (running)" "$TAGS"

set +e
warp_exec "${ARGS[@]}" 2>&1 | tee "$RUN_DIR/warp-run.log"
WARP_RC=${PIPESTATUS[0]}
set -e
END_UTC="$(date -u +%FT%TZ)"

BENCHFILE="$(ls "$RUN_DIR"/warp*.zst 2>/dev/null | head -1 || true)"
if [ -n "$BENCHFILE" ]; then
  warp_exec analyze --no-color --analyze.v "$(warp_path "$(basename "$BENCHFILE")")" > "$RUN_DIR/analyze.txt" 2>&1 || true
fi

# error-free check: warp prints "Errors:" lines / "* Errors" in the report when requests failed
ERR_LINES="$(grep -iE '^\s*\*? *errors?:|[0-9]+ errors|^warp: <ERROR>' "$RUN_DIR/warp-run.log" "$RUN_DIR/analyze.txt" 2>/dev/null | head -5 || true)"
ERR_FREE=true
{ [ "$WARP_RC" -ne 0 ] || [ -n "$ERR_LINES" ]; } && ERR_FREE=false

python3 -I "$(dirname "${BASH_SOURCE[0]}")/meta.py" "$RUN_DIR/meta.json" \
  started_utc="$START_UTC" finished_utc="$END_UTC" git_dirty="$DIRTY" container="$SECTORIADB_CONTAINER" \
  warp_version="$WARP_VER" warp_runner="$WARP_RUNNER" warp_args="$(IFS=$'\x1f'; echo "${LOGGED_ARGS[*]}")" \
  mode="$MODE" size_class="$SIZE" size_label="$SIZE_LABEL" concurrency="$CONC" duration="$DURATION" \
  objects="$OBJECTS" bucket="$BUCKET" autoterm="$AUTOTERM" list_existing="$LIST_EXISTING" influx="$INFLUX" \
  warp_exit_code="$WARP_RC" error_free="$ERR_FREE" label="$LABEL"

if [ "$OWN_BUCKET" = 1 ] && [ "$KEEP_BUCKET" = 0 ] && [ "$LIST_EXISTING" = 0 ]; then
  BUCKET_DELETE_STATUS="$(delete_bucket "$BUCKET")"
  echo "== bucket $BUCKET deleted: HTTP $BUCKET_DELETE_STATUS"
fi

[ "$ANNOTATE" = 1 ] && grafana_annotate_end "warp $MODE $SIZE_LABEL c$CONC @ $SHA: $([ "$ERR_FREE" = true ] && echo ok || echo ERRORS)" "$TAGS"

echo "== finished rc=$WARP_RC error_free=$ERR_FREE -> $RUN_DIR"
[ "$ERR_FREE" = true ] || { echo "$ERR_LINES" >&2; exit 3; }
