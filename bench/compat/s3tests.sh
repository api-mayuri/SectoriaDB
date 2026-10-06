#!/usr/bin/env bash
# ceph/s3-tests against a running SectoriaDB (two accounts: main + alt; SectoriaDB has no IAM, so both are plain keys).
#
#   ./s3tests.sh                       # default subset, 20 min timeout
#   S3TESTS_FILTER='-k test_bucket_list' ./s3tests.sh
#   S3TESTS_FULL=1 TIMEOUT=7200 ./s3tests.sh      # everything not excluded by markers (hours)
#
# Needs: git, python3 with venv, network access to GitHub and PyPI for the first run (checkout goes to bench/compat/work/).
# Output: bench/compat/work/s3tests-<UTC>/{junit.xml,log.txt,summary.json,summary.txt}; summary.txt is the short form.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../warp/lib.sh"

: "${S3_HOST:=localhost}"; : "${S3_PORT:=8080}"
: "${MAIN_ACCESS:=${SECTORIADB_ACCESS_KEY:-BENCHACCESSKEY0001}}"; : "${MAIN_SECRET:=${SECTORIADB_SECRET_KEY:-benchsecretkey0123456789abcdef}}"
: "${ALT_ACCESS:=${SECTORIADB_ALT_ACCESS_KEY:-ALTACCESSKEY00002}}"; : "${ALT_SECRET:=${SECTORIADB_ALT_SECRET_KEY:-altsecretkey0123456789abcdef}}"
: "${TIMEOUT:=1200}"
: "${S3TESTS_REF:=master}"
WORK="$HERE/work"; mkdir -p "$WORK"
SRC="$WORK/s3-tests"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$S3TESTS_REF" https://github.com/ceph/s3-tests.git "$SRC"
fi
S3TESTS_COMMIT="$(git -C "$SRC" rev-parse --short HEAD)"
if [ ! -x "$WORK/venv/bin/pytest" ]; then
  python3 -m venv "$WORK/venv"
  "$WORK/venv/bin/pip" install -q --upgrade pip
  "$WORK/venv/bin/pip" install -q -r "$SRC/requirements.txt" pytest-timeout
fi

OUT="$WORK/s3tests-$(utc_now)"; mkdir -p "$OUT"
CONF="$OUT/s3tests.conf"
sed -e "s|@HOST@|$S3_HOST|; s|@PORT@|$S3_PORT|; s|@MAIN_ACCESS@|$MAIN_ACCESS|; s|@MAIN_SECRET@|$MAIN_SECRET|; s|@ALT_ACCESS@|$ALT_ACCESS|; s|@ALT_SECRET@|$ALT_SECRET|" \
    "$HERE/s3tests.conf.template" > "$CONF"

# Tests that need features SectoriaDB deliberately does not have are deselected up front (IAM/STS/SNS/lifecycle/website/
# versioning/encryption/object lock/CORS/...) so the numbers show what could pass. S3TESTS_FULL=1 runs everything.
MARKERS='not fails_on_aws and not fails_strict_rfc2616 and not sse_s3 and not encryption and not tagging and not lifecycle and not lifecycle_expiration and not versioning and not iam_account and not iam_tenant and not iam_role and not iam_user and not iam_cross_account and not sns and not sts_test and not webidentity_test and not s3select and not object_lock and not bucket_logging and not cloud_transition and not cloud_restore and not checksum and not delete_marker and not group and not appendobject and not s3website and not auth_aws2 and not auth_common and not role_policy and not user_policy and not group_policy and not bucket_policy_status and not abac_test and not storage_class and not worm and not fails_with_subdomain and not ragweed_test'
[ "${S3TESTS_FULL:-0}" = 1 ] && MARKERS='not fails_on_aws and not sts_test'
# shellcheck disable=SC2086
(
  cd "$SRC"
  S3TEST_CONF="$CONF" timeout "$TIMEOUT" "$WORK/venv/bin/pytest" s3tests_boto3/functional/test_s3.py \
    -m "$MARKERS" -p no:cacheprovider --timeout=60 -q -rfE --junitxml="$OUT/junit.xml" ${S3TESTS_FILTER:-} \
    > "$OUT/log.txt" 2>&1
) || echo "pytest exited with $? (timeout=$TIMEOUT s; failures are expected)"

python3 -I "$HERE/summarize_s3tests.py" "$OUT" "$S3TESTS_COMMIT" | tee "$OUT/summary.txt"
