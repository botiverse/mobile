#!/usr/bin/env bash
# Keeps the human pixel-avatar port, its vendored art and its vectors on one pinned slock commit.
#
# Provenance: compose/shared/.../RaftHumanPixelAvatar.kt ports packages/shared/src/humanAvatar.ts
# (reader + layer composition), RaftHumanPixelAvatarVectorTest.kt copies its test vectors, and
# compose/shared/src/androidUnitTest/resources/humanavatar/ holds byte-exact copies of
# packages/shared/assets/humanAvatarLayers.json and packages/shared/src/fixtures/humanAvatarDefault.png,
# all at github.com/botiverse/slock @ SLOCK_HEAD. The generated Kotlin art
# (RaftHumanPixelAvatarArtData.kt, scripts/generate_human_pixel_avatars.mjs) carries the JSON's
# digest; RaftHumanPixelAvatarArtTest compares it with the vendored JSON cell by cell.
# Content at a pinned commit is immutable, so the check is fully local. To update: copy the two
# files from the new commit, bump SLOCK_HEAD and both digests here, the SHA in the port and the
# vector test, re-port/re-copy what changed, and rerun the generator.
set -euo pipefail

readonly SLOCK_HEAD="853c6998bcef45df5c25e63b1035e2388608721d"
readonly LAYERS_SHA256="f4d2aa393e7de05360ae8cd5ba5eca7af98ee3e4b02aeb0d3928520eeaa860ae"
readonly DEFAULT_PNG_SHA256="42a534dd4f7e60e6bed60a12b5b16ffc31c3ae78d15a27cd4dde5863b24c5fc7"

readonly PORT="compose/shared/src/commonMain/kotlin/build/raft/app/RaftHumanPixelAvatar.kt"
readonly ART="compose/shared/src/commonMain/kotlin/build/raft/app/RaftHumanPixelAvatarArtData.kt"
readonly TEST="compose/shared/src/commonTest/kotlin/build/raft/app/RaftHumanPixelAvatarVectorTest.kt"
readonly LAYERS="compose/shared/src/androidUnitTest/resources/humanavatar/humanAvatarLayers.json"
readonly DEFAULT_PNG="compose/shared/src/androidUnitTest/resources/humanavatar/humanAvatarDefault.png"

for file in "$PORT" "$ART" "$TEST" "$LAYERS" "$DEFAULT_PNG"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing human pixel-avatar file: $file" >&2
    exit 1
  fi
done

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

fail=0
port_sha="$(sed -n 's/^internal const val RAFT_HUMAN_PIXEL_AVATAR_SLOCK_SHA = "\([0-9a-f]\{40\}\)"$/\1/p' "$PORT")"
header_sha="$(sed -n 's|^//   github.com/botiverse/slock staging @ \([0-9a-f]\{40\}\)$|\1|p' "$TEST")"
assert_sha="$(sed -n 's/.*assertEquals("\([0-9a-f]\{40\}\)", RAFT_HUMAN_PIXEL_AVATAR_SLOCK_SHA).*/\1/p' "$TEST")"
for pin in "port=$port_sha" "header=$header_sha" "assert=$assert_sha"; do
  if [[ "${pin#*=}" != "$SLOCK_HEAD" ]]; then
    echo "Human pixel-avatar slock pin drifted: ${pin%%=*} is '${pin#*=}', expected $SLOCK_HEAD" >&2
    fail=1
  fi
done

layers_actual="$(sha256_of "$LAYERS")"
if [[ "$layers_actual" != "$LAYERS_SHA256" ]]; then
  echo "Vendored humanAvatarLayers.json drifted from botiverse/slock@$SLOCK_HEAD: expected $LAYERS_SHA256, got $layers_actual" >&2
  fail=1
fi
png_actual="$(sha256_of "$DEFAULT_PNG")"
if [[ "$png_actual" != "$DEFAULT_PNG_SHA256" ]]; then
  echo "Vendored humanAvatarDefault.png drifted from botiverse/slock@$SLOCK_HEAD: expected $DEFAULT_PNG_SHA256, got $png_actual" >&2
  fail=1
fi
art_sha="$(sed -n 's/^internal const val RAFT_HUMAN_PIXEL_AVATAR_ART_SHA256 = "\([0-9a-f]\{64\}\)"$/\1/p' "$ART")"
if [[ "$art_sha" != "$LAYERS_SHA256" ]]; then
  echo "Generated art was made from another JSON (digest '$art_sha'); run node scripts/generate_human_pixel_avatars.mjs" >&2
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "Human pixel-avatar port, art and vectors pinned to botiverse/slock@$SLOCK_HEAD"
