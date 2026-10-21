#!/usr/bin/env bash
# The same warp workload against SectoriaDB and other S3 servers, one server at a time, on one machine.
#
#   ./compare.sh [--duration 4m] [--fsync both|on|off]
#
# Servers: SectoriaDB (image sectoriadb:bench, fsync on and off), SeaweedFS, Versity S3 Gateway (posix) and Garage
# (fsync off and on). MinIO is not included: its images are no longer published on Docker Hub; add it by hand if you
# have an image. Results: bench/results/compare_<time>/<server>/warp.txt and a summary table summary.tsv.
#
# Fairness: SeaweedFS, Versity and Garage acknowledge writes without fsync by default; compare SectoriaDB fsync=off
# with their defaults and SectoriaDB fsync=on with Versity --fsync and Garage data_fsync/metadata_fsync = true.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
D=4m; FSYNC=both
while [ $# -gt 0 ]; do case "$1" in --duration) D=$2; shift 2 ;; --fsync) FSYNC=$2; shift 2 ;; *) echo "unknown option $1" >&2; exit 2 ;; esac; done

NET=sectoria-compare
WARP=minio/warp:latest@sha256:72ae1b02216b51bd102b73a6923b597c092a5acc7a655c936c2f31bee897a7c5   # warp 1.3.1
SEAWEED=chrislusf/seaweedfs:latest; VERSITY=versity/versitygw:v1.9.0; GARAGE=dxflrs/garage:v1.0.1
AK=BENCHACCESSKEY0001; SK=benchsecretkey0123456789abcdef
OUT="../results/compare_$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
docker network create "$NET" >/dev/null 2>&1 || true

cleanup() { docker rm -f cmp-server >/dev/null 2>&1 || true; docker volume rm -f cmp-data >/dev/null 2>&1 || true; }
trap cleanup EXIT

wait_port() { for _ in $(seq 60); do docker run --rm --network "$NET" curlimages/curl:latest -s -o /dev/null "http://$1/" && return; sleep 2; done; }

bench() {   # name host access secret
  mkdir -p "$OUT/$1"
  WARP_ACCESS_KEY=$3 WARP_SECRET_KEY=$4 docker run --rm --network "$NET" -e WARP_ACCESS_KEY -e WARP_SECRET_KEY \
    -v "$OUT/$1:/out" "$WARP" mixed --no-color --host "$2" --bucket "cmp-$1" --concurrent 20 --duration "$D" \
    --benchdata /out/warp --obj.size 64KiB --obj.randsize --objects 10000 --get-distrib 40 --delete-distrib 15 \
    > "$OUT/$1/warp.txt" 2>&1 || echo "warp failed for $1 (see $OUT/$1/warp.txt)"
  docker stats --no-stream --format '{{.MemUsage}}' cmp-server > "$OUT/$1/mem.txt" || true
  cleanup
}

sectoria() {   # fsync
  docker run -d --name cmp-server --network "$NET" -v cmp-data:/data \
    -e SECTORIADB_S3_CREDENTIALS="$AK:$SK|bench" -e SECTORIADB_S3_LIMITS_MAX_INFLIGHT_WRITES_PER_KEY=0 \
    -e SECTORIADB_S3_LIMITS_MAX_INFLIGHT_READS_PER_KEY=0 -e SPRING_SHELL_INTERACTIVE_ENABLED=false \
    -e "JAVA_OPTS=-Xmx1g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dsectoriadb.fsync=$1" sectoriadb:bench >/dev/null
  wait_port cmp-server:8080; bench "sectoria-fsync-$1" cmp-server:8080 "$AK" "$SK"
}
seaweed() {
  printf '{"identities":[{"name":"bench","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","Write","List","Tagging"]}]}' "$AK" "$SK" > "$OUT/s3.json"
  docker run -d --name cmp-server --network "$NET" -v cmp-data:/data -v "$OUT/s3.json:/etc/s3.json:ro" "$SEAWEED" \
    server -dir=/data -s3 -s3.port=8333 -s3.config=/etc/s3.json >/dev/null
  wait_port cmp-server:8333; sleep 5; bench seaweedfs cmp-server:8333 "$AK" "$SK"
}
versity() {   # extra posix flag ("" or --fsync)
  docker run -d --name cmp-server --network "$NET" -v cmp-data:/data -e ROOT_ACCESS_KEY="$AK" -e ROOT_SECRET_KEY="$SK" \
    "$VERSITY" --port :7070 posix $1 /data >/dev/null
  wait_port cmp-server:7070; bench "versity${1:+-fsync}" cmp-server:7070 "$AK" "$SK"
}
garage() {   # fsync true|false
  cat > "$OUT/garage.toml" <<TOML
metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
db_engine = "lmdb"
replication_factor = 1
rpc_bind_addr = "0.0.0.0:3901"
rpc_public_addr = "127.0.0.1:3901"
rpc_secret = "$(openssl rand -hex 32)"
metadata_fsync = $1
data_fsync = $1
[s3_api]
s3_region = "us-east-1"
api_bind_addr = "0.0.0.0:3900"
root_domain = ".s3.garage"
TOML
  docker run -d --name cmp-server --network "$NET" -v "$OUT/garage.toml:/etc/garage.toml:ro" -v cmp-data:/var/lib/garage "$GARAGE" >/dev/null
  sleep 5
  local id key ga gs
  id=$(docker exec cmp-server /garage node id -q | cut -d@ -f1)
  docker exec cmp-server /garage layout assign -z dc1 -c 10G "$id" >/dev/null
  docker exec cmp-server /garage layout apply --version 1 >/dev/null
  key=$(docker exec cmp-server /garage key create bench); ga=$(awk '/Key ID/{print $3}' <<<"$key"); gs=$(awk '/Secret key/{print $3}' <<<"$key")
  docker exec cmp-server /garage key allow --create-bucket bench >/dev/null
  bench "garage-fsync-$1" cmp-server:3900 "$ga" "$gs"
}

case "$FSYNC" in off|both) sectoria false; seaweed; versity ""; garage false ;; esac
case "$FSYNC" in on|both)  sectoria true; versity --fsync; garage true ;; esac

# summary: total operations per second and the average/p99 latency of each operation as warp prints them
{ printf 'server\ttotal_obj_s\tget_obj_s\tput_obj_s\n'
  for d in "$OUT"/*/; do n=$(basename "$d"); f="$d/warp.txt"; [ -f "$f" ] || continue
    tot=$(awk '/Report: Total/{t=1} t&&/Average/{print $NF=="obj/s"?$(NF-1):$NF; exit}' "$f")
    get=$(awk '/Report: GET/{t=1} t&&/Average/{print $(NF-1); exit}' "$f")
    put=$(awk '/Report: PUT/{t=1} t&&/Average/{print $(NF-1); exit}' "$f")
    printf '%s\t%s\t%s\t%s\n' "$n" "$tot" "$get" "$put"; done; } | tee "$OUT/summary.tsv"
