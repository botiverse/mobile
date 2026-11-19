#!/usr/bin/env bash
# The release settings case, run by scripts/qa/scenarios/release_settings_case.mjs inside
# release_auto_login.mjs after Testbed's "matched" (same app process, before any cleanup):
# recorder, agent, and every read that needs that process (pids, logcat, activity, gfxinfo).
# The recorder loop is stopped and JOINED (loop shell and screenrecord both gone) and its
# finalized segments pulled here, before cleanup; if that cannot be confirmed the case exits 4
# and the run is not vouched for (release_settings_terminal.mjs). The workflow's later lines skip
# all of this in the release lane and only secure and report.
set -u
PKG="$(cat qa-app-package.txt 2>/dev/null || echo build.raft.app)"
LOOP_PID=/data/local/tmp/qa-rec-loop.pid
mkdir -p qa-evidence
# The release case has its own goal; never the workflow's default (it opens a channel).
if [ -z "${QA_GOAL:-}" ]; then echo "::error::release settings case: no goal (QA_GOAL) supplied" >&2; exit 3; fi
adb logcat -c || true
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
date +%s%3N > qa-evidence/perf-started.txt
adb shell "rm -f /sdcard/qa-rec-*.mp4 $LOOP_PID"
adb shell "nohup sh -c 'echo \$\$ > $LOOP_PID; i=1; while [ \$i -le 8 ]; do screenrecord --size 540x1200 --bit-rate 1000000 --time-limit 180 /sdcard/qa-rec-\$(printf %02d \$i).mp4; i=\$((i+1)); done' >/dev/null 2>&1 &"
export MINIMAX_API_KEY="${PI_API_KEY:-}"
rc=0
node scripts/qa/acceptance_agent.mjs --goal "$QA_GOAL" --max-turns "$QA_MAX_TURNS" --max-minutes "$QA_MAX_MINUTES" \
  --no-progress-turns "$QA_NO_PROGRESS_TURNS" --package "$PKG" --out qa-evidence || rc=$?
adb shell pidof "$PKG" >> qa-evidence/app-pids.txt 2>/dev/null || true
adb logcat -d -v threadtime > qa-evidence/logcat-full.txt 2>&1 || true
node scripts/qa/scope_logcat.mjs --input qa-evidence/logcat-full.txt --output qa-evidence/logcat.txt --verdict qa-evidence/verdict.json \
  --pids qa-evidence/app-pids.txt --package "$PKG" --generation "${QA_CAPTURE_SCOPE_GENERATION:-${GITHUB_RUN_ID:-local}:${GITHUB_RUN_ATTEMPT:-1}}" || true
adb shell dumpsys activity top > qa-evidence/activity-top.txt 2>&1 || true
adb shell dumpsys gfxinfo "$PKG" > qa-evidence/gfxinfo.txt 2>&1 || true

# Stop the loop first (so it cannot start the next segment), then the running segment, then wait
# until both are gone. Only then are the segments final.
adb shell "[ -s $LOOP_PID ] && kill \$(cat $LOOP_PID)" 2>/dev/null || true
adb shell pkill -INT screenrecord 2>/dev/null || true
stopped=no
for _ in $(seq 1 30); do
  state=$(adb shell "if [ -s $LOOP_PID ] && kill -0 \$(cat $LOOP_PID) 2>/dev/null; then echo loop; elif pidof screenrecord >/dev/null 2>&1; then echo rec; else echo none; fi" | tr -d '\r')
  if [ "$state" = none ]; then stopped=yes; break; fi
  sleep 1
done
if [ "$stopped" != yes ]; then
  echo "::error::release settings case: the screen recorder did not stop; its media is not evidence" >&2
  adb shell "pkill -KILL screenrecord; rm -f /sdcard/qa-rec-*.mp4" 2>/dev/null || true
  echo "$rc" > qa-evidence/agent-rc.txt
  exit 4
fi
adb shell "ls /sdcard/qa-rec-*.mp4" 2>/dev/null | tr -d '\r' | while read -r f; do [ -n "$f" ] && adb pull "$f" qa-evidence/ >/dev/null; done
adb shell "rm -f /sdcard/qa-rec-*.mp4 $LOOP_PID" || true
echo "$rc" > qa-evidence/agent-rc.txt
echo "release settings case: agent exit code $rc; recorder stopped"
