#!/usr/bin/env bash
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0
expected_ime="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

check_exit() {
  local description="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" -ne "$expected" ]]; then
    echo "FAIL: $description (expected exit $expected, got $actual)" >&2
    fail=1
  else
    echo "ok: $description"
  fi
}

check_receipt() {
  local description="$1"
  local needle="$2"
  local receipt="$3"
  if grep -Fqx "$needle" "$receipt"; then
    echo "ok: $description"
  else
    echo "FAIL: $description (missing $needle)" >&2
    fail=1
  fi
}

mkdir -p "$tmp/bin" "$tmp/sdk/emulator" "$tmp/sdk/system-images/android-34/google_apis/x86_64"

cat > "$tmp/bin/adb" <<'FAKEADB'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_ADB_STATE:?}"
expected="com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"

if [[ "$1" != "shell" ]]; then
  exit 2
fi
shift

if [[ "$1" == "getprop" ]]; then
  case "$2" in
    ro.build.version.sdk) echo 34 ;;
    ro.product.cpu.abi) echo x86_64 ;;
    ro.build.fingerprint) echo google/sdk_gphone_x86_64/emu64xa:14/test/fingerprint:userdebug/dev-keys ;;
    *) exit 2 ;;
  esac
elif [[ "$1" == "ime" && "$2" == "list" ]]; then
  printf '%s\n' "${FAKE_INSTALLED_IMES:-$expected}"
elif [[ "$1" == "ime" && "$2" == "enable" ]]; then
  [[ "${FAKE_ENABLE_FAIL:-0}" == "0" ]] || exit 1
  if [[ "${FAKE_ENABLE_NO_EFFECT:-0}" == "0" ]]; then
    current="$(cat "$state/enabled")"
    if [[ -n "$current" ]]; then
      printf '%s:%s' "$current" "$3" > "$state/enabled"
    else
      printf '%s' "$3" > "$state/enabled"
    fi
  fi
elif [[ "$1" == "ime" && "$2" == "set" ]]; then
  if [[ "${FAKE_SET_NO_EFFECT:-0}" == "0" ]]; then
    printf '%s' "$3" > "$state/default"
  fi
elif [[ "$1" == "settings" && "$2" == "get" ]]; then
  case "$4" in
    enabled_input_methods) cat "$state/enabled" ;;
    default_input_method) cat "$state/default" ;;
    show_ime_with_hard_keyboard) cat "$state/show" ;;
    *) exit 2 ;;
  esac
elif [[ "$1" == "settings" && "$2" == "put" ]]; then
  if [[ "${FAKE_SHOW_NO_EFFECT:-0}" == "0" ]]; then
    printf '%s' "$5" > "$state/show"
  fi
elif [[ "$1" == "dumpsys" && "$2" == "power" ]]; then
  if [[ "${FAKE_POWER_MISSING:-0}" == "1" ]]; then
    echo "Power Manager State: unavailable"
  elif [[ "${FAKE_POWER_MALFORMED:-0}" == "1" ]]; then
    echo "  mWakefulness=Unknown"
    echo "  mStayOn=maybe"
  else
    printf '  mWakefulness=%s\n' "$(cat "$state/wakefulness")"
    printf '  mStayOn=%s\n' "$(cat "$state/stay-on")"
  fi
elif [[ "$1" == "input" && "$2" == "keyevent" && "$3" == "KEYCODE_WAKEUP" ]]; then
  if [[ "${FAKE_WAKE_NO_EFFECT:-0}" == "0" ]]; then
    printf 'Awake' > "$state/wakefulness"
  fi
elif [[ "$1" == "svc" && "$2" == "power" && "$3" == "stayon" && "$4" == "true" ]]; then
  if [[ "${FAKE_STAY_ON_NO_EFFECT:-0}" == "0" ]]; then
    printf 'true' > "$state/stay-on"
  fi
else
  exit 2
fi
FAKEADB
chmod +x "$tmp/bin/adb"

cat > "$tmp/sdk/emulator/package.xml" <<'EMULATORPACKAGEXML'
<repository>
  <localPackage path="emulator">
    <revision><major>37</major><minor>1</minor><micro>11</micro></revision>
  </localPackage>
</repository>
EMULATORPACKAGEXML

cat > "$tmp/sdk/system-images/android-34/google_apis/x86_64/package.xml" <<'PACKAGEXML'
<repository>
  <localPackage path="system-images;android-34;google_apis;x86_64">
    <revision><major>14</major></revision>
  </localPackage>
</repository>
PACKAGEXML

run_arm() {
  local name="$1"
  local state="$tmp/state-$name"
  mkdir -p "$state"
  printf '%s' "${FAKE_ENABLED_BEFORE:-}" > "$state/enabled"
  : > "$state/default"
  printf '0' > "$state/show"
  printf '%s' "${FAKE_WAKEFULNESS_BEFORE:-Asleep}" > "$state/wakefulness"
  printf '%s' "${FAKE_STAY_ON_BEFORE:-false}" > "$state/stay-on"
  FAKE_ADB_STATE="$state" \
    ANDROID_HOME="$tmp/sdk" \
    ANDROID_EMULATOR_SYSTEM_IMAGE_PACKAGE='system-images;android-34;google_apis;x86_64' \
    SOURCE_EXACT='source-head' \
    GITHUB_SHA='workflow-merge' \
    RUNNER_LABEL='blacksmith-4vcpu-ubuntu-2404' \
    ADB_BIN="$tmp/bin/adb" \
    "$here/prepare_android_emulator_ime.sh" "$tmp/$name.receipt"
}

FAKE_ENABLED_BEFORE='com.example/.VoiceIme' run_arm success
check_exit "existing IME state is repaired" 0 $?
check_receipt "source head is recorded separately" "source_exact=source-head" "$tmp/success.receipt"
check_receipt "workflow merge SHA is recorded separately" "workflow_sha=workflow-merge" "$tmp/success.receipt"
check_receipt "existing enabled IME is preserved" "enabled_imes_after=com.example/.VoiceIme:$expected_ime" "$tmp/success.receipt"
check_receipt "selected IME is recorded" "default_ime_after=$expected_ime" "$tmp/success.receipt"
check_receipt "visibility setting is recorded" "show_ime_with_hard_keyboard_after=1" "$tmp/success.receipt"
check_receipt "sleeping emulator state is recorded" "wakefulness_before=Asleep" "$tmp/success.receipt"
check_receipt "wake command takes effect" "wakefulness_after=Awake" "$tmp/success.receipt"
check_receipt "stay-on command takes effect" "stay_on_after=true" "$tmp/success.receipt"
check_receipt "system-image revision is recorded" "sdk_package_revision=14" "$tmp/success.receipt"
check_receipt "emulator package revision is recorded" "emulator_version=37.1.11" "$tmp/success.receipt"
check_receipt "preparation PASS is recorded" "ime_preparation_status=PASS" "$tmp/success.receipt"

FAKE_INSTALLED_IMES='com.example/.VoiceIme' run_arm missing-ime >/dev/null 2>&1
check_exit "missing required software IME fails closed" 1 $?
check_receipt "missing IME records failure" "ime_preparation_status=FAIL" "$tmp/missing-ime.receipt"

FAKE_ENABLE_NO_EFFECT=1 run_arm enable-no-effect >/dev/null 2>&1
check_exit "IME enable that does not take effect fails closed" 1 $?
check_receipt "ineffective IME enable records failure" "ime_preparation_status=FAIL" "$tmp/enable-no-effect.receipt"

FAKE_SET_NO_EFFECT=1 run_arm set-no-effect >/dev/null 2>&1
check_exit "IME selection that does not take effect fails closed" 1 $?
check_receipt "ineffective IME selection records failure" "ime_preparation_status=FAIL" "$tmp/set-no-effect.receipt"

FAKE_SHOW_NO_EFFECT=1 run_arm show-no-effect >/dev/null 2>&1
check_exit "software-keyboard visibility that does not take effect fails closed" 1 $?
check_receipt "ineffective visibility setting records failure" "ime_preparation_status=FAIL" "$tmp/show-no-effect.receipt"

FAKE_POWER_MALFORMED=1 run_arm malformed-power-state >/dev/null 2>&1
check_exit "malformed emulator power state fails closed" 1 $?
check_receipt "malformed power state records failure" "ime_preparation_status=FAIL" "$tmp/malformed-power-state.receipt"

FAKE_POWER_MISSING=1 run_arm missing-power-state >/dev/null 2>&1
check_exit "missing emulator power state fails closed" 1 $?
check_receipt "missing power state records failure" "ime_preparation_status=FAIL" "$tmp/missing-power-state.receipt"

FAKE_WAKE_NO_EFFECT=1 run_arm wake-no-effect >/dev/null 2>&1
check_exit "wake command that does not take effect fails closed" 1 $?
check_receipt "ineffective wake command records failure" "ime_preparation_status=FAIL" "$tmp/wake-no-effect.receipt"

FAKE_STAY_ON_NO_EFFECT=1 run_arm stay-on-no-effect >/dev/null 2>&1
check_exit "stay-on command that does not take effect fails closed" 1 $?
check_receipt "ineffective stay-on command records failure" "ime_preparation_status=FAIL" "$tmp/stay-on-no-effect.receipt"

printf '%s\n' '<repository><localPackage path="emulator"/></repository>' > "$tmp/sdk/emulator/package.xml"
run_arm malformed-emulator-metadata >/dev/null 2>&1
check_exit "malformed emulator package metadata fails closed" 1 $?
check_receipt "malformed emulator metadata records failure" "ime_preparation_status=FAIL" "$tmp/malformed-emulator-metadata.receipt"

printf '%s\n' '<repository><localPackage path="emulator"><revision/></localPackage></repository>' > "$tmp/sdk/emulator/package.xml"
run_arm empty-emulator-revision >/dev/null 2>&1
check_exit "empty emulator package revision fails closed" 1 $?
check_receipt "empty emulator revision records failure" "ime_preparation_status=FAIL" "$tmp/empty-emulator-revision.receipt"

if [[ "$fail" -ne 0 ]]; then
  echo "Android emulator IME preparation test FAILED" >&2
  exit 1
fi
echo "Android emulator IME preparation test PASSED"
