#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GENERATED_ROOT="${1:-$REPO_ROOT/compose/shared/build/generated/tmm-res/ohosArm64Main}"
ENTRY_ROOT="${2:-$REPO_ROOT/compose/ohosApp/entry}"
OWN_RESOURCES="$GENERATED_ROOT/res"
MERGED_RESOURCES="$GENERATED_ROOT/mergeRes"
MR_DEST="$ENTRY_ROOT/mr-res"
KUIKLY_ASSET_DEST="$ENTRY_ROOT/mr-kuikly-assets/resfile/assets"

[[ -d "$OWN_RESOURCES" ]] || {
  echo "Generated OHOS MR resources not found: $OWN_RESOURCES" >&2
  exit 1
}

mkdir -p "$MR_DEST" "$KUIKLY_ASSET_DEST"
find "$MR_DEST" -mindepth 1 -maxdepth 1 ! -name .gitkeep -exec rm -rf -- {} +
rm -rf "$ENTRY_ROOT/mr-kuikly-assets/resfile"
mkdir -p "$KUIKLY_ASSET_DEST"

normalize_resource_directory() {
  local name="$1"
  if [[ "$name" =~ ^[a-z]{2,3}(-[A-Za-z]{2,8})+$ ]]; then
    printf '%s\n' "${name//-/_}"
  else
    printf '%s\n' "$name"
  fi
}

stage_resource_root() {
  local source_root="$1"
  [[ -d "$source_root" ]] || return 0

  local child name normalized target
  shopt -s nullglob
  for child in "$source_root"/*; do
    name="$(basename "$child")"
    normalized="$(normalize_resource_directory "$name")"
    target="$MR_DEST/$normalized"
    if [[ -d "$child" ]]; then
      mkdir -p "$target"
      cp -a "$child/." "$target/"
    else
      cp -a "$child" "$target"
    fi
  done
  shopt -u nullglob
}

stage_kuikly_assets() {
  local source_root="$1"
  local assets="$source_root/rawfile/assets"
  [[ -d "$assets" ]] || return 0
  cp -a "$assets/." "$KUIKLY_ASSET_DEST/"
}

# Dependency resources, when available, are the lower-priority layer. The
# app's generated resources are copied last so its keys remain authoritative.
stage_resource_root "$MERGED_RESOURCES"
stage_resource_root "$OWN_RESOURCES"
stage_kuikly_assets "$MERGED_RESOURCES"
stage_kuikly_assets "$OWN_RESOURCES"

BASE_STRINGS="$MR_DEST/base/element/string.json"
[[ -s "$BASE_STRINGS" ]] || {
  echo "Staged OHOS MR base strings are missing" >&2
  exit 1
}
grep -Fq 'slock_thread_show_more' "$BASE_STRINGS" || {
  echo "Staged OHOS MR strings do not contain slock_thread_show_more" >&2
  exit 1
}

for relative in \
  rawfile/assets/slock_reactions/reaction_atlas_v1.png \
  rawfile/assets/slock_reactions/reaction_atlas_v1_manifest.json; do
  [[ -s "$MR_DEST/$relative" ]] || {
    echo "Missing staged OHOS MR resource: $relative" >&2
    exit 1
  }
done

for relative in \
  slock_reactions/reaction_atlas_v1.png \
  slock_reactions/reaction_atlas_v1_manifest.json; do
  [[ -s "$KUIKLY_ASSET_DEST/$relative" ]] || {
    echo "Missing staged Kuikly asset: $relative" >&2
    exit 1
  }
done

printf 'Staged OHOS MR resources: mr_files=%s kuikly_asset_files=%s\n' \
  "$(find "$MR_DEST" -type f ! -name .gitkeep | wc -l | tr -d '[:space:]')" \
  "$(find "$KUIKLY_ASSET_DEST" -type f | wc -l | tr -d '[:space:]')"
