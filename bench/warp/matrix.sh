#!/usr/bin/env bash
# Matrix of runs: modes x size classes x concurrency steps, one run.sh call each, sequentially (never in parallel:
# runs would disturb each other). Failed runs do not stop the matrix; the summary lists them.
#
#   MODES="get put mixed" SIZES="small medium" CONCS="20 50 100" DURATION=60s ./matrix.sh --influx
#
# Environment:
#   MODES     default: get put mixed delete list stat multipart multipart-put
#   SIZES     default: small medium large   (list/stat/delete use only the first size; multipart modes ignore sizes)
#   CONCS     default: 20 50 100
#   DURATION  default: 30s (smoke). For a real measurement use >= 5m, for drift hunting hours (see bench/README.md)
#   COOLDOWN  seconds between runs, default 15 (lets fsync queues, GC and page cache settle)
#   DRY_RUN=1 only print the planned runs
# All other arguments are passed to run.sh (--influx, --autoterm, --objects, ...).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

MODES="${MODES:-get put mixed delete list stat multipart multipart-put}"
SIZES="${SIZES:-small medium large}"
CONCS="${CONCS:-20 50 100}"
DURATION="${DURATION:-30s}"
COOLDOWN="${COOLDOWN:-15}"
DRY_RUN="${DRY_RUN:-0}"

mkdir -p "$RESULTS_DIR"
SUMMARY="$RESULTS_DIR/matrix_$(utc_now).tsv"
printf 'mode\tsize\tconc\trc\tresult\taverage\n' | tee "$SUMMARY"

first_size="${SIZES%% *}"
fails=0
for mode in $MODES; do
  case "$mode" in
    list|stat|delete) sizes="$first_size" ;;
    multipart|multipart-put) sizes="-" ;;
    *) sizes="$SIZES" ;;
  esac
  for size in $sizes; do
    for conc in $CONCS; do
      if [ "$DRY_RUN" = 1 ]; then
        echo "would run: run.sh --mode $mode --size ${size/-/small} --conc $conc --duration $DURATION $*"; continue
      fi
      out="$(mktemp)"
      "$HERE/run.sh" --mode "$mode" --size "${size/-/small}" --conc "$conc" --duration "$DURATION" "$@" >"$out" 2>&1
      rc=$?
      dir="$(grep -o 'results: .*' "$out" | head -1 | cut -d' ' -f2-)"
      avg="$(grep -m1 -E '^ \* Average:' "$out" | sed 's/^ \* //')"
      [ "$rc" -ne 0 ] && fails=$((fails + 1))
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$mode" "$size" "$conc" "$rc" "$(basename "${dir:-?}")" "$avg" | tee -a "$SUMMARY"
      rm -f "$out"
      sleep "$COOLDOWN"
    done
  done
done
echo "summary: $SUMMARY  (failed or with errors: $fails)"
[ "$fails" -eq 0 ]
