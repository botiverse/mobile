#!/usr/bin/env bash
# Record the Xcode and iOS SDK this job builds with, in the log and the job summary.
#
# The SDK decides how iOS lays the app out on iPhone Duo (developer.apple.com/iphone-duo/prepare):
#   iOS 26 SDK or earlier -> letterboxed and centred on the inner display;
#   iOS 27.0 SDK          -> resizes, still avoids the right-edge status bar;
#   iOS 27.1 SDK or later -> full display, bars vertical below the status bar.
# Run after scripts/ci/select_ios_xcode.sh (it exports DEVELOPER_DIR). RAFT_DEFAULT_XCODE_APP is
# the workflow's pinned default; a different XCODE_APP_PATH is flagged as an opt-in lane.
set -euo pipefail

xcode_version="$(xcodebuild -version)"
device_sdk="$(xcrun --sdk iphoneos --show-sdk-version)"
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-version)"
selected_app="${XCODE_APP_PATH:-}"
default_app="${RAFT_DEFAULT_XCODE_APP:-}"

case "$device_sdk" in
  ''|*[!0-9.]*) echo "::error::Unexpected iPhoneOS SDK version: $device_sdk" >&2; exit 1 ;;
esac
major="${device_sdk%%.*}"
rest="${device_sdk#"$major"}"
rest="${rest#.}"
minor="${rest%%.*}"
minor="${minor:-0}"
if [ "$major" -lt 27 ]; then
  tier="iOS 26 SDK or earlier: letterboxed on the iPhone Duo inner display"
elif [ "$major" -eq 27 ] && [ "$minor" -lt 1 ]; then
  tier="iOS 27.0 SDK: resizes on iPhone Duo, avoids the right-edge status bar"
else
  tier="iOS 27.1 SDK or later: edge-to-edge on iPhone Duo, vertical bars"
fi

lane="default"
if [ -n "$default_app" ] && [ -n "$selected_app" ] && [ "$selected_app" != "$default_app" ]; then
  lane="opt-in (default is ${default_app##*/})"
fi

echo "$xcode_version"
echo "iPhoneOS SDK $device_sdk, iPhoneSimulator SDK $simulator_sdk"
echo "Raft iOS SDK tier: $tier"
echo "Xcode lane: $lane"
if [ "$lane" != "default" ]; then
  echo "::warning::Built with ${selected_app##*/} (iPhoneOS SDK $device_sdk), not the pinned ${default_app##*/}. Not for release."
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### iOS toolchain"
    echo ""
    echo '```'
    echo "$xcode_version"
    echo '```'
    echo ""
    echo "- iPhoneOS SDK: \`$device_sdk\`"
    echo "- iPhoneSimulator SDK: \`$simulator_sdk\`"
    echo "- Raft iOS SDK tier: $tier"
    echo "- Xcode lane: $lane"
  } >> "$GITHUB_STEP_SUMMARY"
fi
