#!/usr/bin/env bash
# Security and robustness probes (clock skew, path normalisation, unicode keys, oversized input, XXE, cross-account,
# slow clients, huge-prefix List). Starts its own server container on port $FAULT_S3_PORT (default 8090).
#   ./security.sh [auth] [paths] [limits] [xxe] [cross] [list] [slow]     (default: all)
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_venv
fresh_data
start_server || exit 2
export SECURITY_MGMT="http://localhost:$MGMT_PORT"
"$PY" "$FAULTS_DIR/security.py" "$@" | tee "$WORK/security.out"
rc=${PIPESTATUS[0]}
info "server log warnings/errors: $(server_logs | grep -cE ' (WARN|ERROR) ')"
remove_server
exit "$rc"
