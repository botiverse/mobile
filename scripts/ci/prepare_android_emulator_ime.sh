#!/usr/bin/env bash
set -euo pipefail

receipt_path="${1:?usage: prepare_android_emulator_ime.sh <receipt-path>}"
adb_bin="${ADB_BIN:-adb}"
expected_ime="${ANDROID_EMULATOR_IME_COMPONENT:-com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME}"
sdk_package="${ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE:-unknown}"

mkdir -p "$(dirname "$receipt_path")"
: > "$receipt_path"

single_line() {
  tr -d '\r' | tr '\n' ',' | sed 's/,$//'
}

record() {
  printf '%s=%s\n' "$1" "$2" | tee -a "$receipt_path"
}

status_recorded=false
record_failure_on_exit() {
  local rc=$?
  trap - EXIT
  if [[ "$rc" -ne 0 && "$status_recorded" != "true" ]]; then
    printf 'ime_preparation_status=FAIL\n' | tee -a "$receipt_path"
  fi
  exit "$rc"
}
trap record_failure_on_exit EXIT

read_setting() {
  "$adb_bin" shell settings get "$1" "$2" | tr -d '\r'
}

read_power_field() {
  local field="$1"
  sed -n "s/^[[:space:]]*${field}=\\([^[:space:]]*\\).*$/\\1/p" | head -n 1
}

read_package_revision() {
  python3 - "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
revision = next(element for element in root.iter() if element.tag.endswith("revision"))
parts = []
for name in ("major", "minor", "micro"):
    value = next(
        (child.text for child in revision if child.tag.endswith(name)),
        None,
    )
    if value is not None:
        parts.append(value)
print(".".join(parts))
PY
}

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is required to identify the emulator environment" >&2
  exit 1
fi
if [[ "$sdk_package" == "unknown" ]]; then
  echo "ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE is required" >&2
  exit 1
fi

sdk_package_path="${sdk_package//;/\/}"
sdk_package_xml="$ANDROID_HOME/$sdk_package_path/package.xml"
emulator_package_xml="$ANDROID_HOME/emulator/package.xml"
for package_xml in "$sdk_package_xml" "$emulator_package_xml"; do
  if [[ ! -f "$package_xml" ]]; then
    echo "Required Android SDK package metadata is missing: $package_xml" >&2
    exit 1
  fi
done

# Keep these as ordinary assignments. `local x="$(...)"` masks a failed
# command substitution and would silently defeat `set -e` here.
sdk_revision="$(read_package_revision "$sdk_package_xml")"
emulator_version="$(read_package_revision "$emulator_package_xml")"
revision_pattern='^[0-9]+(\.[0-9]+){0,2}$'
if [[ ! "$sdk_revision" =~ $revision_pattern || ! "$emulator_version" =~ $revision_pattern ]]; then
  echo "Android SDK package revisions must contain one to three numeric parts" >&2
  exit 1
fi
sdk_package_xml_sha256="$(shasum -a 256 "$sdk_package_xml" | awk '{print $1}')"
emulator_package_xml_sha256="$(shasum -a 256 "$emulator_package_xml" | awk '{print $1}')"

installed_imes="$($adb_bin shell ime list -s -a | tr -d '\r')"
before_enabled="$(read_setting secure enabled_input_methods)"
before_default="$(read_setting secure default_input_method)"
before_show_with_hardware="$(read_setting secure show_ime_with_hard_keyboard)"
power_before="$($adb_bin shell dumpsys power | tr -d '\r')"
wakefulness_before="$(printf '%s\n' "$power_before" | read_power_field mWakefulness)"
stay_on_before="$(printf '%s\n' "$power_before" | read_power_field mStayOn)"
if [[ ! "$wakefulness_before" =~ ^(Awake|Asleep|Dreaming|Dozing)$ || ! "$stay_on_before" =~ ^(true|false)$ ]]; then
  echo "Android power state is missing or malformed before preparation" >&2
  exit 1
fi

record source_exact "${SOURCE_EXACT:-${GITHUB_SHA:-unknown}}"
record workflow_sha "${GITHUB_SHA:-unknown}"
record github_run_id "${GITHUB_RUN_ID:-unknown}"
record github_run_attempt "${GITHUB_RUN_ATTEMPT:-unknown}"
record runner_label "${RUNNER_LABEL:-unknown}"
record runner_name "${RUNNER_NAME:-unknown}"
record runner_os "${RUNNER_OS:-unknown}"
record runner_arch "${RUNNER_ARCH:-unknown}"
record sdk_package "$sdk_package"
record sdk_package_revision "$sdk_revision"
record sdk_package_xml_sha256 "$sdk_package_xml_sha256"
record emulator_version "$emulator_version"
record emulator_package_xml_sha256 "$emulator_package_xml_sha256"
record device_api "$($adb_bin shell getprop ro.build.version.sdk | tr -d '\r')"
record device_abi "$($adb_bin shell getprop ro.product.cpu.abi | tr -d '\r')"
record device_fingerprint "$($adb_bin shell getprop ro.build.fingerprint | tr -d '\r')"
record expected_ime "$expected_ime"
record installed_imes_before "$(printf '%s\n' "$installed_imes" | single_line)"
record enabled_imes_before "$(printf '%s' "$before_enabled" | single_line)"
record default_ime_before "$(printf '%s' "$before_default" | single_line)"
record show_ime_with_hard_keyboard_before "$(printf '%s' "$before_show_with_hardware" | single_line)"
record wakefulness_before "$wakefulness_before"
record stay_on_before "$stay_on_before"

if ! grep -Fxq "$expected_ime" <<< "$installed_imes"; then
  echo "Required emulator IME is not installed: $expected_ime" >&2
  exit 1
fi

"$adb_bin" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" shell svc power stayon true

power_after=""
wakefulness_after=""
stay_on_after=""
for _ in $(seq 1 50); do
  power_after="$($adb_bin shell dumpsys power | tr -d '\r')"
  wakefulness_after="$(printf '%s\n' "$power_after" | read_power_field mWakefulness)"
  stay_on_after="$(printf '%s\n' "$power_after" | read_power_field mStayOn)"
  if [[ "$wakefulness_after" == "Awake" && "$stay_on_after" == "true" ]]; then
    break
  fi
  sleep 0.1
done

record wakefulness_after "$wakefulness_after"
record stay_on_after "$stay_on_after"

if [[ "$wakefulness_after" != "Awake" || "$stay_on_after" != "true" ]]; then
  echo "Android emulator did not become and remain awake: wakefulness=$wakefulness_after stay_on=$stay_on_after" >&2
  exit 1
fi

"$adb_bin" shell ime enable "$expected_ime"
"$adb_bin" shell ime set "$expected_ime"
"$adb_bin" shell settings put secure show_ime_with_hard_keyboard 1

after_enabled="$(read_setting secure enabled_input_methods)"
after_default="$(read_setting secure default_input_method)"
after_show_with_hardware="$(read_setting secure show_ime_with_hard_keyboard)"

record enabled_imes_after "$(printf '%s' "$after_enabled" | single_line)"
record default_ime_after "$(printf '%s' "$after_default" | single_line)"
record show_ime_with_hard_keyboard_after "$(printf '%s' "$after_show_with_hardware" | single_line)"

case ":$after_enabled:" in
  *":$expected_ime:"*) ;;
  *)
    echo "Required emulator IME was not enabled: expected=$expected_ime actual=$after_enabled" >&2
    exit 1
    ;;
esac

if [[ "$after_default" != "$expected_ime" ]]; then
  echo "Required emulator IME was not selected: expected=$expected_ime actual=$after_default" >&2
  exit 1
fi

if [[ "$after_show_with_hardware" != "1" ]]; then
  echo "Software IME visibility setting did not take effect: actual=$after_show_with_hardware" >&2
  exit 1
fi

record ime_preparation_status PASS
status_recorded=true
