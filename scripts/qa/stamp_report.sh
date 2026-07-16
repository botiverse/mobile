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

if [ "$PHASE" = "claim" ]; then
  curl -sf -X POST "$BASE/claim" -H "$AUTH" -H 'content-type: application/json' \
    -d "{\"ci_job_ref\":\"${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}\"}" \
    >/dev/null && echo "stamp: claimed" || echo "stamp: claim failed (continuing)"
  exit 0
fi

# report phase: upload evidence files, then the verdict
seq=0
upload() { # file kind
  local f="$1" kind="$2" sha
  sha=$(sha256sum "$f" | cut -d' ' -f1)
  seq=$((seq+1))
  curl -sf -X POST "$BASE/evidence?kind=$kind&seq=$seq&sha256=$sha" -H "$AUTH" \
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
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H 'content-type: application/json' \
    --data-binary @"$EV/verdict.json" >/dev/null && echo "stamp: verdict reported" \
    || echo "stamp: verdict report failed"
else
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H 'content-type: application/json' \
    -d '{"verdict":"error","reason":"no verdict.json produced"}' >/dev/null || true
fi
