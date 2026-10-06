#!/usr/bin/env bash
# kill -9 during a large PutObject. After the restart the object must be absent or intact (never truncated or
# mixed), the engine must verify clean (offline `verify-all`) and accept new writes.
#   ITER="10 45 90" SIZE_MIB=400 ./kill-put.sh      (ITER: percent of the body received when the server is killed)
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
SIZE_MIB="${SIZE_MIB:-300}"; ITER="${ITER:-10 50 90}"
fresh_data; mkdir -p "$WORK/files"
F="$WORK/files/big-put.bin"; mkfile "$F" "$SIZE_MIB"; WANT="$(sha "$F")"
start_server || exit 2
s3c mb faultb >/dev/null

i=0
for pct in $ITER; do
  i=$((i + 1)); key="big-$i"
  base="$(metric 'sectoriadb_s3_request_bytes_total\{[^}]*operation="PutObject"')"
  s3c put faultb "$key" "$F" > "$WORK/put-$i.out" 2>&1 &
  cp=$!
  target=$(( base + SIZE_MIB * 1048576 * pct / 100 ))
  for _ in $(seq 1 600); do
    [ "$(metric 'sectoriadb_s3_request_bytes_total\{[^}]*operation="PutObject"')" -ge "$target" ] && break
    sleep 0.1
  done
  kill9; wait "$cp" 2>/dev/null
  info "iteration $i: killed with ~${pct}% of $SIZE_MIB MiB received; client said: $(tr '\n' ' ' < "$WORK/put-$i.out")"
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
