#!/usr/bin/env bash
# Report an acceptance run's evidence + verdict back to Stamp
# (https://raft-app-stamp.botiverse.dev). No-op unless STAMP_RUN_CASE_ID is set.
# Usage: stamp_report.sh <phase: dispatch-start|dispatch-error|claim|credential-receipt|report-evidence|report-verdict|report> <evidence-dir> [prerequisite-rc]
set -u
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
PHASE="${1:-report}"
EV="${2:-qa-evidence}"
[ -n "${STAMP_RUN_CASE_ID:-}" ] || { echo "stamp: no run_case_id, skipping"; exit 0; }
[ -n "${STAMP_URL:-}" ] && [ -n "${STAMP_TOKEN:-}" ] || { echo "stamp: URL/token missing, skipping"; exit 0; }
AUTH="Authorization: Bearer $STAMP_TOKEN"
BASE="$STAMP_URL/api/agent/run-cases/$STAMP_RUN_CASE_ID"
JOB_REF="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"
JOB_HEADER="X-Stamp-CI-Job-Ref: $JOB_REF"
CLAIM_TOKEN_FILE="${RUNNER_TEMP:-$EV}/stamp-claim-$STAMP_RUN_CASE_ID"
BOOTSTRAP_SPEC_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-spec-$STAMP_RUN_CASE_ID.json"
BOOTSTRAP_LOGIN_RESPONSE_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-login-$STAMP_RUN_CASE_ID.json"
BOOTSTRAP_READY_RESPONSE_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-ready-$STAMP_RUN_CASE_ID.json"
BOOTSTRAP_CAPTURE_CONTEXT_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-capture-context-$STAMP_RUN_CASE_ID.json"
BOOTSTRAP_CAPTURE_ENV_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-capture-env-$STAMP_RUN_CASE_ID.sh"
CREDENTIAL_RECEIPT_FILE="${RUNNER_TEMP:-$EV}/stamp-credential-receipt-$STAMP_RUN_CASE_ID.json"

if [ "$PHASE" = "workflow-error" ] && [ ! -f "$CLAIM_TOKEN_FILE" ]; then
  PHASE="dispatch-error"
fi

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
    node -e 'const fs=require("fs");const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(!v.claim_token)process.exit(2);fs.writeFileSync(process.argv[2],v.claim_token,{mode:0o600});const b=v.auth_bootstrap&&typeof v.auth_bootstrap==="object"?v.auth_bootstrap:{required:false};fs.writeFileSync(process.argv[3],JSON.stringify({...b,required:b.required===true,run_case_id:v.run_case_id},null,2),{mode:0o600})' "$CLAIM_RESPONSE" "$CLAIM_TOKEN_FILE" "$BOOTSTRAP_SPEC_FILE"
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
BOOTSTRAP_CURL_TIMEOUTS=(--connect-timeout 10 --max-time 30)

if [ "$PHASE" = "credential-receipt" ]; then
  [ -s "$BOOTSTRAP_SPEC_FILE" ] || { echo "stamp: bootstrap spec missing" >&2; exit 1; }
  if node -e 'const v=require(process.argv[1]);process.exit(v.required===true?0:1)' "$BOOTSTRAP_SPEC_FILE"; then
    echo "stamp: bootstrap coordinate forbids executor credential receipt"
    exit 0
  fi
  [ -n "${QA_TEST_EMAIL:-}" ] || { echo "stamp: legacy credential email missing" >&2; exit 1; }
  if ! node "$SCRIPT_DIR/credential_receipt.mjs" --out "$CREDENTIAL_RECEIPT_FILE"; then
    rm -f "$CREDENTIAL_RECEIPT_FILE"
    echo "stamp: credential email receipt invalid" >&2
    exit 1
  fi
  if ! curl -sfS -X POST "$BASE/credential-receipt" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" \
    -H 'content-type: application/json' --data-binary @"$CREDENTIAL_RECEIPT_FILE" >/dev/null; then
    rm -f "$CREDENTIAL_RECEIPT_FILE"
    echo "stamp: credential email receipt freeze failed" >&2
    exit 1
  fi
  rm -f "$CREDENTIAL_RECEIPT_FILE"
  echo "stamp: credential email receipt frozen"
  exit 0
fi

if [ "$PHASE" = "bootstrap-login" ]; then
  BINDING_FILE="${2:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-device-binding.json}"
  [ -s "$BOOTSTRAP_SPEC_FILE" ] && [ -s "$BINDING_FILE" ] || { echo "stamp: bootstrap spec/device binding missing" >&2; exit 1; }
  ENDPOINT=$(node -e 'const v=require(process.argv[1]);if(v.required!==true||v.contract_version!=="qa-bootstrap-login/v1"||typeof v.login_endpoint!=="string")process.exit(2);process.stdout.write(v.login_endpoint)' "$BOOTSTRAP_SPEC_FILE") \
    || { echo "stamp: bootstrap login contract unavailable" >&2; exit 1; }
  install -m 600 /dev/null "$BOOTSTRAP_LOGIN_RESPONSE_FILE"
  if curl -sfS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" --retry 2 --retry-all-errors -X POST "${STAMP_URL%/}$ENDPOINT" \
    -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    --data-binary @"$BINDING_FILE" -o "$BOOTSTRAP_LOGIN_RESPONSE_FILE"; then
    chmod 600 "$BOOTSTRAP_LOGIN_RESPONSE_FILE"
    node -e 'const fs=require("fs"),v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(typeof v.request_id!=="string"||!v.envelope||typeof v.envelope_digest!=="string"||typeof v.expires_at!=="string")process.exit(2)' "$BOOTSTRAP_LOGIN_RESPONSE_FILE" \
      || { rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE"; echo "stamp: bootstrap login response invalid" >&2; exit 1; }
    echo "stamp: bootstrap login envelope received"
    exit 0
  fi
  rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE"
  echo "stamp: bootstrap login failed" >&2
  exit 1
fi

if [ "$PHASE" = "bootstrap-ready" ]; then
  READY_FILE="${2:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-ready.json}"
  [ -s "$BOOTSTRAP_SPEC_FILE" ] && [ -s "$READY_FILE" ] || { echo "stamp: bootstrap spec/READY payload missing" >&2; exit 1; }
  ENDPOINT=$(node -e 'const v=require(process.argv[1]);if(v.required!==true||typeof v.ready_endpoint!=="string")process.exit(2);process.stdout.write(v.ready_endpoint)' "$BOOTSTRAP_SPEC_FILE") \
    || { echo "stamp: bootstrap READY contract unavailable" >&2; exit 1; }
  install -m 600 /dev/null "$BOOTSTRAP_READY_RESPONSE_FILE"
  if curl -sfS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" --retry 2 --retry-all-errors -X POST "${STAMP_URL%/}$ENDPOINT" \
    -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    --data-binary @"$READY_FILE" -o "$BOOTSTRAP_READY_RESPONSE_FILE"; then
    chmod 600 "$BOOTSTRAP_READY_RESPONSE_FILE"
    node -e '
      const fs=require("fs");
      const [specPath,loginPath,readyPath,responsePath,contextPath,envPath]=process.argv.slice(1);
      const spec=JSON.parse(fs.readFileSync(specPath,"utf8"));
      const login=JSON.parse(fs.readFileSync(loginPath,"utf8"));
      const ready=JSON.parse(fs.readFileSync(readyPath,"utf8"));
      const response=JSON.parse(fs.readFileSync(responsePath,"utf8"));
      const envelope=login.envelope??{};
      const values={
        capture_window_nonce:response.capture_window_nonce,
        scope_generation:response.scope_generation,
        ready_receipt_digest:login.envelope_digest,
        run_case_id:envelope.run_case_id,
        attempt:envelope.attempt,
        capture_proof_endpoint:spec.capture_proof_endpoint,
      };
      const safe=v=>typeof v==="string"&&/^[A-Za-z0-9_.:\/-]{1,256}$/.test(v);
      if(!safe(values.capture_window_nonce)||!safe(values.scope_generation)||
         !/^sha256:[a-f0-9]{64}$/.test(values.ready_receipt_digest??"")||
         !safe(values.run_case_id)||!Number.isInteger(values.attempt)||values.attempt<=0||
         typeof values.capture_proof_endpoint!=="string"||!values.capture_proof_endpoint.startsWith("/api/agent/run-cases/")) process.exit(2);
      if(ready.envelope_digest!==values.ready_receipt_digest||ready.runtime_envelope?.ready_receipt_digest!==values.ready_receipt_digest) process.exit(3);
      fs.writeFileSync(contextPath,JSON.stringify(values),{mode:0o600});
      const lines=[
        ["QA_CAPTURE_WINDOW_NONCE",values.capture_window_nonce],
        ["QA_CAPTURE_SCOPE_GENERATION",values.scope_generation],
        ["QA_READY_RECEIPT_DIGEST",values.ready_receipt_digest],
        ["QA_RUN_CASE_ID",values.run_case_id],
        ["QA_ATTEMPT",String(values.attempt)],
      ].map(([k,v])=>`export ${k}=${v}`);
      fs.writeFileSync(envPath,lines.join("\n")+"\n",{mode:0o600});
    ' "$BOOTSTRAP_SPEC_FILE" "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$READY_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE" \
      || { rm -f "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE"; echo "stamp: bootstrap READY response/capture scope invalid" >&2; exit 1; }
    echo "stamp: bootstrap READY accepted"
    exit 0
  fi
  rm -f "$BOOTSTRAP_READY_RESPONSE_FILE"
  echo "stamp: bootstrap READY failed" >&2
  exit 1
fi

if [ "$PHASE" = "bootstrap-status" ]; then
  STATUS_FILE="${2:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-status-$STAMP_RUN_CASE_ID.json}"
  [ -s "$BOOTSTRAP_SPEC_FILE" ] || { echo "stamp: bootstrap spec missing" >&2; exit 1; }
  ENDPOINT=$(node -e 'const v=require(process.argv[1]);if(v.required!==true||typeof v.status_endpoint!=="string")process.exit(2);process.stdout.write(v.status_endpoint)' "$BOOTSTRAP_SPEC_FILE") \
    || { echo "stamp: bootstrap status contract unavailable" >&2; exit 1; }
  install -m 600 /dev/null "$STATUS_FILE"
  if curl -sfS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" --retry 2 --retry-all-errors -X GET "${STAMP_URL%/}$ENDPOINT" \
    -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -o "$STATUS_FILE"; then
    chmod 600 "$STATUS_FILE"
    node -e 'const v=require(process.argv[1]);if(v.contract_version!=="qa-bootstrap-login/v1"||v.required!==true||!["issued","ready","torn_down","aborted","failed","error"].includes(v.status))process.exit(2)' "$STATUS_FILE" \
      || { rm -f "$STATUS_FILE"; echo "stamp: bootstrap status response invalid" >&2; exit 1; }
    echo "stamp: bootstrap status read"
    exit 0
  fi
  rm -f "$STATUS_FILE"
  echo "stamp: bootstrap status unavailable" >&2
  exit 1
fi

if [ "$PHASE" = "bootstrap-failure-terminal" ]; then
  BINDING_FILE="${2:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-device-binding.json}"
  READY_FILE="${3:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-ready.json}"
  STATUS_FILE="${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-failure-status-$STAMP_RUN_CASE_ID.json"
  CLEANUP_FILE="${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-failure-cleanup-$STAMP_RUN_CASE_ID.json"
  [ -s "$BINDING_FILE" ] || { echo "stamp: bootstrap failure lacks device binding" >&2; exit 1; }
  if [ ! -s "$BOOTSTRAP_LOGIN_RESPONSE_FILE" ]; then
    FAILURE_ENDPOINT=$(node -e 'const v=require(process.argv[1]),expected=`/api/agent/run-cases/${process.argv[2]}/auth-bootstrap/login-failure-terminal`;if(v.required!==true||v.failure_terminal_endpoint!==expected)process.exit(2);process.stdout.write(v.failure_terminal_endpoint)' "$BOOTSTRAP_SPEC_FILE" "$STAMP_RUN_CASE_ID") \
      || { echo "stamp: no-material failure terminal contract unavailable" >&2; exit 1; }
    FAILURE_PAYLOAD="${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-failure-terminal-$STAMP_RUN_CASE_ID.json"
    node -e 'const fs=require("fs"),v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(typeof v.signing_cert_sha256!=="string"||!/^sha256:[a-f0-9]{64}$/.test(v.signing_cert_sha256)||typeof v.boot_generation!=="string"||!v.boot_generation||v.boot_generation!==v.boot_generation.trim())process.exit(2);fs.writeFileSync(process.argv[2],JSON.stringify({signing_cert_sha256:v.signing_cert_sha256,boot_generation:v.boot_generation}),{mode:0o600})' "$BINDING_FILE" "$FAILURE_PAYLOAD" \
      || { rm -f "$FAILURE_PAYLOAD"; echo "stamp: no-material failure terminal payload invalid" >&2; exit 1; }
    rc=0
    curl -sfS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" --retry 2 --retry-all-errors -X POST "${STAMP_URL%/}$FAILURE_ENDPOINT" \
      -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
      --data-binary @"$FAILURE_PAYLOAD" >/dev/null || rc=$?
    rm -f "$FAILURE_PAYLOAD" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE"
    if [ "$rc" = "0" ]; then
      echo "stamp: canonical no-material bootstrap failure terminal confirmed"
      exit 0
    fi
    echo "stamp: no-material bootstrap failure terminal failed" >&2
    exit 1
  fi
  if ! "$0" bootstrap-status "$STATUS_FILE"; then
    PKG=$(cat qa-app-package.txt 2>/dev/null || true)
    [ -z "$PKG" ] || { adb shell am force-stop "$PKG" || true; adb shell pm clear "$PKG" || true; }
    rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE"
    echo "stamp: bootstrap state unknown; device cleared, terminal outcome not guessed" >&2
    exit 1
  fi
  state=$(node -e 'const v=require(process.argv[1]);process.stdout.write(v.status)' "$STATUS_FILE")
  rm -f "$STATUS_FILE"
  case "$state" in
    ready)
      [ -s "$READY_FILE" ] || { echo "stamp: READY state lacks local process binding" >&2; exit 1; }
      outcome=completed
      ready_args=(--ready "$READY_FILE")
      ;;
    issued)
      outcome=aborted
      ready_args=()
      ;;
    torn_down|aborted|failed|error)
      rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE"
      echo "stamp: bootstrap already terminal state=$state"
      exit 0
      ;;
    *) echo "stamp: unsupported bootstrap failure state=$state" >&2; exit 1;;
  esac
  cleanup_rc=0
  node "$(dirname "$0")/mobile_login_bootstrap.mjs" cleanup --response "$BOOTSTRAP_LOGIN_RESPONSE_FILE" --binding "$BINDING_FILE" "${ready_args[@]}" --outcome "$outcome" --output "$CLEANUP_FILE" --run-case-id "$STAMP_RUN_CASE_ID" --ci-job-ref "$JOB_REF" || cleanup_rc=$?
  if [ "$cleanup_rc" != "0" ]; then
    PKG=$(cat qa-app-package.txt 2>/dev/null || true)
    [ -z "$PKG" ] || { adb shell am force-stop "$PKG" || true; adb shell pm clear "$PKG" || true; }
    rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE" "$CLEANUP_FILE"
    echo "stamp: bootstrap failure cleanup failed" >&2
    exit "$cleanup_rc"
  fi
  "$0" bootstrap-terminal "$outcome" "$CLEANUP_FILE"
  rc=$?
  rm -f "$CLEANUP_FILE"
  exit "$rc"
fi

if [ "$PHASE" = "bootstrap-terminal" ]; then
  OUTCOME="${2:-aborted}"
  CLEANUP_FILE="${3:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-cleanup.json}"
  [ "$OUTCOME" = "completed" ] || [ "$OUTCOME" = "aborted" ] || { echo "stamp: invalid bootstrap terminal outcome" >&2; exit 1; }
  [ -s "$BOOTSTRAP_SPEC_FILE" ] && [ -s "$BOOTSTRAP_LOGIN_RESPONSE_FILE" ] && [ -s "$CLEANUP_FILE" ] \
    || { echo "stamp: bootstrap terminal inputs missing" >&2; rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE"; exit 1; }
  ENDPOINT=$(node -e 'const v=require(process.argv[1]);if(v.required!==true||typeof v.terminal_endpoint!=="string")process.exit(2);process.stdout.write(v.terminal_endpoint)' "$BOOTSTRAP_SPEC_FILE") \
    || { echo "stamp: bootstrap terminal contract unavailable" >&2; rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE"; exit 1; }
  TERMINAL_PAYLOAD="${RUNNER_TEMP:-$EV}/stamp-bootstrap-terminal-$STAMP_RUN_CASE_ID.json"
  OUTCOME="$OUTCOME" node -e 'const fs=require("fs"),login=JSON.parse(fs.readFileSync(process.argv[1],"utf8")),cleanup=JSON.parse(fs.readFileSync(process.argv[2],"utf8"));const refresh=login.envelope?.refresh_token;if(typeof refresh!=="string"||!refresh)process.exit(2);fs.writeFileSync(process.argv[3],JSON.stringify({request_id:login.request_id,envelope_digest:login.envelope_digest,refresh_token:refresh,outcome:process.env.OUTCOME,device_cleanup:cleanup}),{mode:0o600})' \
    "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$CLEANUP_FILE" "$TERMINAL_PAYLOAD" \
    || { rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$TERMINAL_PAYLOAD"; echo "stamp: bootstrap terminal payload invalid" >&2; exit 1; }
  rc=0
  curl -sfS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" --retry 2 --retry-all-errors -X POST "${STAMP_URL%/}$ENDPOINT" \
    -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    --data-binary @"$TERMINAL_PAYLOAD" >/dev/null || rc=$?
  rm -f "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BOOTSTRAP_READY_RESPONSE_FILE" "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" "$BOOTSTRAP_CAPTURE_ENV_FILE" "$TERMINAL_PAYLOAD"
  if [ "$rc" = "0" ]; then
    echo "stamp: bootstrap terminal accepted outcome=$OUTCOME"
    exit 0
  fi
  echo "stamp: bootstrap terminal failed outcome=$OUTCOME" >&2
  exit 1
fi

if [ "$PHASE" = "workflow-error" ]; then
  ERROR_PAYLOAD="${RUNNER_TEMP:-$EV}/stamp-workflow-error-$STAMP_RUN_CASE_ID.json"
  ERROR_RESPONSE="${RUNNER_TEMP:-$EV}/stamp-workflow-error-response-$STAMP_RUN_CASE_ID.json"
  REASON="${STAMP_POSTCLAIM_ERROR_REASON:-workflow failed after Stamp claim}"
  ERROR_PHASE="${STAMP_POSTCLAIM_ERROR_PHASE:-post_claim}"
  REASON="$REASON" ERROR_PHASE="$ERROR_PHASE" node -e 'const fs=require("fs"),e=process.env;fs.writeFileSync(process.argv[1],JSON.stringify({verdict:"error",failure_class:"harness",phase:e.ERROR_PHASE,reason:e.REASON}))' "$ERROR_PAYLOAD"
  code=$(curl -sS -o "$ERROR_RESPONSE" -w '%{http_code}' -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' --data-binary @"$ERROR_PAYLOAD" || true)
  if [[ "$code" =~ ^2 ]]; then
    rm -f "$ERROR_PAYLOAD" "$ERROR_RESPONSE"
    echo "stamp: claimed workflow failure reported"
    exit 0
  fi
  response_code=$(node -e 'try{const v=require(process.argv[1]);process.stdout.write(String(v.code||""))}catch{}' "$ERROR_RESPONSE")
  rm -f "$ERROR_PAYLOAD" "$ERROR_RESPONSE"
  if [ "$code" = "409" ] && [ "$response_code" = "RUN_CASE_NOT_RUNNING" ]; then
    echo "stamp: claimed workflow already terminal; no-op"
    exit 0
  fi
  echo "stamp: claimed workflow failure report failed HTTP $code code=$response_code" >&2
  exit 1
fi

# Reporting is deliberately split around auth-bootstrap teardown:
#   READY -> report-evidence -> cleanup/terminal -> report-verdict.
# Stamp rejects evidence once terminal freezes the bootstrap as torn_down, and
# rejects/normalizes a product verdict while it is still ready. `report` stays
# as the non-bootstrap/backward-compatible combined form.
REPORT_EVIDENCE=false
REPORT_VERDICT=false
case "$PHASE" in
  report-evidence) REPORT_EVIDENCE=true;;
  report-verdict) REPORT_VERDICT=true;;
  report) REPORT_EVIDENCE=true; REPORT_VERDICT=true;;
  *) echo "stamp: unknown phase $PHASE" >&2; exit 2;;
esac

# Freeze the execution identity before any scoped log or verdict write. Stamp
# accepts an exact repeat idempotently and rejects a conflicting generation.
evidence_rc=0
CAPTURE_PROOF_REQUIRED=false
CAPTURE_WINDOW_NONCE=""
CAPTURE_SCOPE_GENERATION=""
CAPTURE_PROOF_ENDPOINT=""
if [ "$REPORT_EVIDENCE" = "true" ] && [ -s "$BOOTSTRAP_SPEC_FILE" ]; then
  if node -e 'const v=require(process.argv[1]);process.exit(v.required===true?0:1)' "$BOOTSTRAP_SPEC_FILE"; then
    CAPTURE_PROOF_REQUIRED=true
    [ -s "$BOOTSTRAP_CAPTURE_CONTEXT_FILE" ] || { echo "stamp: bootstrap capture context missing" >&2; exit 1; }
    mapfile -t CAPTURE_CONTEXT < <(node -e 'const v=require(process.argv[1]);for(const k of ["capture_window_nonce","scope_generation","capture_proof_endpoint"]){if(typeof v[k]!=="string"||!v[k])process.exit(2);console.log(v[k])}' "$BOOTSTRAP_CAPTURE_CONTEXT_FILE")
    [ "${#CAPTURE_CONTEXT[@]}" = "3" ] || { echo "stamp: bootstrap capture context invalid" >&2; exit 1; }
    CAPTURE_WINDOW_NONCE="${CAPTURE_CONTEXT[0]}"
    CAPTURE_SCOPE_GENERATION="${CAPTURE_CONTEXT[1]}"
    CAPTURE_PROOF_ENDPOINT="${CAPTURE_CONTEXT[2]}"
  fi
fi
if [ "$REPORT_EVIDENCE" = "true" ] && [ -f "$EV/verdict.json" ] && [ -f "$EV/logcat.txt" ]; then
  if ! SCOPE=$(node -e 'const fs=require("fs");const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));const s=v.log_scope;if(!(s&&s.filtered===true&&s.package_id&&Array.isArray(s.pids)&&s.pids.length&&s.generation))process.exit(2);process.stdout.write(JSON.stringify({package_id:s.package_id,pids:s.pids,generation:s.generation}))' "$EV/verdict.json"); then
    echo "stamp: scoped log exists but verdict execution scope is invalid" >&2
    exit 1
  fi
  curl -sf -X POST "$BASE/execution-scope" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    -d "$SCOPE" >/dev/null && echo "stamp: execution scope frozen" || { echo "stamp: execution scope freeze failed" >&2; exit 1; }
fi

# The email is frozen by the dedicated pre-executor phase. Subject binding is optional and may use only
# the package/PID/generation-scoped product log after Stamp accepts that exact
# execution scope. Model transcripts and broad host/system logs are never
# identity authorities. If no scoped log exists, repeat the email-only receipt.
if [ "$REPORT_EVIDENCE" = "true" ] && [ -n "${QA_TEST_EMAIL:-}" ]; then
  [ -s "$BOOTSTRAP_SPEC_FILE" ] || { echo "stamp: bootstrap spec missing for credential receipt" >&2; exit 1; }
  if ! node -e 'const v=require(process.argv[1]);process.exit(v.required===true?0:1)' "$BOOTSTRAP_SPEC_FILE"; then
    RECEIPT_LOG_ARGS=()
    [ -f "$EV/logcat.txt" ] && RECEIPT_LOG_ARGS=(--log "$EV/logcat.txt")
    if ! node "$SCRIPT_DIR/credential_receipt.mjs" --out "$CREDENTIAL_RECEIPT_FILE" "${RECEIPT_LOG_ARGS[@]}"; then
      echo "stamp: credential receipt construction failed" >&2
      exit 1
    fi
    if ! curl -sfS -X POST "$BASE/credential-receipt" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" \
      -H 'content-type: application/json' --data-binary @"$CREDENTIAL_RECEIPT_FILE" >/dev/null; then
      rm -f "$CREDENTIAL_RECEIPT_FILE"
      echo "stamp: credential receipt subject bind failed" >&2
      exit 1
    fi
    rm -f "$CREDENTIAL_RECEIPT_FILE"
    echo "stamp: credential receipt refreshed"
  else
    echo "stamp: bootstrap coordinate skips executor credential receipt refresh"
  fi
fi

seq=0
upload() { # file kind
  local f="$1" kind="$2" sha mt base step_query="" proof_query="" proof_payload proof_response command_generation
  sha=$(sha256sum "$f" | cut -d' ' -f1)
  # capture time = file mtime (ms); GNU stat on Linux, BSD stat on macOS.
  mt=$(stat -c %Y "$f" 2>/dev/null || stat -f %m "$f" 2>/dev/null || echo "")
  base=$(basename "$f")
  if [[ "$base" =~ ^step-([0-9]+)(\.annotated)?\.png$ ]]; then
    step_query="&step=$((10#${BASH_REMATCH[1]}))"
  fi
  seq=$((seq+1))
  if [ "$CAPTURE_PROOF_REQUIRED" = "true" ]; then
    proof_payload="${RUNNER_TEMP:-$EV}/stamp-capture-proof-$STAMP_RUN_CASE_ID-$seq.json"
    proof_response="${RUNNER_TEMP:-$EV}/stamp-capture-proof-response-$STAMP_RUN_CASE_ID-$seq.json"
    command_generation="run${GITHUB_RUN_ID:-0}.attempt${GITHUB_RUN_ATTEMPT:-1}.seq${seq}"
    CAPTURE_WINDOW_NONCE="$CAPTURE_WINDOW_NONCE" CAPTURE_SCOPE_GENERATION="$CAPTURE_SCOPE_GENERATION" ARTIFACT_SHA="$sha" PRODUCER="mobile.$kind" COMMAND_GENERATION="$command_generation" \
      node -e 'const fs=require("fs"),e=process.env;fs.writeFileSync(process.argv[1],JSON.stringify({capture_window_nonce:e.CAPTURE_WINDOW_NONCE,scope_generation:e.CAPTURE_SCOPE_GENERATION,artifact_sha256:e.ARTIFACT_SHA,producer:e.PRODUCER,command_generation:e.COMMAND_GENERATION}),{mode:0o600})' "$proof_payload"
    if ! curl -sfS -X POST "${STAMP_URL%/}$CAPTURE_PROOF_ENDPOINT" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' --data-binary @"$proof_payload" -o "$proof_response"; then
      rm -f "$proof_payload" "$proof_response"
      echo "stamp: capture proof failed $(basename "$f")" >&2
      evidence_rc=1
      return
    fi
    proof_query=$(ARTIFACT_SHA="$sha" node -e 'const fs=require("fs"),v=JSON.parse(fs.readFileSync(process.argv[1],"utf8")),s=new URLSearchParams();if(typeof v.capture_proof_id!=="string"||typeof v.capture_upload_identity!=="string"||v.artifact_sha256!==process.env.ARTIFACT_SHA)process.exit(2);s.set("capture_proof_id",v.capture_proof_id);s.set("capture_upload_identity",v.capture_upload_identity);process.stdout.write("&"+s.toString())' "$proof_response") \
      || { rm -f "$proof_payload" "$proof_response"; echo "stamp: capture proof response invalid $(basename "$f")" >&2; evidence_rc=1; return; }
    rm -f "$proof_payload" "$proof_response"
  fi
  if curl -sf -X POST "$BASE/evidence?kind=$kind&seq=$seq&sha256=$sha${step_query}${mt:+&captured_at=${mt}000}${proof_query}" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" \
    --data-binary @"$f" >/dev/null; then
    echo "stamp: uploaded $(basename "$f")"
  else
    echo "stamp: upload failed $(basename "$f")" >&2
    evidence_rc=1
  fi
}
if [ "$REPORT_EVIDENCE" = "true" ]; then
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
  [ "$REPORT_VERDICT" = "true" ] || exit "$evidence_rc"
fi

if [ "$REPORT_VERDICT" = "true" ]; then
  prerequisite_rc="${3:-$evidence_rc}"
  VERDICT_FILE="$EV/verdict.json"
  OVERRIDE_FILE="${RUNNER_TEMP:-$EV}/stamp-harness-verdict-$STAMP_RUN_CASE_ID.json"
  if [ "$prerequisite_rc" != "0" ]; then
    node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({verdict:"harness",reason:"Stamp evidence/bootstrap lifecycle incomplete; product verdict suppressed"}))' "$OVERRIDE_FILE"
    VERDICT_FILE="$OVERRIDE_FILE"
  fi
  if [ -f "$VERDICT_FILE" ]; then
  curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
      --data-binary @"$VERDICT_FILE" >/dev/null && { rm -f "$OVERRIDE_FILE"; echo "stamp: verdict reported"; } \
      || { rm -f "$OVERRIDE_FILE"; echo "stamp: verdict report failed" >&2; exit 1; }
  else
    curl -sf -X POST "$BASE/verdict" -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
      -d '{"verdict":"error","reason":"no verdict.json produced"}' >/dev/null \
      || { echo "stamp: missing-verdict report failed" >&2; exit 1; }
  fi
fi
