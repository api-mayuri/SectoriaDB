#!/usr/bin/env bash
# Full disk: the data directory (or the metadata directory, TARGET=meta) lives on a small ext4 loop device (needs root:
# mount -o loop). PUTs are sent until the disk is full. Expected: clean 5xx error (no hang, no crash), earlier objects
# still readable, no corruption (offline verify-all), service keeps answering, and PUTs work again after space is freed
# (a filler file on the same device is removed; SectoriaDB itself does not delete data to make room).
#   SIZE_MB=64 TARGET=storage ./full-disk.sh        data directory full
#   SIZE_MB=40 TARGET=meta OBJ_KIB=1 MAX_PUTS=20000 FILLER_MB=20 ./full-disk.sh   metadata directory full
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
SIZE_MB="${SIZE_MB:-64}"; TARGET="${TARGET:-storage}"; OBJ_KIB="${OBJ_KIB:-2048}"; OBJ_MIB=$(( OBJ_KIB / 1024 > 0 ? OBJ_KIB / 1024 : 1 ))
[ "$(id -u)" = 0 ] || { echo "needs root for the loop mount"; exit 2; }
fresh_data
IMG="$WORK/small-disk.img"; MNT="$WORK/data/$TARGET"
cleanup() { remove_server; umount "$MNT" 2>/dev/null; rm -f "$IMG"; }
trap cleanup EXIT
dd if=/dev/zero of="$IMG" bs=1M count="$SIZE_MB" status=none && mkfs.ext4 -q -m 0 "$IMG" && mount -o loop "$IMG" "$MNT" || { echo "cannot create the loop device"; exit 2; }
# filler: leaves ~20 MiB for the server; removed later to "free space"
dd if=/dev/zero of="$MNT/filler.bin" bs=1M count="${FILLER_MB:-$(( SIZE_MB - 24 ))}" status=none
info "device $SIZE_MB MiB for $TARGET, free: $(df -m "$MNT" | awk 'NR==2 {print $4}') MiB"

start_server || exit 2
s3c mb diskb >/dev/null
MAXP="${MAX_PUTS:-60}"
r="$(s3c fill diskb "$MAXP" $(( OBJ_KIB * 1024 )) "$WORK/fill.sha")"
case "$r" in
  "FILL ok "*) ok="${r#FILL ok }"; first_err=""; info "all $ok PUTs of $OBJ_KIB KiB succeeded" ;;
  "FILL error "*) set -- $r; failed_at=$3; ok=$(( failed_at - 1 )); first_err="PUT error ${r#FILL error $3 }"
                  info "$ok PUTs of $OBJ_KIB KiB succeeded; first failure at #$failed_at: $first_err" ;;
  *) ok=0; first_err="$r" ;;
esac
[ -n "$first_err" ] && pass "writes failed once the disk was full (instead of hanging or crashing)" || fail "no failure after $MAXP PUTs: disk did not fill"
echo "$first_err" | grep -qE "PUT error 5[0-9][0-9] " && pass "failure is a clean 5xx S3 error: ${first_err#PUT error }" || fail "failure is not a clean 5xx S3 error: $first_err"
check "server process still running and healthy" curl -sf "http://localhost:$MGMT_PORT/actuator/health"
bad=0; for i in 1 $(( ok > 2 ? ok / 2 : 1 )) "$ok"; do
  [ "$ok" -ge 1 ] || break
  want="$(awk -v k="o$i" '$1 == k {print $2}' "$WORK/fill.sha")"
  [ "$(s3c get diskb "o$i")" != "GET ok $(( OBJ_KIB * 1024 )) $want" ] && bad=$((bad + 1))
done
[ "$bad" = 0 ] && pass "objects written before the disk filled are intact" || fail "$bad earlier objects unreadable/corrupt"
mkfile "$WORK/obj.bin" "$OBJ_MIB"; WANT="$(sha "$WORK/obj.bin")"; OBJ_BYTES=$(( OBJ_MIB * 1048576 ))
r2="$(s3c put diskb after-full "$WORK/obj.bin")"; info "another PUT while still full: $r2"
info "errors by code: $(curl -s "http://localhost:$MGMT_PORT/actuator/prometheus" | grep '^sectoriadb_s3_errors_total' | sed 's/application="SectoriaDB",//' | tr '\n' ' ')"
info "disk free metric: $(curl -s "http://localhost:$MGMT_PORT/actuator/prometheus" | grep '^sectoriadb_disk_free_bytes' | tr '\n' ' ')"
server_logs | grep -iE "No space|ENOSPC|IOException" | sed 's/^/      log: /' | sort | uniq -c | sort -rn | head -3

rm -f "$MNT/filler.bin"; sleep 1
r3="$(s3c put diskb after-free "$WORK/obj.bin")"
[ "$r3" = "PUT ok" ] && pass "PUT succeeds after space was freed (no restart)" || {
  info "PUT after freeing: $r3; restarting the server"; start_server
  [ "$(s3c put diskb after-free2 "$WORK/obj.bin")" = "PUT ok" ] && pass "PUT succeeds after space was freed and a restart" || fail "PUT still fails after freeing space and restart"; }
[ "$(s3c get diskb after-free)" = "GET ok $OBJ_BYTES $WANT" ] || [ "$(s3c get diskb after-free2)" = "GET ok $OBJ_BYTES $WANT" ] \
  && pass "object written after recovery reads back intact" || fail "object written after recovery is not intact"

stop_server
out="$(offline_verify)"; echo "$out" | tail -8 | sed 's/^/      /'
echo "$out" | grep -q "RESULT: OK" && pass "verify-all clean after the full-disk episode" || fail "verify-all reports problems after the full-disk episode"
finish
