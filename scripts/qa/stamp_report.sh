#!/usr/bin/env bash
# Report an acceptance run's evidence + verdict back to Stamp
# (https://raft-app-stamp.botiverse.dev). No-op unless STAMP_RUN_CASE_ID is set.
# Usage: stamp_report.sh <phase: claim|report> <evidence-dir>
set -u
PHASE="${1:-report}"
EV="${2:-qa-evidence}"
[ -n "${STAMP_RUN_CASE_ID:-}" ] || { echo "stamp: no run_case_id, skipping"; exit 0; }
[ -n "${STAMP_URL:-}" ] && [ -n "${STAMP_TOKEN:-}" ] || { echo "stamp: URL/token missing, skipping"; exit 0; }
AUTH="Authorization: Bearer $STAMP_TOKEN"
BASE="$STAMP_URL/api/agent/run-cases/$STAMP_RUN_CASE_ID"
JOB_REF="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"
JOB_HEADER="X-Stamp-CI-Job-Ref: $JOB_REF"

if [ "$PHASE" = "claim" ]; then
  # bind the exact build under test to the run (artin: every run records its version)
  APK_FILE=$(find apks -name '*.apk' 2>/dev/null | head -1)
  APK_SHA=""; [ -n "$APK_FILE" ] && APK_SHA=$(sha256sum "$APK_FILE" | cut -d' ' -f1)
  VN=""; VC=""
  if [ -n "$APK_FILE" ] && command -v aapt >/dev/null 2>&1; then
    BADGING=$(aapt dump badging "$APK_FILE" 2>/dev/null | head -1)
    VN=$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
    VC=$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
  fi
  curl -sf -X POST "$BASE/claim" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"ci_job_ref\":\"$JOB_REF\",\"source_commit\":\"${GITHUB_SHA:-}\",\"apk_sha256\":\"$APK_SHA\",\"app_version\":\"$VN\",\"version_code\":${VC:-null}}" \
    >/dev/null && { echo "stamp: claimed (commit=${GITHUB_SHA:-?} apk=${APK_SHA:0:12})"; exit 0; }
  echo "stamp: claim failed (strict fail-closed)" >&2
  exit 1
fi

# report phase: upload evidence files, then the verdict
# Freeze the execution identity before any scoped log or verdict write. Stamp
# accepts an exact repeat idempotently and rejects a conflicting generation.
if [ -f "$EV/verdict.json" ]; then
  SCOPE=$(node -e 'const v=require(process.argv[1]); const s=v.log_scope; if(s&&s.filtered===true&&s.package_id&&Array.isArray(s.pids)&&s.generation) process.stdout.write(JSON.stringify({package_id:s.package_id,pids:s.pids,generation:s.generation}))' "$EV/verdict.json")
  if [ -n "$SCOPE" ]; then
    curl -sf -X POST "$BASE/execution-scope" -H "$AUTH" -H "$JOB_HEADER" -H 'content-type: application/json' \
      -d "$SCOPE" >/dev/null && echo "stamp: execution scope frozen" || { echo "stamp: execution scope freeze failed" >&2; exit 1; }
  fi
fi

seq=0
upload() { # file kind
  local f="$1" kind="$2" sha mt
  sha=$(sha256sum "$f" | cut -d' ' -f1)
  # capture time = file mtime (ms); GNU stat on Linux, BSD stat on macOS.
  mt=$(stat -c %Y "$f" 2>/dev/null || stat -f %m "$f" 2>/dev/null || echo "")
  seq=$((seq+1))
  curl -sf -X POST "$BASE/evidence?kind=$kind&seq=$seq&sha256=$sha${mt:+&captured_at=${mt}000}" -H "$AUTH" -H "$JOB_HEADER" \
    --data-binary @"$f" >/dev/null && echo "stamp: uploaded $(basename "$f")" \
    || echo "stamp: upload failed $(basename "$f") (continuing)"
}
for f in "$EV"/step-*.annotated.png; do [ -f "$f" ] && upload "$f" screenshot_annotated; done
# raw screenshots only when no annotated twin exists (annotated covers review)
for f in "$EV"/step-*.png; do
  case "$f" in *.annotated.png) continue;; esac
  [ -f "${f%.png}.annotated.png" ] && continue
  [ -f "$f" ] && upload "$f" screenshot
done
[ -f "$EV/build-info.json" ] && upload "$EV/build-info.json" build_info
[ -f "$EV/steps.jsonl" ] && upload "$EV/steps.jsonl" steps_log
[ -f "$EV/usage.json" ] && upload "$EV/usage.json" usage
[ -f "$EV/session.jsonl" ] && upload "$EV/session.jsonl" session_log
[ -f "$EV/logcat.txt" ] && upload "$EV/logcat.txt" logcat
for f in "$EV"/qa-rec-*.mp4; do [ -f "$f" ] && upload "$f" recording; done

if [ -f "$EV/verdict.json" ]; then
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H 'content-type: application/json' \
    --data-binary @"$EV/verdict.json" >/dev/null && echo "stamp: verdict reported" \
    || echo "stamp: verdict report failed"
else
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H 'content-type: application/json' \
    -d '{"verdict":"error","reason":"no verdict.json produced"}' >/dev/null || true
fi
