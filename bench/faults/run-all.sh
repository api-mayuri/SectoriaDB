#!/usr/bin/env bash
# All fault and security tests one after another; prints a PASS/FAIL line per script.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 2
rc=0
mkdir -p work
for t in kill-put.sh kill-multipart.sh full-disk.sh corrupt-chunk.sh snapshot-backup.sh network-faults.sh security.sh; do
  echo "######## $t"
  # live output on the terminal, the full log in work/<script>.log (the first script no longer loses its log)
  ./"$t" 2>&1 | tee "work/${t%.sh}.log"
  r=${PIPESTATUS[0]}; [ "$r" -eq 0 ] && echo "SUMMARY PASS $t" || { echo "SUMMARY FAIL $t (rc=$r)"; rc=1; }
done
exit $rc
