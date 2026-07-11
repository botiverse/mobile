#!/usr/bin/env bash
# Attachment layering gate: wire/domain mappers preserve raw sizeBytes. Only UI/presentation code may
# call the attachment-size formatter; otherwise the raw fact is irreversibly lost before rendering.
set -euo pipefail

root="${1:-compose/shared/src/commonMain/kotlin/build/raft/app}"
pattern='format(Attachment|ThreadAttachment)Size'
files=(
  "$root/data/AttachmentsParser.kt"
  "$root/data/MessageParser.kt"
  "$root/data/ThreadMessagesParser.kt"
  "$root/data/realtime/RealtimeEventMapper.kt"
  "$root/network/sync/CanonicalMessageRowProjection.kt"
  "$root/network/sync/MessageWireCanonicalDecoder.kt"
)

failed=0
for file in "${files[@]}"; do
  [ -f "$file" ] || continue
  while IFS=: read -r line_number line; do
    echo "::error file=${file},line=${line_number}::attachment mapper must preserve raw sizeBytes; move formatting to the UI boundary" >&2
    failed=1
  done < <(rg -n "$pattern" "$file" || true)
done
exit "$failed"
