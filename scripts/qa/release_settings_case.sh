#!/usr/bin/env bash
# The release settings case, run by scripts/qa/scenarios/release_settings_case.mjs inside
# release_auto_login.mjs after Testbed's "matched" (same app process, before any cleanup):
# recorder, agent, and every read that needs that process (pids, logcat, activity, gfxinfo).
# Recording is bound to this invocation (its own device directory and loop pid = the start
# receipt, confirmed before the agent runs), then stopped and JOINED (loop and screenrecord both
# gone), every segment pulled and checked, and the directory removed, all before cleanup.
# qa-evidence/recording.json says complete only then; otherwise the case exits 4 and the run is
# not vouched for (release_settings_terminal.mjs). The workflow's later lines skip
# all of this in the release lane and only secure and report.
set -u
PKG="$(cat qa-app-package.txt 2>/dev/null || echo build.raft.app)"
RUN="${GITHUB_RUN_ID:-local}:${GITHUB_RUN_ATTEMPT:-1}"
REC_DIR="/sdcard/qa-rec-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$$-${RANDOM}"
rc=0
mkdir -p qa-evidence
rm -f qa-evidence/recording.json qa-evidence/qa-rec-*.mp4
# An unavailable or unconfirmed recording is reported as such and fails the case (exit 4):
# release_settings_terminal.mjs then withholds the run from both exits.
recording_fail() {
  echo "::error::release settings case: recording $1; its media is not evidence" >&2
  adb shell "pkill -KILL screenrecord; rm -rf $REC_DIR" >/dev/null 2>&1 || true
  rm -f qa-evidence/qa-rec-*.mp4
  printf '{"status":"%s","segments":0,"run":"%s"}\n' "$1" "$RUN" > qa-evidence/recording.json
  echo "$rc" > qa-evidence/agent-rc.txt
  exit 4
}
# The release case has its own goal; never the workflow's default (it opens a channel).
if [ -z "${QA_GOAL:-}" ]; then echo "::error::release settings case: no goal (QA_GOAL) supplied" >&2; exit 3; fi
adb logcat -c || true
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
date +%s%3N > qa-evidence/perf-started.txt
# This invocation's own recording directory and loop: the start receipt is the loop's pid file
# in it plus a running screenrecord, confirmed before the agent starts.
adb shell "rm -rf $REC_DIR; mkdir -p $REC_DIR" || recording_fail not_started
adb shell "nohup sh -c 'echo \$\$ > $REC_DIR/loop.pid; i=1; while [ \$i -le 8 ]; do screenrecord --size 540x1200 --bit-rate 1000000 --time-limit 180 $REC_DIR/qa-rec-\$(printf %02d \$i).mp4; i=\$((i+1)); done' >/dev/null 2>&1 &"
started=no
for _ in $(seq 1 10); do
  if [ "$(adb shell "[ -s $REC_DIR/loop.pid ] && kill -0 \$(cat $REC_DIR/loop.pid) 2>/dev/null && pidof screenrecord >/dev/null && echo yes" | tr -d '\r')" = yes ]; then started=yes; break; fi
  sleep 1
done
[ "$started" = yes ] || recording_fail not_started
export MINIMAX_API_KEY="${PI_API_KEY:-}"
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
adb shell "kill \$(cat $REC_DIR/loop.pid)" 2>/dev/null || true
adb shell pkill -INT screenrecord 2>/dev/null || true
stopped=no
for _ in $(seq 1 30); do
  state=$(adb shell "if kill -0 \$(cat $REC_DIR/loop.pid) 2>/dev/null; then echo loop; elif pidof screenrecord >/dev/null 2>&1; then echo rec; else echo none; fi" | tr -d '\r')
  if [ "$state" = none ]; then stopped=yes; break; fi
  sleep 1
done
[ "$stopped" = yes ] || recording_fail unconfirmed_stop
# Collect: every segment of this directory must arrive, non-empty; at least one must exist.
mapfile -t segments < <(adb shell "ls $REC_DIR/qa-rec-*.mp4 2>/dev/null" | tr -d '\r' | grep -E '/qa-rec-[0-9]{2}\.mp4$')
[ "${#segments[@]}" -ge 1 ] || recording_fail no_segments
for f in "${segments[@]}"; do
  adb pull "$f" qa-evidence/ >/dev/null 2>&1 || recording_fail transfer_failed
  [ -s "qa-evidence/$(basename "$f")" ] || recording_fail transfer_failed
done
adb shell "rm -rf $REC_DIR" && [ -z "$(adb shell "ls -d $REC_DIR 2>/dev/null" | tr -d '\r')" ] || recording_fail cleanup_failed
printf '{"status":"complete","segments":%d,"run":"%s"}\n' "${#segments[@]}" "$RUN" > qa-evidence/recording.json
echo "$rc" > qa-evidence/agent-rc.txt
echo "release settings case: agent exit code $rc; recorder stopped"
