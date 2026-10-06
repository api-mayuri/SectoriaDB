#!/usr/bin/env bash
# minio/mint (SDK and tool compatibility suites) against SectoriaDB, one suite per container run with a timeout.
#
#   ./mint.sh                              # default suites, 10 min each
#   SUITES="awscli minio-go" TIMEOUT=900 ./mint.sh
#
# Suites available in the image (core mode): awscli aws-sdk-go-v2 aws-sdk-java-v2 aws-sdk-php aws-sdk-ruby mc minio-go
# minio-java minio-js minio-py s3cmd healthcheck versioning s3select (the last two need features SectoriaDB lacks).
# Output: bench/compat/work/mint-<UTC>/<suite>/log.json and summary.txt / summary.json.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../warp/lib.sh"

MINT_IMAGE="${MINT_IMAGE:-minio/mint:edge@sha256:08a05e68893c68be2a83b6f79556853ed6aa3c6c9e64c823a00853e4e55d2200}"
SUITES="${SUITES:-awscli aws-sdk-go-v2 aws-sdk-java-v2 s3cmd minio-go mc minio-py minio-js aws-sdk-ruby aws-sdk-php minio-java}"
TIMEOUT="${TIMEOUT:-600}"
: "${MINT_ENDPOINT:=sectoriadb:8080}"
OUT="$HERE/work/mint-$(utc_now)"; mkdir -p "$OUT"

for s in $SUITES; do
  mkdir -p "$OUT/$s"
  echo "== mint $s (timeout ${TIMEOUT}s)"
  timeout "$TIMEOUT" docker run --rm --name "mint-$s" --network "$WARP_NETWORK" \
    -e SERVER_ENDPOINT="$MINT_ENDPOINT" -e ACCESS_KEY="$WARP_ACCESS_KEY" -e SECRET_KEY="$WARP_SECRET_KEY" \
    -e ENABLE_HTTPS=0 -e SERVER_REGION=us-east-1 -e MINT_MODE=core -e RUN_ON_FAIL=1 \
    -v "$OUT/$s:/mint/log" "$MINT_IMAGE" "$s" > "$OUT/$s/stdout.txt" 2>&1
  rc=$?
  [ "$rc" -eq 124 ] && { echo "   timed out"; docker rm -f "mint-$s" >/dev/null 2>&1; }
  echo "$rc" > "$OUT/$s/exit-code"
done
python3 -I "$HERE/summarize_mint.py" "$OUT" | tee "$OUT/summary.txt"
