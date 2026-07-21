#!/usr/bin/env bash
# Report an acceptance run's evidence + verdict back to Stamp
# (https://raft-app-stamp.botiverse.dev). No-op unless STAMP_RUN_CASE_ID is set.
# Usage: stamp_report.sh <phase: dispatch-start|dispatch-error|claim|report> <evidence-dir>
set -u
PHASE="${1:-report}"
EV="${2:-qa-evidence}"
[ -n "${STAMP_RUN_CASE_ID:-}" ] || { echo "stamp: no run_case_id, skipping"; exit 0; }
[ -n "${STAMP_URL:-}" ] && [ -n "${STAMP_TOKEN:-}" ] || { echo "stamp: URL/token missing, skipping"; exit 0; }
AUTH="Authorization: Bearer $STAMP_TOKEN"
BASE="$STAMP_URL/api/agent/run-cases/$STAMP_RUN_CASE_ID"
JOB_REF="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"
JOB_HEADER="X-Stamp-CI-Job-Ref: $JOB_REF"
CLAIM_TOKEN_FILE="${RUNNER_TEMP:-$EV}/stamp-claim-$STAMP_RUN_CASE_ID"

if [ "$PHASE" = "dispatch-start" ] || [ "$PHASE" = "dispatch-error" ]; then
  # Once a claim lease exists, ordinary executor/product failures belong to
  # the verdict endpoint and must never be rewritten as pre-claim errors.
  [ ! -f "$CLAIM_TOKEN_FILE" ] || { echo "stamp: already claimed, skipping dispatch status"; exit 0; }
  STATUS="started"; [ "$PHASE" = "dispatch-error" ] && STATUS="error"
  PHASE_FILE="${RUNNER_TEMP:-$EV}/stamp-preclaim-phase"
  PRECLAIM_PHASE="checkout_complete"; [ -f "$PHASE_FILE" ] && PRECLAIM_PHASE=$(cat "$PHASE_FILE")
  REASON="${STAMP_PRECLAIM_ERROR_REASON:-workflow failed before Stamp claim}"
  PAYLOAD="${RUNNER_TEMP:-$EV}/stamp-dispatch-status-$STAMP_RUN_CASE_ID.json"
  STATUS="$STATUS" JOB_REF="$JOB_REF" PRECLAIM_PHASE="$PRECLAIM_PHASE" REASON="$REASON" \
    node -e 'const fs=require("fs"),e=process.env;const v={status:e.STATUS,ci_job_ref:e.JOB_REF};if(e.STATUS==="error"){v.phase=e.PRECLAIM_PHASE;v.reason=e.REASON}fs.writeFileSync(process.argv[1],JSON.stringify(v))' "$PAYLOAD"
  if curl -sf -X POST "$BASE/dispatch-status" -H "$AUTH" -H 'content-type: application/json' --data-binary @"$PAYLOAD" >/dev/null; then
    rm -f "$PAYLOAD"
    echo "stamp: dispatch $STATUS ($PRECLAIM_PHASE)"
    exit 0
  fi
  rm -f "$PAYLOAD"
  echo "stamp: dispatch $STATUS report failed" >&2
  [ "$PHASE" = "dispatch-error" ] && exit 0
  exit 1
fi

if [ "$PHASE" = "claim" ]; then
  # bind the exact build under test to the run (artin: every run records its version)
  APK_FILE=$(find apks -name '*.apk' 2>/dev/null | head -1)
  APK_SHA=""; [ -n "$APK_FILE" ] && APK_SHA=$(sha256sum "$APK_FILE" | cut -d' ' -f1)
  VN=""; VC=""; PKG=""
  if [ -n "$APK_FILE" ] && command -v aapt >/dev/null 2>&1; then
    BADGING=$(aapt dump badging "$APK_FILE" 2>/dev/null | head -1)
    PKG=$(printf '%s' "$BADGING" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
    VN=$(printf '%s' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
    VC=$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
  fi
  SOURCE_COMMIT="${STAMP_VERIFIED_SOURCE_COMMIT:-${GITHUB_SHA:-}}"
  ARTIFACT_SHA="${STAMP_VERIFIED_ARTIFACT_SHA256:-$APK_SHA}"
  APP_VERSION="${STAMP_VERIFIED_APP_VERSION:-$VN}"
  VERSION_CODE="${STAMP_VERIFIED_VERSION_CODE:-$VC}"
  PACKAGE_ID="${STAMP_VERIFIED_PACKAGE_ID:-${BUNDLE_ID:-$PKG}}"
  mkdir -p "$EV"
  CLAIM_RESPONSE="${RUNNER_TEMP:-$EV}/stamp-claim-response-$STAMP_RUN_CASE_ID.json"
  CLAIM_PAYLOAD="${RUNNER_TEMP:-$EV}/stamp-claim-payload-$STAMP_RUN_CASE_ID.json"
  SOURCE_COMMIT="$SOURCE_COMMIT" ARTIFACT_SHA="$ARTIFACT_SHA" APP_VERSION="$APP_VERSION" \
    VERSION_CODE="$VERSION_CODE" PACKAGE_ID="$PACKAGE_ID" JOB_REF="$JOB_REF" \
    node -e 'const fs=require("fs"); const e=process.env; const code=e.VERSION_CODE===""?null:Number(e.VERSION_CODE); if(code!==null&&!Number.isInteger(code))process.exit(2); fs.writeFileSync(process.argv[1],JSON.stringify({ci_job_ref:e.JOB_REF,source_commit:e.SOURCE_COMMIT,apk_sha256:e.ARTIFACT_SHA,app_version:e.APP_VERSION,version_code:code,package_id:e.PACKAGE_ID}));' "$CLAIM_PAYLOAD"
  if curl -sf -X POST "$BASE/claim" -H "$AUTH" -H 'content-type: application/json' \
    --data-binary @"$CLAIM_PAYLOAD" \
    -o "$CLAIM_RESPONSE"; then
    node -e 'const fs=require("fs");const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(v.claim_token)fs.writeFileSync(process.argv[2],v.claim_token,{mode:0o600})' "$CLAIM_RESPONSE" "$CLAIM_TOKEN_FILE"
    rm -f "$CLAIM_RESPONSE" "$CLAIM_PAYLOAD"
    echo "stamp: claimed (commit=${SOURCE_COMMIT:-?} artifact=${ARTIFACT_SHA:0:12})"
    exit 0
  fi
  rm -f "$CLAIM_RESPONSE" "$CLAIM_PAYLOAD"
  echo "stamp: claim failed (strict fail-closed)" >&2
  exit 1
fi

CLAIM_TOKEN=""; [ -f "$CLAIM_TOKEN_FILE" ] && CLAIM_TOKEN=$(cat "$CLAIM_TOKEN_FILE")
CLAIM_HEADER="X-Stamp-Claim-Token: $CLAIM_TOKEN"

# report phase: upload evidence files, then the verdict
# Freeze the execution identity before any scoped log or verdict write. Stamp
# accepts an exact repeat idempotently and rejects a conflicting generation.
if [ -f "$EV/verdict.json" ] && [ -f "$EV/logcat.txt" ]; then
  if ! SCOPE=$(node -e 'const fs=require("fs");const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));const s=v.log_scope;if(!(s&&s.filtered===true&&s.package_id&&Array.isArray(s.pids)&&s.pids.length&&s.generation))process.exit(2);process.stdout.write(JSON.stringify({package_id:s.package_id,pids:s.pids,generation:s.generation}))' "$EV/verdict.json"); then
    echo "stamp: scoped log exists but verdict execution scope is invalid" >&2
    exit 1
  fi
  curl -sf -X POST "$BASE/execution-scope" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    -d "$SCOPE" >/dev/null && echo "stamp: execution scope frozen" || { echo "stamp: execution scope freeze failed" >&2; exit 1; }
fi

seq=0
upload() { # file kind
  local f="$1" kind="$2" sha mt
  sha=$(sha256sum "$f" | cut -d' ' -f1)
  # capture time = file mtime (ms); GNU stat on Linux, BSD stat on macOS.
  mt=$(stat -c %Y "$f" 2>/dev/null || stat -f %m "$f" 2>/dev/null || echo "")
  seq=$((seq+1))
  curl -sf -X POST "$BASE/evidence?kind=$kind&seq=$seq&sha256=$sha${mt:+&captured_at=${mt}000}" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" \
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
[ -f "$EV/model-capacity.jsonl" ] && upload "$EV/model-capacity.jsonl" model_capacity
[ -f "$EV/logcat.txt" ] && upload "$EV/logcat.txt" logcat
for f in "$EV"/qa-rec-*.mp4; do [ -f "$f" ] && upload "$f" recording; done

if [ -f "$EV/verdict.json" ]; then
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    --data-binary @"$EV/verdict.json" >/dev/null && echo "stamp: verdict reported" \
    || echo "stamp: verdict report failed"
else
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    -d '{"verdict":"error","reason":"no verdict.json produced"}' >/dev/null || true
fi
