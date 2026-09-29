#!/usr/bin/env bash
# Explicit bootstrap only: capture jobs never install host dependencies.
set -euo pipefail
: "${DEVELOPER_DIR:?Select full Xcode first}"
export HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_UPGRADE=1
if ! command -v brew >/dev/null && [ -x /opt/homebrew/bin/brew ]; then
  export PATH="/opt/homebrew/bin:$PATH"
  echo /opt/homebrew/bin >> "${GITHUB_PATH:?}"
fi
for dependency in python3 pod; do
  if ! command -v "$dependency" >/dev/null; then
    command -v brew >/dev/null || { echo '::error::Install Homebrew for the runner account before bootstrapping missing dependencies' >&2; exit 1; }
    case "$dependency" in
      python3) brew install python ;;
      pod) brew install cocoapods ;;
    esac
  fi
done
python3 --version
pod --version
if ! xcodebuild -checkFirstLaunchStatus; then
  # Only Apple's component installation needs elevation. Never prompt for a
  # password, accept a license, or change the machine-wide developer directory.
  sudo -n env DEVELOPER_DIR="$DEVELOPER_DIR" /usr/bin/xcodebuild -runFirstLaunch
fi
xcrun simctl list runtimes -j > "$RUNNER_TEMP/bootstrap-ios-runtimes.json"
available=$(python3 - "$RUNNER_TEMP/bootstrap-ios-runtimes.json" <<'PY'
import json, sys
runtimes = json.load(open(sys.argv[1]))['runtimes']
print(sum(bool(r['isAvailable']) and (r.get('platform') == 'iOS' or r['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-')) for r in runtimes))
PY
)
if [ "$available" -eq 0 ]; then
  xcodebuild -downloadPlatform iOS
fi
xcrun simctl list runtimes -j
