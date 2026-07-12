#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE="compose/shared/src/commonMain/kotlin/build/raft/app/network/sync/ReadStateSyncDomain.kt"

if rg -n 'SyncDomainConfig|READ_STATE_SYNC_DOMAIN|readStateSyncDomain' "$SOURCE"; then
  echo "Read-state wire versions must not be registered as a transport-frame SyncCore domain" >&2
  exit 1
fi

echo "Read-state SyncCore production-registration guard passed"
