#!/usr/bin/env bash
# Long mixed run against the stand with periodic sampling of the server's metrics (a leak or drift only shows over hours).
#
#   ./soak.sh --duration 8h --sample 5m --conc 20 --size small [--container sectoriadb-bench] [-- extra run.sh options]
#
# warp (run.sh, mode mixed with as many DELETEs as PUTs, so the data set stays the same size) runs for --duration; at the same time bench/warp/soak_sample.py scrapes the Prometheus endpoint
# of the server every --sample (rates and p99 from the deltas between two scrapes) and appends a row to soak.csv in the
# results directory <results>/<UTC>_<sha>_soak_<mode>_<size>_c<conc>/ next to the warp output. At the end the drift report
# is printed: throughput and p99 of the first window against the last (window = 1 h, or a quarter of a short run) and a warning
# for every series that only grows (RSS, heap, open files, threads, metastore size / free pages, GC queue, disk).
# Defaults: duration 4h (before a production release use >= 8h), sample 5m.
#   --duration D   4h, 30m, 90s ...     --sample D   sampling interval (5m)     --mode M / --size S / --conc N   as run.sh
#   --url U        metrics URL (SOAK_METRICS_URL; default http://localhost:9464/actuator/prometheus)
#   --container C  docker container of the server: adds RSS (VmRSS of PID 1) and the real disk usage (du /data); default
#                  SECTORIADB_CONTAINER (sectoriadb-bench), pass "" to skip
# The stand must be running (bench/observability or any server with the credentials of lib.sh). Exit code: run.sh's, or 4
# when the drift report has a warning. Single run, no repetitions: read the numbers as a trend, not as a benchmark.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DURATION=4h; SAMPLE=5m; MODE=mixed; SIZE=small; CONC=20
URL="${SOAK_METRICS_URL:-http://localhost:9464/actuator/prometheus}"; CONTAINER="$SECTORIADB_CONTAINER"
while [ $# -gt 0 ]; do
  case "$1" in
    --duration) DURATION=$2; shift 2 ;;
    --sample) SAMPLE=$2; shift 2 ;;
    --mode) MODE=$2; shift 2 ;;
    --size) SIZE=$2; shift 2 ;;
    --conc) CONC=$2; shift 2 ;;
    --url) URL=$2; shift 2 ;;
    --container) CONTAINER=$2; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    --) shift; break ;;
    *) die "unknown option $1 (extra run.sh options go after --)" ;;
  esac
done
secs() { case "$1" in *h) echo $(( ${1%h} * 3600 )) ;; *m) echo $(( ${1%m} * 60 )) ;; *s) echo "${1%s}" ;; *[!0-9]*) die "bad duration $1" ;; *) echo "$1" ;; esac; }
TOTAL="$(secs "$DURATION")"; EVERY="$(secs "$SAMPLE")"
curl -sf -m 5 "$URL" >/dev/null || die "no metrics at $URL (is the server running?)"

export RESULTS_DIR="$RESULTS_DIR/$(utc_now)_$(git_short)_soak_${MODE}_${SIZE}_c${CONC}"
mkdir -p "$RESULTS_DIR"
CSV="$RESULTS_DIR/soak.csv"
T0="$(date +%s)"
SAMPLER_ARGS=(--url "$URL" --t0 "$T0"); [ -n "$CONTAINER" ] && SAMPLER_ARGS+=(--container "$CONTAINER")
echo "== soak: $MODE $SIZE c$CONC for $DURATION, sample every $SAMPLE -> $CSV"
python3 -I "$(dirname "${BASH_SOURCE[0]}")/soak_sample.py" sample "$CSV" $(( TOTAL + 7200 )) "$EVERY" "${SAMPLER_ARGS[@]}" &
SAMPLER=$!
trap 'kill "$SAMPLER" 2>/dev/null || true' EXIT INT TERM

# mixed: as many DELETEs as PUTs, so the number of objects stays constant and growth of the metastore or the disk means a leak
# (warp's default 15 % PUT / 10 % DELETE grows the data set all the time); an --extra after "--" overrides this
BALANCE=(); [ "$MODE" = mixed ] && BALANCE=(--extra "--get-distrib 40 --delete-distrib 15")
set +e
"$(dirname "${BASH_SOURCE[0]}")/run.sh" --mode "$MODE" --size "$SIZE" --conc "$CONC" --duration "$DURATION" --no-annotate "${BALANCE[@]}" "$@"
RC=$?
set -e
kill "$SAMPLER" 2>/dev/null || true; wait "$SAMPLER" 2>/dev/null || true; trap - EXIT INT TERM
python3 -I "$(dirname "${BASH_SOURCE[0]}")/soak_sample.py" sample "$CSV" 0 1 "${SAMPLER_ARGS[@]}"   # the closing row

set +e
python3 -I "$(dirname "${BASH_SOURCE[0]}")/soak_sample.py" report "$CSV"
DRIFT=$?
set -e
echo "== soak finished: run.sh rc=$RC, drift warnings=$([ "$DRIFT" -ne 0 ] && echo yes || echo no); results: $RESULTS_DIR"
[ "$RC" -ne 0 ] && exit "$RC"
[ "$DRIFT" -ne 0 ] && exit 4
exit 0
