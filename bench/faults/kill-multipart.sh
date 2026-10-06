#!/usr/bin/env bash
# kill -9 during a multipart upload: (a) between UploadPart calls, (b) while CompleteMultipartUpload runs.
# Expected: after restart the upload can be resumed (parts listed) or aborted; the finished object is intact or absent;
# `verify-all` is clean.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
PART_MIB="${PART_MIB:-8}"; PARTS="${PARTS:-24}"
fresh_data; mkdir -p "$WORK/files"
F="$WORK/files/big-mpu.bin"; mkfile "$F" $(( PART_MIB * PARTS )); WANT="$(sha "$F")"; SIZE=$(( PART_MIB * PARTS * 1048576 ))
start_server || exit 2
s3c mb faultb >/dev/null

# (a) kill between parts
s3c mpu-parts faultb mp-a "$F" "$PART_MIB" 5 > "$WORK/mpu-a.out" 2>&1
UID_A="$(sed -n 's/^UPLOADID //p' "$WORK/mpu-a.out")"
kill9
start_server || exit 2
n="$(s3c list-uploads faultb | sed -n 's/^UPLOADS //p')"
[ "$n" = 1 ] && pass "(a) the in-flight upload is still listed after the crash" || fail "(a) ListMultipartUploads after crash: $n uploads"
out="$(s3c mpu-resume faultb mp-a "$F" "$PART_MIB" "$UID_A")"; info "(a) resume: $(echo "$out" | tr '\n' ' ')"
echo "$out" | grep -q "COMPLETE ok" && [ "$(s3c get faultb mp-a)" = "GET ok $SIZE $WANT" ] \
  && pass "(a) resumed upload completes and the object is intact" || fail "(a) resume/complete/GET"

# (b) kill while CompleteMultipartUpload is running (assembling parts is slow for big objects)
(s3c mpu-complete-only faultb mp-b "$F" "$PART_MIB" > "$WORK/mpu-b.out" 2>&1) &
cp=$!
for _ in $(seq 1 600); do grep -q COMPLETING "$WORK/mpu-b.out" 2>/dev/null && break; sleep 0.1; done
sleep "${COMPLETE_KILL_DELAY:-0.3}"
kill9; wait "$cp" 2>/dev/null
info "(b) client said: $(tr '\n' ' ' < "$WORK/mpu-b.out")"
start_server || exit 2
r="$(s3c get faultb mp-b)"
case "$r" in
  "GET absent") pass "(b) object absent after crash during Complete"
     # the upload id should still be completable (or at least abortable)
     s3c list-uploads faultb | grep -q "mp-b" && info "(b) upload mp-b is still listed (resumable)" ;;
  "GET ok $SIZE $WANT") pass "(b) object complete and intact after crash during Complete" ;;
  *) fail "(b) unexpected state after crash during Complete: $r" ;;
esac
check "no integrity failures counted" test "$(metric 'sectoriadb_integrity_crc_failures_total')" = 0

stop_server
out="$(offline_verify)"; echo "$out" | sed 's/^/      /' | tail -6
echo "$out" | grep -q "RESULT: OK" && pass "verify-all clean" || fail "verify-all reports problems"
remove_server; rm -f "$F"
finish
