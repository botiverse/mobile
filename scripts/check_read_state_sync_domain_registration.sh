#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE="compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/ReadStateSyncDomain.kt"

# Fail closed: if the guarded file is renamed/moved, this gate must break
# loudly instead of silently passing on rg's file-not-found exit code.
if [[ ! -f "$SOURCE" ]]; then
  echo "Guarded source file missing: $SOURCE (renamed? update this guard)" >&2
  exit 1
fi

# rg exit codes: 0 = match (violation), 1 = no match (pass), >=2 = error.
set +e
matches="$(rg -n 'SyncDomainConfig|READ_STATE_SYNC_DOMAIN|readStateSyncDomain' "$SOURCE")"
status=$?
set -e
case "$status" in
  0)
    printf '%s\n' "$matches"
    echo "Read-state wire versions must not be registered as a transport-frame SyncCore domain" >&2
    exit 1
    ;;
  1) ;;
  *)
    echo "rg failed (exit $status) while scanning $SOURCE" >&2
    exit "$status"
    ;;
esac

echo "Read-state SyncCore production-registration guard passed"
