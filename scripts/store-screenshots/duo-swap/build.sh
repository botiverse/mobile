#!/bin/bash
# Builds duo_swap for the arm64 simulator. The simulator reads entitlements from the binary's
# __TEXT,__entitlements section (as Xcode does for simulator builds), not from the signature.
# usage: build.sh <out> [plain]   (plain: no entitlements)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/duo_swap}
ent=(-Wl,-sectcreate,__TEXT,__entitlements,"$here/entitlements.plist")
[ "${2:-}" = plain ] && ent=(-Wl,-dead_strip)
xcrun -sdk iphonesimulator clang -arch arm64 -mios-simulator-version-min=26.0 -fobjc-arc -framework Foundation \
  "${ent[@]}" "$here/duo_swap.m" -o "$out"
codesign -f -s - "$out"
echo "$out"
