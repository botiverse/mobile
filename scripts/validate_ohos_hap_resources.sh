#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: validate_ohos_hap_resources.sh <signed.hap>" >&2
  exit 2
fi

HAP_FILE="$1"
[[ -f "$HAP_FILE" ]] || {
  echo "OHOS HAP not found: $HAP_FILE" >&2
  exit 1
}
command -v unzip >/dev/null
command -v strings >/dev/null

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

RESOURCE_SIZE="$(unzip -l "$HAP_FILE" resources.index | awk '$4 == "resources.index" { print $1; exit }')"
[[ "$RESOURCE_SIZE" =~ ^[0-9]+$ ]] || {
  echo "HAP does not contain resources.index" >&2
  exit 1
}
if (( RESOURCE_SIZE < 100000 )); then
  echo "HAP resources.index is unexpectedly small: ${RESOURCE_SIZE} bytes" >&2
  exit 1
fi

unzip -p "$HAP_FILE" resources.index > "$WORK_DIR/resources.index"
strings "$WORK_DIR/resources.index" > "$WORK_DIR/resources.strings"
for key in slock_thread_show_more slock_auth_restore_tagline; do
  grep -Fq "$key" "$WORK_DIR/resources.strings" || {
    echo "HAP resources.index is missing shared resource key: $key" >&2
    exit 1
  }
done

unzip -Z1 "$HAP_FILE" > "$WORK_DIR/entries.txt"
for entry in \
  resources/rawfile/assets/slock_reactions/reaction_atlas_v1.png \
  resources/rawfile/assets/slock_reactions/reaction_atlas_v1_manifest.json \
  resources/resfile/assets/slock_reactions/reaction_atlas_v1.png \
  resources/resfile/assets/slock_reactions/reaction_atlas_v1_manifest.json; do
  grep -Fxq "$entry" "$WORK_DIR/entries.txt" || {
    echo "HAP is missing packaged OHOS resource: $entry" >&2
    exit 1
  }
done

printf 'Validated OHOS HAP resources: resources.index=%s bytes, shared strings and dual-path atlas present\n' \
  "$RESOURCE_SIZE"
