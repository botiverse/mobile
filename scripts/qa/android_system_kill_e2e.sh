#!/usr/bin/env bash
# Android <-> Hands joint test for system-kill reports (AndroidSystemKillReporter, botiverse/hands#955).
# Runs on an emulator (android-system-kill-e2e.yml). Every case kills exactly one process and
# relaunches so the next process uploads; logcat, exit-info, screenshots and a UTC timeline go to OUT.
#
#   A  open >=35 s, tap, HOME, wait 95 s, kill -9          -> importance 400/125, recent false
#   B  tap, kill -9 3 s later while on screen               -> importance 100, recent true
#   C  tap, HOME, kill -9 5 s later                         -> importance >=125, recent true
#   D  am crash                                             -> HandsCrash ticket only, no SystemKill
#   E  cold start, kill -9 ~1 s later (best effort)         -> recent omitted, source unknown
#
# usage: android_system_kill_e2e.sh <apk> <package> <out-dir>
set -uo pipefail

APK="$1"
PKG="$2"
OUT="$3"
UPLOAD_WAIT="${UPLOAD_WAIT:-45}"
mkdir -p "$OUT"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ) $*" | tee -a "$OUT/timeline.txt"; }
app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
launch() { adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
# A blank spot on the left edge: counts as a touch (Activity.onUserInteraction) without opening anything.
tap() { adb shell input tap 8 900; }
home() { adb shell input keyevent KEYCODE_HOME; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
exit_info() { adb shell dumpsys activity exit-info "$PKG" > "$OUT/exit-info-$1.txt" 2>&1 || true; }

kill9() {
  local tag="$1" pid
  pid="$(app_pid)"
  if [ -z "$pid" ]; then log "$tag ERROR no process to kill"; return 1; fi
  adb shell "dumpsys activity processes" > "$OUT/processes-$tag-before-kill.txt" 2>&1 || true
  log "$tag kill -9 pid=$pid"
  adb shell run-as "$PKG" kill -9 "$pid" 2>/dev/null || true
  sleep 1
  if [ "$(app_pid)" = "$pid" ]; then
    log "$tag run-as kill failed; retrying as root"
    adb root >/dev/null 2>&1; sleep 3
    adb shell kill -9 "$pid"
    sleep 1
  fi
  [ "$(app_pid)" != "$pid" ] && log "$tag pid $pid gone" || log "$tag ERROR pid $pid still alive"
}

relaunch_and_upload() {
  local tag="$1"
  log "$tag relaunch (upload)"
  launch
  sleep "$UPLOAD_WAIT"
  log "$tag relaunched pid=$(app_pid)"
  shot "$tag-after-relaunch"
  exit_info "$tag"
}

adb wait-for-device
adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
adb shell dumpsys package "$PKG" | grep -E "versionCode|versionName|lastUpdateTime|firstInstallTime" > "$OUT/package.txt"
adb shell getprop ro.build.fingerprint > "$OUT/device.txt"
adb logcat -c
adb logcat -v threadtime -v UTC > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

log "baseline launch"
launch
sleep 40
log "baseline pid=$(app_pid)"
shot baseline

# A: the baseline process has been open >=35 s.
log "A start"
tap
sleep 1
log "A HOME"
home
sleep 95
kill9 A
relaunch_and_upload A
log "A end"

# B: the relaunched process is on screen.
log "B start"
tap
sleep 3
shot B-before-kill
kill9 B
relaunch_and_upload B
log "B end"

# C
log "C start"
tap
sleep 1
log "C HOME"
home
sleep 5
kill9 C
relaunch_and_upload C
log "C end"

# D
log "D start pid=$(app_pid)"
adb shell am crash "$PKG" || log "D am crash failed"
sleep 3
log "D pid after crash=$(app_pid)"
relaunch_and_upload D
log "D end"

# E: end the current process the way the reporter skips (user requested), then a fresh cold start.
log "E force-stop (user requested, skipped by the reporter)"
adb shell am force-stop "$PKG"
sleep 3
log "E start (cold start)"
launch
sleep 1
kill9 E
relaunch_and_upload E
log "E end"

adb shell run-as "$PKG" ls shared_prefs > "$OUT/shared_prefs-list.txt" 2>&1 || true
mkdir -p "$OUT/shared_prefs"
for f in $(tr -d '\r' < "$OUT/shared_prefs-list.txt" | grep '\.xml$'); do
  case "$f" in
    *process-exit*|*hands*|*Hands*|*telemetry*|*memory*)
      adb shell run-as "$PKG" cat "shared_prefs/$f" > "$OUT/shared_prefs/$f" 2>&1 || true ;;
  esac
done
exit_info final
kill "$LOGCAT_PID" 2>/dev/null || true
grep -E "SlockAndroidProcessExit|HandsCrash|HandsFeedback|Hands" "$OUT/logcat.txt" > "$OUT/logcat-hands.txt" || true
log "done"
