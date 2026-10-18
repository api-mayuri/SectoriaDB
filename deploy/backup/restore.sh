#!/usr/bin/env bash
# Restore a cold backup (or any tar of the volume root) into a FRESH volume. Refuses to touch an existing volume.
#
#   deploy/backup/restore.sh ARCHIVE [NEW_VOLUME]
#
# Default NEW_VOLUME is the volume docker compose uses (<project>_sectoriadb-store): it must not exist, i.e. run
# `docker compose down` and remove the old volume (`docker volume rm`) first. Afterwards: docker compose up -d.
# Environment: BACKUP_IMAGE (debian:12-slim, GNU tar).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

archive="${1:?usage: restore.sh ARCHIVE [NEW_VOLUME]}"
BACKUP_IMAGE="${BACKUP_IMAGE:-debian:12-slim}"
[ -f "$archive" ] || { echo "error: $archive not found" >&2; exit 2; }
case "$archive" in
  *.tar.zst) decompress=(zstd -dc) ;;
  *.tar.gz)  decompress=(gzip -dc) ;;
  *.tar)     decompress=(cat) ;;
  *) echo "error: unknown archive type (expected .tar.zst, .tar.gz or .tar)" >&2; exit 2 ;;
esac
if [ -f "$archive.sha256" ]; then
  ( cd "$(dirname "$archive")" && sha256sum -c "$(basename "$archive").sha256" >/dev/null ) \
    || { echo "error: checksum mismatch: $archive is damaged" >&2; exit 1; }
fi

project="$(docker compose config 2>/dev/null | sed -n 's/^name: //p')"
volume="${2:-${project}_sectoriadb-store}"
if docker volume inspect "$volume" >/dev/null 2>&1; then
  echo "error: volume $volume already exists; restore only into a fresh volume (docker volume rm $volume, or pass another name)" >&2
  exit 2
fi
# labels make docker compose treat the volume as its own (no "not created by compose" warning)
docker volume create --label "com.docker.compose.project=$project" --label "com.docker.compose.volume=sectoriadb-store" "$volume" >/dev/null
if ! "${decompress[@]}" < "$archive" | docker run --rm -i -v "$volume:/data" "$BACKUP_IMAGE" tar -C /data -x -p --numeric-owner -f -; then
  docker volume rm "$volume" >/dev/null 2>&1 || true
  echo "error: extraction failed, volume removed" >&2; exit 1
fi
echo "OK  restored $archive into volume $volume; next: deploy/backup/verify-backup.sh $archive, then docker compose up -d"
