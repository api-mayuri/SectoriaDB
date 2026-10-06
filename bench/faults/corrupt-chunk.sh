#!/usr/bin/env bash
# Bit rot: flip bytes inside a stored object on disk (while the server is stopped). Expected: GET never returns
# wrong bytes silently (error or aborted body), the CRC failure counter rises, offline verify-all reports the object.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
fresh_data; mkdir -p "$WORK/files"
mkfile "$WORK/files/big.bin" 3; head -c 5000 /dev/urandom > "$WORK/files/small.bin"
BIG="$(sha "$WORK/files/big.bin")"; SMALL="$(sha "$WORK/files/small.bin")"
start_server || exit 2
s3c mb corb >/dev/null
s3c put corb big "$WORK/files/big.bin" >/dev/null; s3c put corb small "$WORK/files/small.bin" >/dev/null
s3c put corb untouched "$WORK/files/big.bin" >/dev/null     # same bytes as 'big': deduplicated chunks; see below
stop_server
RAW="$(find "$WORK/data/storage/corb" -name 'blob_*.raw' | head -1)"; SOB="$(find "$WORK/data/storage/corb" -name 'small_*.sob' | head -1)"
info "chunk blob: $(basename "${RAW:-none}")  small-object file: $(basename "${SOB:-none}")"
# locate the 3 MiB random payload in the (sparse) blob by its first 4 KiB and flip bytes in the middle of it
"$PY" - "$RAW" "$WORK/files/big.bin" <<'PYEOF'
import sys, mmap
raw, src = sys.argv[1], sys.argv[2]
needle = open(src, "rb").read()[:4096]
with open(raw, "r+b") as f:
    mm = mmap.mmap(f.fileno(), 0)
    pos = mm.find(needle)
    if pos < 0:
        print("PAYLOAD NOT FOUND"); sys.exit(3)
    for off in (pos + 100, pos + 3000):
        mm[off] ^= 0xFF
    mm.flush(); mm.close()
    print("flipped 2 bytes in the first chunk at blob offset", pos)
PYEOF
[ $? -eq 0 ] || fail "could not locate chunk data in the blob"
# small object: flip a byte in the middle of the record
"$PY" - "$SOB" "$WORK/files/small.bin" <<'PYEOF'
import sys
sob, src = sys.argv[1], sys.argv[2]
needle = open(src, "rb").read()[:512]
data = bytearray(open(sob, "rb").read())
pos = data.find(needle)
if pos < 0:
    print("SMALL PAYLOAD NOT FOUND"); sys.exit(3)
data[pos + 1000] ^= 0xFF
open(sob, "wb").write(data)
print("flipped 1 byte in the small object record at offset", pos)
PYEOF
[ $? -eq 0 ] || fail "could not locate the small object in its blob"

out="$(offline_verify)"; echo "$out" | sed 's/^/      /' | head -12
echo "$out" | grep -q "RESULT: FAILED" && pass "offline verify-all detects the corruption" || fail "offline verify-all did not detect the corruption"
start_server || exit 2
for k in big small untouched; do
  r="$(s3c get corb "$k")"; want="$BIG"; [ "$k" = small ] && want="$SMALL"
  case "$r" in
    "GET ok "*" $want") fail "GET $k returned the ORIGINAL bytes although a byte was flipped on disk (not reached or healed): $r" ;;
    "GET ok "*) fail "GET $k returned WRONG bytes with 200 (silent corruption): $r" ;;
    *) pass "GET $k does not return corrupted data: $r" ;;
  esac
done
sleep 1
n="$(metric 'sectoriadb_integrity_crc_failures_total')"
[ "$n" -ge 1 ] && pass "sectoriadb_integrity_crc_failures_total rose to $n" || fail "CRC failure counter did not rise"
info "by kind: $(curl -s "http://localhost:$MGMT_PORT/actuator/prometheus" | grep '^sectoriadb_integrity_crc_failures_total' | sed 's/application="SectoriaDB",//' | tr '\n' ' ')"
info "aborted requests: $(metric 'sectoriadb_s3_requests_aborted_total')"
remove_server
finish
