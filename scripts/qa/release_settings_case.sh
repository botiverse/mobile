#!/usr/bin/env bash
# The release settings case, run by scripts/qa/scenarios/release_settings_case.mjs inside
# release_auto_login.mjs after Testbed's "matched" (same app process, before any cleanup):
# recorder, agent, and every read that needs that process (pids, logcat, activity, gfxinfo).
# The workflow's later lines skip these in the release lane and only pull, secure and report.
set -u
PKG="$(cat qa-app-package.txt 2>/dev/null || echo build.raft.app)"
mkdir -p qa-evidence
adb logcat -c || true
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
date +%s%3N > qa-evidence/perf-started.txt
adb shell "nohup sh -c 'i=1; while [ \$i -le 8 ]; do screenrecord --size 540x1200 --bit-rate 1000000 --time-limit 180 /sdcard/qa-rec-\$(printf %02d \$i).mp4; i=\$((i+1)); done' >/dev/null 2>&1 &"
export MINIMAX_API_KEY="${PI_API_KEY:-}"
rc=0
node scripts/qa/acceptance_agent.mjs --goal "${QA_GOAL:-$DEFAULT_GOAL}" --max-turns "$QA_MAX_TURNS" --max-minutes "$QA_MAX_MINUTES" \
  --no-progress-turns "$QA_NO_PROGRESS_TURNS" --package "$PKG" --out qa-evidence || rc=$?
adb shell pidof "$PKG" >> qa-evidence/app-pids.txt 2>/dev/null || true
adb logcat -d -v threadtime > qa-evidence/logcat-full.txt 2>&1 || true
node scripts/qa/scope_logcat.mjs --input qa-evidence/logcat-full.txt --output qa-evidence/logcat.txt --verdict qa-evidence/verdict.json \
  --pids qa-evidence/app-pids.txt --package "$PKG" --generation "${QA_CAPTURE_SCOPE_GENERATION:-${GITHUB_RUN_ID:-local}:${GITHUB_RUN_ATTEMPT:-1}}" || true
adb shell dumpsys activity top > qa-evidence/activity-top.txt 2>&1 || true
adb shell dumpsys gfxinfo "$PKG" > qa-evidence/gfxinfo.txt 2>&1 || true
adb shell pkill -INT screenrecord 2>/dev/null || true
sleep 3
echo "$rc" > qa-evidence/agent-rc.txt
echo "release settings case: agent exit code $rc"
