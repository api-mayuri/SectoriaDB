#!/usr/bin/env bash
# Crash-consistent hot backup: a copy of the single data volume taken while the server is writing must be a valid
# store. The copy is taken with the server process FROZEN (docker pause = cgroup freezer: no instruction runs, nothing is
# flushed or closed), which stands in for the instant of an atomic volume snapshot (LVM/ZFS/Btrfs/cloud disk). Each copy is
# started like a restored backup: offline `verify-all` must be clean, and every operation acknowledged before the pause
# must be visible with the right checksum (an unanswered request may or may not have been applied).
#   ROUNDS=3 WRITE_SECONDS=45 THREADS=4 ./snapshot-backup.sh
#   NEGATIVE=1 ./snapshot-backup.sh   control: the server keeps running and the directories are copied one after another
#                                     (what the documentation forbids); the checks are expected to FAIL
# Limit: the copy sees everything the frozen process had write()-n, i.e. at least what a block-level snapshot sees; a real
# snapshot of the disk may lack the not-yet-flushed tail, which the store tolerates by design (kill-put.sh, power-loss tests).
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
ROUNDS="${ROUNDS:-3}"; WRITE_SECONDS="${WRITE_SECONDS:-45}"; THREADS="${THREADS:-4}"
TAG="snapbk$$"; LIVE="$TAG-live"; NAME="$TAG-live"; SNAP_NAME="$TAG-snap"
SNAP_S3_PORT="${SNAP_S3_PORT:-8091}"; SNAP_MGMT_PORT="${SNAP_MGMT_PORT:-9491}"
LOG="$WORK/snapshot-writes.log"; : > "$LOG"
cleanup() { docker rm -f "$NAME" "$SNAP_NAME" >/dev/null 2>&1; docker volume rm -f "$LIVE" $(seq -f "$TAG-snap%g" 1 "$ROUNDS") >/dev/null 2>&1; [ "${WPID:-0}" -gt 0 ] && kill "$WPID" 2>/dev/null; }
trap cleanup EXIT

run_server() { # name volume s3port mgmtport
  docker run -d --name "$1" -p "127.0.0.1:$3:8080" -p "127.0.0.1:$4:9464" \
    -e "SECTORIADB_S3_CREDENTIALS=$FAULT_ACCESS_KEY:$FAULT_SECRET_KEY:main" -e "JAVA_OPTS=$JAVA_OPTS_FAULT" \
    -v "$2:/data" "$IMAGE" >/dev/null || return 1
  for _ in $(seq 1 90); do
    curl -sf -m 2 "http://localhost:$4/actuator/health" >/dev/null 2>&1 && return 0; sleep 1
  done
  echo "server $1 did not become ready" >&2; docker logs --tail 20 "$1" >&2; return 1
}

docker volume create "$LIVE" >/dev/null
run_server "$NAME" "$LIVE" "$S3_PORT" "$MGMT_PORT" || exit 2
s3c mb snapb >/dev/null
"$PY" "$FAULTS_DIR/snapshot_check.py" write snapb "$LOG" "$WRITE_SECONDS" "$THREADS" &
WPID=$!

sleep 8
for r in $(seq 1 "$ROUNDS"); do
  snapvol="$TAG-snap$r"
  docker volume create "$snapvol" >/dev/null
  [ -n "${NEGATIVE:-}" ] || docker pause "$NAME" >/dev/null
  # the copy of the volume and of the write log are taken while the server is frozen; the log can only hold operations
  # that were answered before the pause (the answer is written after the server committed)
  if [ -n "${NEGATIVE:-}" ]; then
    docker run --rm -v "$LIVE:/src:ro" -v "$snapvol:/dst" debian:12-slim sh -c 'cp -a /src/storage /dst/ && sleep 4 && cp -a /src/meta /dst/'
  else
    docker run --rm -v "$LIVE:/src:ro" -v "$snapvol:/dst" debian:12-slim cp -a /src/. /dst/
  fi
  cp_rc=$?
  cp "$LOG" "$WORK/snapshot-writes-$r.log"
  [ -n "${NEGATIVE:-}" ] || docker unpause "$NAME" >/dev/null
  [ "$cp_rc" -eq 0 ] || { fail "round $r: copying the volume"; continue; }
  acked="$(grep -c '^ACK' "$WORK/snapshot-writes-$r.log")"
  info "round $r: snapshot taken with $acked acknowledged operations, volume copy $(docker run --rm -v "$snapvol:/d:ro" debian:12-slim du -sh /d | cut -f1)"
  sleep 6
done
wait "$WPID" 2>/dev/null; WPID=0
docker rm -f "$NAME" >/dev/null

for r in $(seq 1 "$ROUNDS"); do
  snapvol="$TAG-snap$r"
  docker volume inspect "$snapvol" >/dev/null 2>&1 || continue
  out="$(docker run --rm --entrypoint java -v "$snapvol:/data" "$IMAGE" -jar /app/sectoriadb.jar verify-all 2>&1 | grep -v JAVA_TOOL)"
  echo "$out" | tail -2 | sed 's/^/      /'
  echo "$out" | grep -q '^RESULT: OK' && pass "round $r: verify-all on the snapshot copy is clean" || fail "round $r: verify-all on the snapshot copy"
  run_server "$SNAP_NAME" "$snapvol" "$SNAP_S3_PORT" "$SNAP_MGMT_PORT" || { fail "round $r: server start on the snapshot copy"; continue; }
  res="$(FAULT_ENDPOINT="http://localhost:$SNAP_S3_PORT" "$PY" "$FAULTS_DIR/snapshot_check.py" check snapb "$WORK/snapshot-writes-$r.log" 2>&1)"
  echo "$res" | sed 's/^/      /'
  case "$res" in "CHECK ok"*) pass "round $r: every acknowledged operation is present with the right checksum" ;; *) fail "round $r: content check" ;; esac
  check "round $r: no integrity failures counted" test "$(curl -s -m 3 "http://localhost:$SNAP_MGMT_PORT/actuator/prometheus" | grep -E '^sectoriadb_integrity_crc_failures_total' | awk '{s += $NF} END {printf "%.0f\n", s}')" = 0
  docker rm -f "$SNAP_NAME" >/dev/null
done
finish
