#!/usr/bin/env bash
# Cold backup of the single data volume: stop the server, archive the volume, start the server again.
#
#   deploy/backup/cold-backup.sh [OUT_DIR]        (default OUT_DIR: $SECTORIADB_BACKUP_DIR or ~/sectoriadb-backups)
#
# The archive holds credentials.json (S3 secrets in the clear): it is created with umask 077 (owner only) and the default
# directory is OUTSIDE the repository (a relative OUT_DIR is taken relative to the current directory, not to the repo;
# backups/ and *.tar.* are ignored by .gitignore and .dockerignore anyway).
#
# The archive is deploy/backup/restore.sh's input: <OUT_DIR>/sectoriadb-<UTC>.tar.zst (tar.gz without zstd on the host)
# plus a .sha256 file. The tar runs in a throwaway container with GNU tar (-S keeps the sparse blob_*.raw sparse; the
# busybox tar of the server image would expand every blob to its full logical size).
# Environment: SERVICE (sectoriadb), VOLUME (default: the volume mounted at /data), BACKUP_IMAGE (debian:12-slim).
set -euo pipefail
umask 077
# resolve the output directory BEFORE changing into the repository: a relative path means relative to the caller
OUT="$(realpath -m "${1:-${SECTORIADB_BACKUP_DIR:-$HOME/sectoriadb-backups}}")"
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

SERVICE="${SERVICE:-sectoriadb}"
BACKUP_IMAGE="${BACKUP_IMAGE:-debian:12-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587}"

cid="$(docker compose ps -aq "$SERVICE")"
[ -n "$cid" ] || { echo "error: no container of service '$SERVICE' (docker compose up -d first)" >&2; exit 2; }
VOLUME="${VOLUME:-$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$cid")}"
[ -n "$VOLUME" ] || { echo "error: the container has no named volume at /data (set VOLUME=...)" >&2; exit 2; }

if command -v zstd >/dev/null 2>&1; then ext=tar.zst; compress=(zstd -T0 -q); else ext=tar.gz; compress=(gzip -c); fi
mkdir -p "$OUT"
chmod 700 "$OUT" 2>/dev/null || true
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
