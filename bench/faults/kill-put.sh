#!/usr/bin/env bash
# kill -9 during a large PutObject. After the restart the object must be absent or intact (never truncated or
# mixed), the engine must verify clean (offline `verify-all`) and accept new writes.
#   ITER="10 50 90" SIZE_MIB=800 ./kill-put.sh
# The server first spools the whole body to a temp file (checksums are verified before anything touches a blob) and then
# writes it into the blob; the kill is aimed at the blob-writing phase (ITER: percent of the object already written).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
SIZE_MIB="${SIZE_MIB:-800}"; ITER="${ITER:-10 50 90}"
fresh_data; mkdir -p "$WORK/files"
F="$WORK/files/big-put.bin"
start_server || exit 2
s3c mb faultb >/dev/null
i=0
for pct in $ITER; do
  i=$((i + 1)); key="big-$i"
  # new random content each time: identical chunks would be deduplicated against earlier iterations and never written
  mkfile "$F" "$SIZE_MIB"; WANT="$(sha "$F")"
  # progress = bytes the engine wrote to disk (the request-bytes counter only moves when the request is over)
  base="$(metric 'sectoriadb_storage_disk_write_bytes_total')"
  s3c put faultb "$key" "$F" > "$WORK/put-$i.out" 2>&1 &
  cp=$!
  target=$(( base + SIZE_MIB * 1048576 * pct / 100 ))
  for _ in $(seq 1 1500); do
    [ "$(metric 'sectoriadb_storage_disk_write_bytes_total')" -ge "$target" ] && break
    sleep 0.05
  done
  kill9; wait "$cp" 2>/dev/null
  info "iteration $i: killed with ~${pct}% of $SIZE_MIB MiB written to the blob; client said: $(tr '\n' ' ' < "$WORK/put-$i.out")"
  start_server || { fail "iteration $i: server restart"; break; }
  r="$(s3c get faultb "$key")"
  case "$r" in
    "GET absent") pass "iteration $i ($pct%): object absent after crash" ;;
    "GET ok $(( SIZE_MIB * 1048576 )) $WANT") pass "iteration $i ($pct%): object complete and intact after crash" ;;
    *) fail "iteration $i ($pct%): unexpected state after crash: $r" ;;
  esac
  # a retried PUT after the restart must work and be readable
  s3c put faultb "$key-retry" "$F" | grep -q "PUT ok" && [ "$(s3c get faultb "$key-retry")" = "GET ok $(( SIZE_MIB * 1048576 )) $WANT" ] \
    && pass "iteration $i: new PUT after restart works" || fail "iteration $i: new PUT after restart"
  check "iteration $i: no integrity failures counted" test "$(metric 'sectoriadb_integrity_crc_failures_total')" = 0
done

stop_server
out="$(offline_verify)"; echo "$out" | sed 's/^/      /' | tail -6
echo "$out" | grep -q "RESULT: OK" && pass "verify-all clean after $i kill -9 cycles" || fail "verify-all reports problems"
remove_server; rm -f "$F"
finish
