#!/usr/bin/env bash
# All fault and security tests one after another; prints a PASS/FAIL line per script.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 2
rc=0
for t in kill-put.sh kill-multipart.sh full-disk.sh corrupt-chunk.sh network-faults.sh security.sh; do
  echo "######## $t"
  ./"$t" | tee "work/${t%.sh}.log" | tail -n 400
  r=${PIPESTATUS[0]}; [ "$r" -eq 0 ] && echo "SUMMARY PASS $t" || { echo "SUMMARY FAIL $t (rc=$r)"; rc=1; }
done
exit $rc
