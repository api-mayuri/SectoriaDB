#!/usr/bin/env bash
# One-time large dataset, then runs against it with --list-existing (no preparation phase, no wipe).
#
#   ./preload.sh load  --bucket warp-bigdata --size medium --objects 100000 --conc 50     # fill
#   ./preload.sh run   --bucket warp-bigdata --mode get --conc 50 --duration 30m         # measure (run.sh --list-existing)
#   ./preload.sh drop  --bucket warp-bigdata                                              # remove the data
#
# Loading uses `warp put` with --noclear so the bucket is NOT wiped afterwards. Run `load` repeatedly (with a different
# --prefix) to grow the dataset; objects of one load are listed back by `get --list-existing`.
#
# 1 TB procedure (not executed in the sandbox, see bench/README.md): 1 TB = 1 000 000 objects of 1 MiB, or 64 000
# objects of 16 MiB. warp put speed is roughly 100-500 MiB/s on one disk, so the load takes 1-3 hours:
#   for i in $(seq 1 10); do ./preload.sh load --bucket warp-1tb --size medium --objects 100000 --prefix part$i; done
# Watch fill % / disk free % / metastore size on the dashboard while loading, and run the measurement at 70, 80, 90 % fill.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/lib.sh"

CMD="${1:-}"; [ -n "$CMD" ] || die "usage: preload.sh load|run|drop [options]"; shift
BUCKET=""; SIZE="medium"; OBJECTS=10000; CONC=50; PREFIX=""
PASS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --bucket) BUCKET=$2; shift 2 ;;
    --size) SIZE=$2; shift 2 ;;
    --objects) OBJECTS=$2; shift 2 ;;
    --conc) CONC=$2; shift 2 ;;
    --prefix) PREFIX=$2; shift 2 ;;
    *) PASS+=("$1"); shift ;;
  esac
done
[ -n "$BUCKET" ] || die "--bucket is required"

case "$CMD" in
  load)
    # warp put runs for --duration; we want a number of objects, so use a long duration and stop by count via
    # `--objects` of `get` preparation (prepares exactly N objects, then runs for 1s).
    WARP_MOUNT="$RESULTS_DIR"; mkdir -p "$WARP_MOUNT"
    # shellcheck disable=SC2046
    warp_exec get --no-color --host "$WARP_HOST" --access-key "$WARP_ACCESS_KEY" --secret-key "$WARP_SECRET_KEY" \
      --bucket "$BUCKET" --concurrent "$CONC" $(size_args "$SIZE") --objects "$OBJECTS" \
      ${PREFIX:+--prefix "$PREFIX"} --noclear --duration 1s --benchdata "$(warp_path preload-$(utc_now))" "${PASS[@]}"
    ;;
  run)
    exec "$HERE/run.sh" --bucket "$BUCKET" --size "$SIZE" --conc "$CONC" --list-existing "${PASS[@]}"
    ;;
  drop)
    echo "delete the bucket's objects with: mc rm --recursive --force / aws s3 rm s3://$BUCKET --recursive (warp itself would wipe it on a normal run)"
    ;;
  *) die "unknown command $CMD" ;;
esac
