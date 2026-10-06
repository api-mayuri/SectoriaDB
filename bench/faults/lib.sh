#!/usr/bin/env bash
# Shared helpers for the fault and security tests. Source it, do not execute.
# shellcheck disable=SC2034
FAULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$FAULTS_DIR/../.." && pwd)"
WORK="${FAULT_WORK:-$FAULTS_DIR/work}"
IMAGE="${FAULT_IMAGE:-sectoriadb:bench}"
NAME="${FAULT_CONTAINER:-sectoriadb-fault}"
S3_PORT="${FAULT_S3_PORT:-8090}"
MGMT_PORT="${FAULT_MGMT_PORT:-9490}"
export FAULT_ENDPOINT="http://localhost:$S3_PORT"
export FAULT_ACCESS_KEY="${FAULT_ACCESS_KEY:-FAULTACCESSKEY0001}"
export FAULT_SECRET_KEY="${FAULT_SECRET_KEY:-faultsecretkey0123456789abcdef}"
export FAULT_ALT_ACCESS_KEY="${FAULT_ALT_ACCESS_KEY:-FAULTALTKEY0000002}"
export FAULT_ALT_SECRET_KEY="${FAULT_ALT_SECRET_KEY:-faultaltsecret0123456789abcdef}"
JAVA_OPTS_FAULT="${FAULT_JAVA_OPTS:--Xmx768m -XX:+UseG1GC -Dlogging.level.org.example.sectoriadb=INFO -Dlogging.level.org.springframework.web=WARN -Dlogging.level.org.springframework.web.servlet.mvc.method.annotation=WARN}"
PY="$WORK/venv/bin/python"

PASSES=0; FAILS=0
pass() { echo "PASS  $*"; PASSES=$((PASSES + 1)); }
fail() { echo "FAIL  $*"; FAILS=$((FAILS + 1)); }
info() { echo "INFO  $*"; }
check() { # check "description" command...   (PASS when the command succeeds)
  local d=$1; shift
  if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}
finish() { echo "== $(basename "$0"): $PASSES passed, $FAILS failed"; [ "$FAILS" -eq 0 ]; }

ensure_venv() {
  mkdir -p "$WORK"
  if [ ! -x "$PY" ]; then
    python3 -m venv "$WORK/venv" && "$WORK/venv/bin/pip" install -q boto3 requests >&2 || { echo "cannot create the python venv (boto3)" >&2; exit 2; }
  fi
}

# fresh_data [extra docker args are given to start_server instead]
fresh_data() { rm -rf "$WORK/data"; mkdir -p "$WORK/data/meta" "$WORK/data/storage"; }

start_server() { # extra docker run args...
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d --name "$NAME" -p "127.0.0.1:$S3_PORT:8080" -p "127.0.0.1:$MGMT_PORT:9464" \
    -e "SECTORIADB_S3_CREDENTIALS=$FAULT_ACCESS_KEY:$FAULT_SECRET_KEY:main,$FAULT_ALT_ACCESS_KEY:$FAULT_ALT_SECRET_KEY:alt" \
    -e "JAVA_OPTS=$JAVA_OPTS_FAULT" \
    -v "$WORK/data/meta:/data/meta" -v "$WORK/data/storage:/data/storage" "$@" "$IMAGE" >/dev/null
  wait_ready
}
wait_ready() {
  local i
  for i in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' -m 2 "$FAULT_ENDPOINT/" 2>/dev/null)" != "000" ] && \
      curl -sf -m 2 "http://localhost:$MGMT_PORT/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "server did not become ready" >&2; docker logs --tail 30 "$NAME" >&2; return 1
}
kill9() { docker kill -s KILL "$NAME" >/dev/null; }
stop_server() { docker stop -t 20 "$NAME" >/dev/null 2>&1 || true; }
remove_server() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
server_logs() { docker logs "$NAME" 2>&1; }

# metric NAME_REGEX_WITH_LABELS -> sum of matching samples on the management port
metric() {
  curl -s -m 3 "http://localhost:$MGMT_PORT/actuator/prometheus" | grep -E "^$1" | awk '{s += $NF} END {printf "%.0f\n", s}'
}

# offline_verify: the server must be stopped (the metastore is single-process). Prints the verify-all output.
offline_verify() {
  docker run --rm --entrypoint java -v "$WORK/data/meta:/data/meta" -v "$WORK/data/storage:/data/storage" "$IMAGE" \
    -Dsectoriadb.meta-dir=/data/meta -Dsectoriadb.data-dir=/data/storage -jar /app/sectoriadb.jar verify-all 2>&1 | grep -v JAVA_TOOL
}

s3c() { "$PY" "$FAULTS_DIR/s3c.py" "$@"; }
# random file of N MiB
mkfile() { head -c "$(( $2 * 1048576 ))" /dev/urandom > "$1"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
