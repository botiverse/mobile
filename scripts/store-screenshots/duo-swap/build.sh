#!/bin/bash
# Builds duo_swap for the arm64 simulator, ad-hoc signed with the display tool entitlements.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-$here/duo_swap}
xcrun -sdk iphonesimulator clang -arch arm64 -mios-simulator-version-min=26.0 -fobjc-arc -framework Foundation \
  "$here/duo_swap.m" -o "$out"
codesign -f -s - --entitlements "$here/entitlements.plist" "$out"
echo "$out"
