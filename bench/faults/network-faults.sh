#!/usr/bin/env bash
# Network faults. 1) tc netem (latency / loss / reset) when the kernel and an image with iproute2 allow it;
# 2) always: a userspace proxy (slowproxy.py) that cuts a PUT in the middle, adds latency and limits bandwidth.
#   NETEM_IMAGE=nicolaka/netshoot ./network-faults.sh
#
# Manual netem on a real host (what the docs recommend):
#   docker run --rm --cap-add NET_ADMIN --network container:sectoriadb-fault nicolaka/netshoot \
#       tc qdisc add dev eth0 root netem delay 100ms 20ms loss 1%        # + `... change ... loss 5%`, `... del dev eth0 root`
# Needs CONFIG_NET_SCH_NETEM in the kernel of the Docker host; the sandbox kernel of this repository's CI image has none.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
fresh_data; mkdir -p "$WORK/files"
start_server || exit 2
s3c mb netb >/dev/null
mkfile "$WORK/files/n.bin" 40; WANT="$(sha "$WORK/files/n.bin")"; SIZE=$(( 40 * 1048576 ))

# ---- 1. tc netem ----
NETEM_IMAGE="${NETEM_IMAGE:-nicolaka/netshoot}"
if docker image inspect "$NETEM_IMAGE" >/dev/null 2>&1 || docker pull -q "$NETEM_IMAGE" >/dev/null 2>&1; then
  if docker run --rm --cap-add NET_ADMIN --network "container:$NAME" "$NETEM_IMAGE" tc qdisc add dev eth0 root netem delay 80ms 20ms loss 1% 2>"$WORK/tc.err"; then
    t=$(date +%s.%N); r="$(s3c put netb with-netem "$WORK/files/n.bin")"; el=$(awk -v a="$t" -v b="$(date +%s.%N)" 'BEGIN{printf "%.1f", b-a}')
    [ "$(s3c get netb with-netem)" = "GET ok $SIZE $WANT" ] && pass "PUT/GET intact under netem delay 80ms±20ms loss 1% ($r in ${el}s)" || fail "object damaged under netem"
    docker run --rm --cap-add NET_ADMIN --network "container:$NAME" "$NETEM_IMAGE" tc qdisc del dev eth0 root
  else
    info "tc netem not possible here: $(tr '\n' ' ' < "$WORK/tc.err")"
  fi
else
  info "no image with tc available ($NETEM_IMAGE); skipping netem"
fi

# ---- 2. userspace proxy ----
PP=$((S3_PORT + 1))
"$PY" "$FAULTS_DIR/slowproxy.py" --listen "$PP" --target "localhost:$S3_PORT" --cut-after-bytes $(( 15 * 1048576 )) > "$WORK/proxy.log" 2>&1 &
proxy=$!; sleep 1
before="$(metric 'sectoriadb_s3_requests_inflight')"
FAULT_ENDPOINT="http://localhost:$PP" "$PY" "$FAULTS_DIR/s3c.py" put netb cut "$WORK/files/n.bin" | sed 's/^/      client: /'
kill "$proxy" 2>/dev/null; sleep 2
[ "$(s3c get netb cut)" = "GET absent" ] && pass "PUT cut at 15 MiB of 40: no partial object visible" || fail "partial object visible after the connection was cut: $(s3c get netb cut)"
infl="$(metric 'sectoriadb_s3_requests_inflight')"
[ "$infl" = 0 ] && pass "in-flight requests back to 0 after the cut (no leaked request)" || fail "in-flight gauge stuck at $infl after the cut"
info "aborted/error counters: $(curl -s "http://localhost:$MGMT_PORT/actuator/prometheus" | grep -E '^sectoriadb_s3_(errors|requests_aborted)_total' | grep -v ' 0.0$' | sed 's/application="SectoriaDB",//' | tr '\n' ' ')"

"$PY" "$FAULTS_DIR/slowproxy.py" --listen "$PP" --target "localhost:$S3_PORT" --delay-ms 5 --rate-kbps 8000 > "$WORK/proxy.log" 2>&1 &
proxy=$!; sleep 1
t=$(date +%s.%N); r="$(FAULT_ENDPOINT="http://localhost:$PP" s3c put netb slow "$WORK/files/n.bin")"; el=$(awk -v a="$t" -v b="$(date +%s.%N)" 'BEGIN{printf "%.1f", b-a}')
kill "$proxy" 2>/dev/null
[ "$r" = "PUT ok" ] && [ "$(s3c get netb slow)" = "GET ok $SIZE $WANT" ] && pass "PUT through a 8 MB/s, +5 ms/chunk link: intact (${el}s)" || fail "PUT through the slow link: $r"
stop_server
out="$(offline_verify)"; echo "$out" | tail -3 | sed 's/^/      /'
echo "$out" | grep -q "RESULT: OK" && pass "verify-all clean" || fail "verify-all reports problems"
remove_server
finish
