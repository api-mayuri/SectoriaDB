#!/usr/bin/env bash
# Verify a backup on a COPY of it: restores the archive (or copies the directory) into a throwaway volume, runs the
# offline `verify-all` (every blob, chunk CRC, manifest) in a throwaway container, removes the volume.
# Exit code: 0 when verify-all prints "RESULT: OK", non-zero otherwise (also on any error). Safe to run from cron
# while the server is running: it does not touch the live volume or its container.
#
#   deploy/backup/verify-backup.sh ARCHIVE.tar.zst|ARCHIVE.tar.gz|DIRECTORY
#
# DIRECTORY is a copy or a mounted snapshot of the volume root (it contains meta/ and storage/); it is only read.
# Environment: SECTORIADB_IMAGE (default: image of the compose service), BACKUP_IMAGE (debian:12-slim).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

src="${1:?usage: verify-backup.sh ARCHIVE|DIRECTORY}"
BACKUP_IMAGE="${BACKUP_IMAGE:-debian:12-slim}"
if [ -z "${SECTORIADB_IMAGE:-}" ]; then
  SECTORIADB_IMAGE="$(docker compose config --images 2>/dev/null | grep -m1 sectoriadb || true)"
  SECTORIADB_IMAGE="${SECTORIADB_IMAGE:-sectoriadb:latest}"
fi
vol="sectoriadb-verify-$$-$(date +%s)"
docker volume create "$vol" >/dev/null
trap 'docker volume rm -f "$vol" >/dev/null 2>&1 || true' EXIT

if [ -d "$src" ]; then
  docker run --rm -v "$(cd "$src" && pwd):/src:ro" -v "$vol:/data" "$BACKUP_IMAGE" cp -a --sparse=always /src/. /data/
else
  case "$src" in
    *.tar.zst) decompress=(zstd -dc) ;; *.tar.gz) decompress=(gzip -dc) ;; *.tar) decompress=(cat) ;;
    *) echo "error: expected a directory or .tar.zst/.tar.gz/.tar" >&2; exit 2 ;;
  esac
  "${decompress[@]}" < "$src" | docker run --rm -i -v "$vol:/data" "$BACKUP_IMAGE" tar -C /data -x -p --numeric-owner -f -
fi

# same invocation as docs/guide/admin-shell.md, on the copy; the image's ENV points meta-dir and data-dir at /data/*
out="$(docker run --rm --entrypoint java -v "$vol:/data" "$SECTORIADB_IMAGE" -jar /app/sectoriadb.jar verify-all 2>&1 | grep -v JAVA_TOOL_OPTIONS || true)"
echo "$out" | tail -8
if echo "$out" | grep -q '^RESULT: OK'; then echo "PASS  backup verified: $src"; else echo "FAIL  backup is NOT clean: $src" >&2; exit 1; fi
