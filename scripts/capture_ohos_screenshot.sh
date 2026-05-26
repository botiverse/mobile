#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OHOS_DIR="$ROOT_DIR/compose/ohosApp"
SDK_HOME="${DEVECO_STUDIO_HOME:-/Applications/DevEco-Studio.app/Contents}"
DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-$SDK_HOME/sdk}"
HDC_BIN="${HDC_BIN:-$DEVECO_SDK_HOME/default/openharmony/toolchains/hdc}"
HVIGORW="${HVIGORW:-$SDK_HOME/tools/hvigor/bin/hvigorw.js}"
NODE_BIN="${NODE_BIN:-$SDK_HOME/tools/node/bin/node}"
OHPM_BIN="${OHPM_BIN:-$SDK_HOME/tools/ohpm/bin/ohpm}"

BUNDLE="ai.slock.compose"
MODULE="entry"
ABILITY="EntryAbility"
TARGET_ID=""
CASE_NAME="manual"
OUTPUT_DIR="$ROOT_DIR/artifacts/ohos-screenshots"
HAP_PATH="$OHOS_DIR/entry/build/default/outputs/default/entry-default-signed.hap"
BUILD_HAP=0
INSTALL_HAP=0
LAUNCH_APP=0
WAIT_SECONDS=2

usage() {
  cat <<'USAGE'
Usage: scripts/capture_ohos_screenshot.sh [options]

Build/install/launch Slock OHOS if requested, then capture the current device
screen through hdc and pull a PNG into artifacts/ohos-screenshots/.

Options:
  --target <id>       hdc target id. Defaults to the first connected target.
  --case <name>       Screenshot case label, e.g. home-tabbar. Default: manual.
  --output-dir <dir>  Local output directory. Default: artifacts/ohos-screenshots.
  --hap <path>        HAP path to install. Default: entry-default-signed.hap.
  --build            Run ohpm install, hvigor sync, and assembleHap first.
  --install          Install the HAP before capture.
  --launch           Start ai.slock.compose/entry EntryAbility before capture.
  --wait <seconds>   Wait after launch before capture. Default: 2.
  -h, --help         Show this help.

Examples:
  scripts/capture_ohos_screenshot.sh --case home-tabbar --launch
  scripts/capture_ohos_screenshot.sh --build --install --launch --case home-tabbar
  scripts/capture_ohos_screenshot.sh --target 127.0.0.1:5555 --case settings-input
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --target)
      TARGET_ID="${2:?missing --target value}"
      shift 2
      ;;
    --case)
      CASE_NAME="${2:?missing --case value}"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="${2:?missing --output-dir value}"
      shift 2
      ;;
    --hap)
      HAP_PATH="${2:?missing --hap value}"
      shift 2
      ;;
    --build)
      BUILD_HAP=1
      shift
      ;;
    --install)
      INSTALL_HAP=1
      shift
      ;;
    --launch)
      LAUNCH_APP=1
      shift
      ;;
    --wait)
      WAIT_SECONDS="${2:?missing --wait value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

if [ ! -x "$HDC_BIN" ]; then
  echo "error: hdc not found or not executable: $HDC_BIN" >&2
  exit 127
fi

export DEVECO_SDK_HOME
export PATH="$DEVECO_SDK_HOME:$SDK_HOME/jbr/Contents/Home/bin:$SDK_HOME/tools/node/bin:$SDK_HOME/tools/ohpm/bin:$SDK_HOME/tools/hvigor/bin:$DEVECO_SDK_HOME/default/openharmony/toolchains:$PATH"

if [ "$BUILD_HAP" -eq 1 ]; then
  cd "$OHOS_DIR"
  "$OHPM_BIN" install --all
  "$NODE_BIN" "$HVIGORW" --sync -p product=default --analyze=normal --parallel
  "$NODE_BIN" "$HVIGORW" --mode module -p module=entry@default -p product=default -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
  cd "$ROOT_DIR"
fi

TARGETS="$("$HDC_BIN" list targets | tr -d '\r')"
echo "hdc list targets:"
printf '%s\n' "$TARGETS"

if [ -z "$TARGET_ID" ]; then
  TARGET_ID="$(printf '%s\n' "$TARGETS" | awk 'NF && $0 !~ /^\[Empty\]$/ { print $1; exit }')"
fi

if [ -z "$TARGET_ID" ]; then
  echo "error: no OHOS target connected. Start an emulator/device, then retry." >&2
  exit 2
fi

HDC_TARGET=("$HDC_BIN" -t "$TARGET_ID")
echo "selected target: $TARGET_ID"
echo "bundle/module/ability: $BUNDLE / $MODULE / $ABILITY"

device_field() {
  local key="$1"
  "${HDC_TARGET[@]}" shell "param get $key" 2>/dev/null | tr -d '\r' || true
}

MODEL="$(device_field const.product.model)"
DEVICE_TYPE="$(device_field const.product.devicetype)"
API_VERSION="$(device_field const.ohos.apiversion)"
SOFTWARE_VERSION="$(device_field const.product.software.version)"
echo "device: model=${MODEL:-unknown} type=${DEVICE_TYPE:-unknown} api=${API_VERSION:-unknown} version=${SOFTWARE_VERSION:-unknown}"

if [ "$INSTALL_HAP" -eq 1 ]; then
  if [ ! -f "$HAP_PATH" ]; then
    echo "error: HAP not found: $HAP_PATH" >&2
    echo "hint: pass --build or --hap <path>" >&2
    exit 3
  fi
  echo "install hap: $HAP_PATH"
  "${HDC_TARGET[@]}" shell "aa force-stop $BUNDLE" >/dev/null 2>&1 || true
  "${HDC_TARGET[@]}" install "$HAP_PATH"
fi

if [ "$LAUNCH_APP" -eq 1 ]; then
  echo "launch command: aa start -a $ABILITY -b $BUNDLE"
  "${HDC_TARGET[@]}" shell "aa start -a $ABILITY -b $BUNDLE"
  sleep "$WAIT_SECONDS"
fi

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
SAFE_CASE="$(printf '%s' "$CASE_NAME" | tr -cs 'A-Za-z0-9._-' '-')"
mkdir -p "$OUTPUT_DIR"
LOCAL_PNG="$OUTPUT_DIR/${TIMESTAMP}-${SAFE_CASE}.png"
REMOTE_PNG="/data/local/tmp/slock-${TIMESTAMP}-${SAFE_CASE}.png"

SCREENSHOT_COMMAND="snapshot_display -f $REMOTE_PNG"
echo "screenshot command: hdc -t $TARGET_ID shell \"$SCREENSHOT_COMMAND\""
if ! "${HDC_TARGET[@]}" shell "$SCREENSHOT_COMMAND"; then
  SCREENSHOT_COMMAND="snapshot_display -i 0 -f $REMOTE_PNG"
  echo "primary screenshot command failed; retry: hdc -t $TARGET_ID shell \"$SCREENSHOT_COMMAND\""
  "${HDC_TARGET[@]}" shell "$SCREENSHOT_COMMAND"
fi

"${HDC_TARGET[@]}" file recv "$REMOTE_PNG" "$LOCAL_PNG"
"${HDC_TARGET[@]}" shell "rm -f $REMOTE_PNG" >/dev/null 2>&1 || true

if [ ! -s "$LOCAL_PNG" ]; then
  echo "error: screenshot pull did not produce a PNG: $LOCAL_PNG" >&2
  exit 4
fi

echo "png path: $LOCAL_PNG"
