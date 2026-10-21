#!/usr/bin/env bash
# Compare two runs with `warp cmp before after`. Arguments are result directories (or names relative to bench/results/)
# or .json.zst files. Only compare runs that differ by ONE variable and are error-free (check meta.json).
#   ./compare.sh bench/results/20261006T1_abc_get_small_c20 bench/results/20261007T1_def_get_small_c20
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
[ $# -ge 2 ] || die "usage: compare.sh BEFORE AFTER [warp cmp flags]"

resolve() {
  local p=$1
  [ -e "$p" ] || p="$RESULTS_DIR/$p"
  if [ -d "$p" ]; then p="$(ls "$p"/warp*.zst 2>/dev/null | head -1)"; fi
  [ -f "$p" ] || die "no benchdata for '$1'"
  realpath "$p"
}
A="$(resolve "$1")"; B="$(resolve "$2")"; shift 2

for f in "$A" "$B"; do
  m="$(dirname "$f")/meta.json"
  [ -f "$m" ] && python3 -I - "$m" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
print(f"{m['git']['short']}{'-dirty' if m['git']['dirty'] else ''}  {m['run']['mode']} {m['run']['size_label']} c{m['run']['concurrency']}"
      f"  error_free={m['run']['error_free']}  {m['hardware']['cpu_model']} x{m['hardware']['nproc']}")
PY
done

if [ "$WARP_RUNNER" = local ]; then
  warp cmp "$A" "$B" "$@"
else
  # both files are mounted read-only into the container under /a and /b
  docker run --rm -v "$(dirname "$A"):/a:ro" -v "$(dirname "$B"):/b:ro" "$WARP_IMAGE" cmp --no-color \
    "/a/$(basename "$A")" "/b/$(basename "$B")" "$@"
fi
