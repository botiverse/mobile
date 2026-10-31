#!/usr/bin/env bash
# Keeps the Android notification copy keys and their vendored catalogue on one pinned slock commit.
#
# Provenance: compose/androidApp/.../AndroidPushPreviewRendering.kt (AndroidPushCopyKeys) maps every
# key of `NOTIFICATION_COPY` (packages/shared/src/notificationCopy.ts) to a string resource, and
# AndroidNotificationCopyVectorTest.kt vendors that catalogue, both at github.com/botiverse/slock @ the
# SHA in AndroidPushCopyKeys.SLOCK_SHA. The check is fully local: the SHA in the table, the vector test
# header and the test's own assertion must be the same 40-hex commit. To update, re-copy the catalogue,
# add the strings (en / zh-CN / ja) and keys, and bump all three together.
set -euo pipefail

readonly PORT="compose/androidApp/src/main/java/build/raft/app/shared/AndroidPushPreviewRendering.kt"
readonly TEST="compose/androidApp/src/test/java/build/raft/app/shared/AndroidNotificationCopyVectorTest.kt"

for file in "$PORT" "$TEST"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing notification copy file: $file" >&2
    exit 1
  fi
done

port_sha="$(sed -n 's/^    const val SLOCK_SHA = "\([0-9a-f]\{40\}\)"$/\1/p' "$PORT")"
header_sha="$(sed -n 's|^//   github.com/botiverse/slock PR #[0-9]* (merged to staging) @ \([0-9a-f]\{40\}\)$|\1|p' "$TEST")"
assert_sha="$(sed -n 's/.*assertEquals("\([0-9a-f]\{40\}\)", AndroidPushCopyKeys.SLOCK_SHA).*/\1/p' "$TEST")"

if [[ -z "$port_sha" || -z "$header_sha" || -z "$assert_sha" ]]; then
  echo "Could not read the pinned slock SHA (port='$port_sha' header='$header_sha' assert='$assert_sha')" >&2
  exit 1
fi

if [[ "$port_sha" != "$header_sha" || "$port_sha" != "$assert_sha" ]]; then
  echo "Notification copy pins drifted: port=$port_sha header=$header_sha assert=$assert_sha" >&2
  exit 1
fi

echo "Notification copy vector pin verified: $port_sha"
