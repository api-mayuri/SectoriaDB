#!/usr/bin/env bash
# Cold backup of the single data volume: stop the server, archive the volume, start the server again.
#
#   deploy/backup/cold-backup.sh [OUT_DIR]        (default OUT_DIR: ./backups)
#
# The archive is deploy/backup/restore.sh's input: <OUT_DIR>/sectoriadb-<UTC>.tar.zst (tar.gz without zstd on the host)
# plus a .sha256 file. The tar runs in a throwaway container with GNU tar (-S keeps the sparse blob_*.raw sparse; the
# busybox tar of the server image would expand every blob to its full logical size).
# Environment: SERVICE (sectoriadb), VOLUME (default: the volume mounted at /data), BACKUP_IMAGE (debian:12-slim).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

SERVICE="${SERVICE:-sectoriadb}"
BACKUP_IMAGE="${BACKUP_IMAGE:-debian:12-slim}"
OUT="${1:-backups}"

cid="$(docker compose ps -aq "$SERVICE")"
[ -n "$cid" ] || { echo "error: no container of service '$SERVICE' (docker compose up -d first)" >&2; exit 2; }
VOLUME="${VOLUME:-$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$cid")}"
[ -n "$VOLUME" ] || { echo "error: the container has no named volume at /data (set VOLUME=...)" >&2; exit 2; }

if command -v zstd >/dev/null 2>&1; then ext=tar.zst; compress=(zstd -T0 -q); else ext=tar.gz; compress=(gzip -c); fi
mkdir -p "$OUT"
file="$OUT/sectoriadb-$(date -u +%Y%m%dT%H%M%SZ).$ext"

was_running="$(docker compose ps -q --status running "$SERVICE")"
restart() { [ -z "$was_running" ] || docker compose start "$SERVICE" >/dev/null; }
trap 'restart; rm -f "$file.part"' EXIT

echo "== stopping $SERVICE (graceful)"
docker compose stop "$SERVICE" >/dev/null
echo "== archiving volume $VOLUME -> $file"
docker run --rm -v "$VOLUME:/data:ro" "$BACKUP_IMAGE" tar -C /data -S --numeric-owner -cf - . | "${compress[@]}" > "$file.part"
mv "$file.part" "$file"
( cd "$OUT" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256" )
echo "== starting $SERVICE"
restart; trap - EXIT
echo "OK  $file ($(du -h "$file" | cut -f1))"
