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
CLAIM_SPEC_BINDING_FILE="${RUNNER_TEMP:-$EV}/stamp-claim-spec-$STAMP_RUN_CASE_ID.json"
BOOTSTRAP_LOGIN_RESPONSE_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-login-$STAMP_RUN_CASE_ID.json"
# Non-secret record of the one issuance call: in_flight (written BEFORE the call) | issued | unknown (no client-side "rejected": every 4xx is unknown).
BOOTSTRAP_LOGIN_OUTCOME_FILE="${RUNNER_TEMP:-$EV}/stamp-bootstrap-login-outcome-$STAMP_RUN_CASE_ID.json"
# Exclusive per-run-case issuance lock (mkdir is atomic); never released or broken automatically.
BOOTSTRAP_LOGIN_LOCK_DIR="${RUNNER_TEMP:-$EV}/stamp-bootstrap-login-lock-$STAMP_RUN_CASE_ID"
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
  mkdir -p "$EV"
  CLAIM_RESPONSE="${RUNNER_TEMP:-$EV}/stamp-claim-response-$STAMP_RUN_CASE_ID.json"
  CLAIM_PAYLOAD="${RUNNER_TEMP:-$EV}/stamp-claim-payload-$STAMP_RUN_CASE_ID.json"
  if [ "${STAMP_DIRECT_ANDROID_APK:-false}" = true ]; then
    # The direct-download step measured this exact file using Android SDK aapt.
    # Its build revision is unknown. The workflow checkout identifies the reporter.
    SOURCE_COMMIT=""
    ARTIFACT_SHA=$(sha256sum apks/pinned.apk) || exit 1
    ARTIFACT_SHA=${ARTIFACT_SHA%% *}
    ARTIFACT_SHA="$ARTIFACT_SHA" JOB_REF="$JOB_REF" node - "$CLAIM_PAYLOAD" <<'NODE' || exit 1
const fs = require("node:fs");
const crypto = require("node:crypto");
const { ARTIFACT_SHA, JOB_REF, GITHUB_SHA, STAMP_SEND_RENDERED_GOAL_SHA256, QA_GOAL } = process.env;
const info = JSON.parse(fs.readFileSync("apks/build-info.json", "utf8"));
if (info.platform !== "android" || info.source_commit_verified !== false || info.apk_sha256 !== ARTIFACT_SHA) {
  throw new Error("direct APK does not match the download step's Android build record");
}
const versionCode = /^\d+$/.test(info.version_code) ? Number(info.version_code) : null;
const claim = {
  ci_job_ref: JOB_REF,
  workflow_source_commit: /^[a-f0-9]{40}$/.test(GITHUB_SHA) ? GITHUB_SHA : null,
  source_commit: null,
  apk_sha256: ARTIFACT_SHA,
  app_version: info.app_version || null,
  version_code: Number.isSafeInteger(versionCode) && versionCode > 0 ? versionCode : null,
  package_id: info.package || null,
  ...(STAMP_SEND_RENDERED_GOAL_SHA256 === "true" && QA_GOAL
    ? { rendered_goal_sha256: crypto.createHash("sha256").update(Buffer.from(QA_GOAL, "utf8")).digest("hex") }
    : {}),
};
fs.writeFileSync(process.argv[2], JSON.stringify(claim));
NODE
  else
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
    SOURCE_COMMIT="$SOURCE_COMMIT" ARTIFACT_SHA="$ARTIFACT_SHA" APP_VERSION="$APP_VERSION" \
      VERSION_CODE="$VERSION_CODE" PACKAGE_ID="$PACKAGE_ID" JOB_REF="$JOB_REF" \
      node -e 'const fs=require("fs"),crypto=require("crypto"); const e=process.env; const code=e.VERSION_CODE===""?null:Number(e.VERSION_CODE); if(code!==null&&!Number.isInteger(code))process.exit(2); const body={ci_job_ref:e.JOB_REF,source_commit:e.SOURCE_COMMIT,apk_sha256:e.ARTIFACT_SHA,app_version:e.APP_VERSION,version_code:code,package_id:e.PACKAGE_ID}; if(e.STAMP_SEND_RENDERED_GOAL_SHA256==="true"&&e.QA_GOAL){body.rendered_goal_sha256=crypto.createHash("sha256").update(Buffer.from(e.QA_GOAL,"utf8")).digest("hex");} fs.writeFileSync(process.argv[1],JSON.stringify(body));' "$CLAIM_PAYLOAD" || exit 1
  fi
  # The spec keeps the claim's auth bootstrap subset, its run case and its case_id (a well-formed
  # case id, else null): qa-acceptance.yml makes the login acceptance mandatory for login-email-password.
  if curl -sf -X POST "$BASE/claim" -H "$AUTH" -H 'content-type: application/json' \
    --data-binary @"$CLAIM_PAYLOAD" \
    -o "$CLAIM_RESPONSE"; then
    node -e 'const fs=require("fs");const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(!v.claim_token)process.exit(2);fs.writeFileSync(process.argv[2],v.claim_token,{mode:0o600});const b=v.auth_bootstrap&&typeof v.auth_bootstrap==="object"?v.auth_bootstrap:{required:false};fs.writeFileSync(process.argv[3],JSON.stringify({...b,required:b.required===true,run_case_id:v.run_case_id,case_id:typeof v.case_id==="string"&&/^[a-z0-9][a-z0-9-]{0,63}$/.test(v.case_id)?v.case_id:null},null,2),{mode:0o600})' "$CLAIM_RESPONSE" "$CLAIM_TOKEN_FILE" "$BOOTSTRAP_SPEC_FILE" || { rm -f "$CLAIM_RESPONSE" "$CLAIM_PAYLOAD"; echo "stamp: claim response invalid" >&2; exit 1; }
    if ! node "$SCRIPT_DIR/claim_spec_binding.mjs" record --response "$CLAIM_RESPONSE" --out "$CLAIM_SPEC_BINDING_FILE" --run-case-id "$STAMP_RUN_CASE_ID"; then
      rm -f "$CLAIM_RESPONSE" "$CLAIM_PAYLOAD" "$CLAIM_SPEC_BINDING_FILE"
      echo "stamp: claimed specification or budget binding mismatch (strict fail-closed)" >&2
      exit 1
    fi
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
# The issuance response is received into this temp file and only renamed to
# BOOTSTRAP_LOGIN_RESPONSE_FILE once it validated completely: a partial/unvalidated body never sits
# under the name the rest of the pipeline consumes. It is kept (mode 0600) as reconciliation evidence.
BOOTSTRAP_LOGIN_PARTIAL_FILE="${BOOTSTRAP_LOGIN_RESPONSE_FILE}.partial"

# Full validation of an issuance response (exit 0 only if complete): request id, expires_at, the
# exact envelope key set and types, version/target route, this run case and CI job, the posted device
# binding (signing_cert_sha256 + boot_generation), principal == user.id, non-empty tokens, and
# envelope_digest == sha256(canonical envelope) — validateLoginResponse, the same check prepare runs.
# The application id is checked against the installed app later by prepare. The envelope's attempt is
# only checked to be a positive integer: no trusted Testbed attempt exists client-side, and the GitHub
# run attempt is a different counter that is never substituted for it.
validate_login_response() {
  [ -s "$1" ] && [ -s "$2" ] || return 1
  RUN_CASE="$STAMP_RUN_CASE_ID" CI_JOB_REF="$JOB_REF" LIB="$SCRIPT_DIR/mobile_login_bootstrap_lib.mjs" node --input-type=module -e '
    import fs from "node:fs";
    import { pathToFileURL } from "node:url";
    const { validateLoginResponse } = await import(pathToFileURL(process.env.LIB).href);
    const response = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const binding = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
    if (!response || typeof response !== "object" || Array.isArray(response) || typeof response.expires_at !== "string" || !response.expires_at) process.exit(2);
    const applicationId = response.envelope?.application_id;
    if (typeof applicationId !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(applicationId)) process.exit(2);
    const login = validateLoginResponse(response, {
      runCaseId: process.env.RUN_CASE, ciJobRef: process.env.CI_JOB_REF, applicationId,
      signingCertSha256: binding.signing_cert_sha256, bootGeneration: binding.boot_generation,
    });
    login.bytes.fill(0);
  ' "$1" "$2" >/dev/null 2>&1
}

# The claimed case login-email-password (#216 login acceptance, the claim spec's case_id) reports only its
# verdict and reason code: neither legacy account report (the credential receipt after the claim, and its
# refresh in report-evidence) carries the QA email or an account id for it. Every other case sends both.
login_acceptance_case() {
  node -e 'const v=require(process.argv[1]);process.exit(v.case_id==="login-email-password"?0:1)' "$BOOTSTRAP_SPEC_FILE" 2>/dev/null
}

if [ "$PHASE" = "credential-receipt" ]; then
  [ -s "$BOOTSTRAP_SPEC_FILE" ] || { echo "stamp: bootstrap spec missing" >&2; exit 1; }
  if node -e 'const v=require(process.argv[1]);process.exit(v.required===true?0:1)' "$BOOTSTRAP_SPEC_FILE"; then
    echo "stamp: bootstrap coordinate forbids executor credential receipt"
    exit 0
  fi
  if login_acceptance_case; then
    echo "stamp: the login acceptance case sends no executor credential receipt"
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
  # Issuance is called at most once per run case on this runner: no curl --retry, no second login,
  # no new attempt. A retry after a lost reply cannot recover the refresh token of a session that may
  # already be live. Mechanism (local only):
  #   1. an exclusive lock: `mkdir` of the lock directory is atomic, so of any number of concurrent
  #      invocations exactly one proceeds; the lock is never released or broken automatically;
  #   2. BEFORE calling the endpoint, hard-link the fully written temp file as the initial intent.
  #      A runner filesystem that refuses hard links stops before the request.
  #   3. after the call the intent is atomically replaced by the outcome.
  # A sender killed at any point leaves the lock and the in_flight intent: the next invocation refuses
  # to issue again, and bootstrap-failure-terminal refuses the "no material" terminal. Nothing ever
  # falls back to "not sent". Outcomes:
  #   issued   — 2xx whose body is a well-formed envelope for THIS run case (kept privately for
  #              prepare, which re-validates it exactly, and for the terminal);
  #   unknown  — anything else (exit 3): EVERY 4xx (including 409 BOOTSTRAP_LOGIN_RETRY_REQUIRES_NEW_ATTEMPT
  #              and any rejection-receipt-shaped body: no such receipt is accepted until Testbed
  #              carries an authoritative run-case attempt with server-side terminal verification),
  #              5xx, partial/unproven 2xx, timeout, reset, empty reply, local connect failure. A
  #              session may exist; operator reconciliation with Testbed is required.
  # Scope: this proves one call per run case per runner (RUNNER_TEMP). It does NOT prove one-shot across
  # runner loss (a re-run on a new runner starts without the lock); that needs a Testbed server-side,
  # attempt-bound issuance fence/reconciliation (follow-up). Shared QA sessions are never touched here.
  BINDING_FILE="${2:-${RUNNER_TEMP:-qa-evidence}/stamp-bootstrap-device-binding.json}"
  [ -s "$BOOTSTRAP_SPEC_FILE" ] && [ -s "$BINDING_FILE" ] || { echo "stamp: bootstrap spec/device binding missing" >&2; exit 1; }
  ENDPOINT=$(node -e 'const v=require(process.argv[1]);if(v.required!==true||v.contract_version!=="qa-bootstrap-login/v1"||typeof v.login_endpoint!=="string")process.exit(2);process.stdout.write(v.login_endpoint)' "$BOOTSTRAP_SPEC_FILE") \
    || { echo "stamp: bootstrap login contract unavailable" >&2; exit 1; }
  if ! mkdir "$BOOTSTRAP_LOGIN_LOCK_DIR" 2>/dev/null; then
    state=$(node -e 'try{process.stdout.write(String(require(process.argv[1]).outcome))}catch{process.stdout.write("none")}' "$BOOTSTRAP_LOGIN_OUTCOME_FILE" 2>/dev/null || echo none)
    echo "stamp: bootstrap login already attempted or in flight for this run case (recorded outcome=$state); refusing a second issuance" >&2
    exit 1
  fi
  if [ -e "$BOOTSTRAP_LOGIN_OUTCOME_FILE" ]; then
    echo "stamp: bootstrap login outcome already recorded for this run case; refusing a second issuance" >&2
    exit 1
  fi
  write_login_outcome() {
    OUTCOME="$1" HTTP_CODE="$2" CURL_RC="$3" DETAIL="$4" RUN_CASE="$STAMP_RUN_CASE_ID" RUN_ID="${GITHUB_RUN_ID:-}" RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-}" \
      node -e 'const fs=require("fs"),e=process.env,file=process.argv[1],tmp=`${file}.tmp-${process.pid}`;
        const v={contract:"qa-bootstrap-login-outcome/v1",outcome:e.OUTCOME,run_case_id:e.RUN_CASE,github_run_id:e.RUN_ID,github_run_attempt:e.RUN_ATTEMPT,retried:false};
        if(e.OUTCOME!=="in_flight"){v.http_status=Number(e.HTTP_CODE);v.curl_exit=Number(e.CURL_RC);v.detail=e.DETAIL}
        const fd=fs.openSync(tmp,"wx",0o600);
        try { fs.writeFileSync(fd,JSON.stringify(v,null,2)+"\n");fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
        if (e.OUTCOME==="in_flight") {
          // Publish the complete initial intent only if this run case still has none.
          try { fs.linkSync(tmp,file); } finally { fs.unlinkSync(tmp); }
        } else {
          fs.renameSync(tmp,file);
        }' "$BOOTSTRAP_LOGIN_OUTCOME_FILE"
  }
  write_login_outcome in_flight 0 0 "" || { echo "stamp: could not persist the bootstrap login intent; not calling issuance" >&2; exit 1; }
  install -m 600 /dev/null "$BOOTSTRAP_LOGIN_PARTIAL_FILE"
  curl_rc=0
  http_code=$(curl -sS "${BOOTSTRAP_CURL_TIMEOUTS[@]}" -X POST "${STAMP_URL%/}$ENDPOINT" \
    -H "$AUTH" -H "$JOB_HEADER" -H "$CLAIM_HEADER" -H 'content-type: application/json' \
    --data-binary @"$BINDING_FILE" -o "$BOOTSTRAP_LOGIN_PARTIAL_FILE" -w '%{http_code}') || curl_rc=$?
  chmod 600 "$BOOTSTRAP_LOGIN_PARTIAL_FILE" 2>/dev/null || true
  case "$http_code" in [0-9][0-9][0-9]) ;; *) http_code=000;; esac
  outcome=unknown
  detail="unproven"
  if [ "$curl_rc" = "0" ] && [[ "$http_code" =~ ^2 ]]; then
    detail="unproven_2xx"
    # Complete, digest-verified and bound to this run case / CI job / device, then atomically renamed
    # into place BEFORE "issued" is recorded: a recorded "issued" implies the validated file exists.
    if validate_login_response "$BOOTSTRAP_LOGIN_PARTIAL_FILE" "$BINDING_FILE" \
      && mv -f "$BOOTSTRAP_LOGIN_PARTIAL_FILE" "$BOOTSTRAP_LOGIN_RESPONSE_FILE"; then
      outcome=issued; detail="envelope"
    fi
  elif [ "$curl_rc" = "0" ] && [[ "$http_code" =~ ^4 ]]; then
    # EVERY 4xx is unknown, whatever its body. No "nothing was issued" receipt is accepted until
    # Testbed carries an authoritative run-case attempt in the claim/spec, emits a rejection receipt
    # bound to that attempt, and verifies the same attempt/binding server-side at the no-material
    # terminal. GITHUB_RUN_ATTEMPT is a different counter and must never stand in for it.
    detail="unproven_4xx"
  elif [ "$curl_rc" != "0" ]; then
    detail="curl_exit_$curl_rc"
  fi
  write_login_outcome "$outcome" "$http_code" "$curl_rc" "$detail" \
    || { echo "stamp: could not record the bootstrap login outcome; it stays in_flight (operator reconciliation required)" >&2; exit 3; }
  [ ! -d qa-evidence ] || cp "$BOOTSTRAP_LOGIN_OUTCOME_FILE" qa-evidence/bootstrap-login-outcome.json 2>/dev/null || true
  case "$outcome" in
    issued)
      echo "stamp: bootstrap login envelope received"
      exit 0;;
    *)
      # Any received body stays in the 0600 .partial file as reconciliation evidence (never consumed).
      echo "stamp: bootstrap login outcome unknown ($detail, http=$http_code curl=$curl_rc); a session may exist. No retry, no second login; operator reconciliation required" >&2
      exit 3;;
  esac
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
  login_attempted=false
  { [ -e "$BOOTSTRAP_LOGIN_OUTCOME_FILE" ] || [ -e "$BOOTSTRAP_LOGIN_LOCK_DIR" ] || [ -e "$BOOTSTRAP_LOGIN_PARTIAL_FILE" ]; } && login_attempted=true
  if { [ "$login_attempted" = true ] && ! node -e 'process.exit(require(process.argv[1]).outcome==="issued"?0:1)' "$BOOTSTRAP_LOGIN_OUTCOME_FILE" 2>/dev/null; } \
    || { { [ "$login_attempted" = true ] || [ -e "$BOOTSTRAP_LOGIN_RESPONSE_FILE" ]; } && ! validate_login_response "$BOOTSTRAP_LOGIN_RESPONSE_FILE" "$BINDING_FILE"; }; then
    # An issuance was attempted (or is in flight / its sender died) and we do not hold a recorded
    # "issued" outcome with a completely validated response. Either we hold no material — it may have
    # committed server-side, so claiming "no material" would be a guess (no 4xx or receipt proves it
    # until Testbed carries an authoritative attempt with server-side terminal verification) — or we
    # hold a partial/unvalidated body, which must not drive status/cleanup as if it were issued. Touch
    # nothing, delete nothing (files are reconciliation evidence), leave the attempt for an operator.
    echo "stamp: bootstrap login outcome is not a confirmed no-issuance; refusing the no-material failure terminal (operator reconciliation required)" >&2
    exit 1
  fi
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

# Evidence gate (#222, secure_evidence.mjs) before anything leaves for Stamp: without a valid
# login record of this run (or with a login-like signal, or a secret value) it deletes the
# evidence, rewrites verdict.json to "unknown" and nothing is uploaded.
evidence_rc=0
if [ "$REPORT_EVIDENCE" = "true" ] && ! node "$SCRIPT_DIR/secure_evidence.mjs" --dir "$EV"; then
  echo "stamp: evidence gate UNKNOWN; no evidence uploaded" >&2
  REPORT_EVIDENCE=false
  evidence_rc=1
  [ "$REPORT_VERDICT" = "true" ] || exit 1
fi

# Freeze the execution identity before any scoped log or verdict write. Stamp
# accepts an exact repeat idempotently and rejects a conflicting generation.
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
  if login_acceptance_case; then
    echo "stamp: the login acceptance case skips the executor credential receipt refresh"
  elif ! node -e 'const v=require(process.argv[1]);process.exit(v.required===true?0:1)' "$BOOTSTRAP_SPEC_FILE"; then
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
  # Every raw screenshot, including steps with an annotated twin: the panel
  # reviewer reads raw frames only, and an annotated (assertion) step is the
  # frame it must judge on (run 43569038982 dropped raw step-19).
  for f in "$EV"/step-*.png; do
    case "$f" in *.annotated.png) continue;; esac
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
  # Device-cleanup gate (iOS: the job-owned Simulator is deleted before reporting). When the workflow
  # requires it (STAMP_REQUIRE_DEVICE_CLEANUP=1) or a cleanup record exists, a product verdict (pass or
  # fail) may only be sent if that record says the simulator was deleted and confirmed absent, and it is
  # bound to this run (run id/attempt) and this job's simulator (ownership receipt UDID/name).
  # Otherwise only a harness workflow error is sent: Testbed accepts one verdict while running, so a
  # product verdict sent first could never be corrected by a later workflow-error.
  DEVICE_CLEANUP_RECORD="${STAMP_DEVICE_CLEANUP_RECORD:-$EV/simulator-cleanup.json}"
  if [ "${STAMP_REQUIRE_DEVICE_CLEANUP:-}" = "1" ] || [ -e "$DEVICE_CLEANUP_RECORD" ]; then
    # The record must also be THIS run's: same GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT, and the UDID/name of
    # this job's simulator ownership receipt (a leftover record from another run never counts).
    OWNERSHIP_RECEIPT="${RAFT_QA_SIMULATOR_OWNERSHIP_RECEIPT:-${RUNNER_TEMP:-}/raft-qa-simulator-ownership.json}"
    # AND the delete step itself must have succeeded (the workflow passes steps.<id>.outcome).
    if [ "${STAMP_DEVICE_CLEANUP_OUTCOME:-}" != "success" ] || ! RUN_ID="${GITHUB_RUN_ID:-}" RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:-}" node -e '
        const fs=require("fs"),e=process.env;
        const v=JSON.parse(fs.readFileSync(process.argv[1],"utf8")),o=JSON.parse(fs.readFileSync(process.argv[2],"utf8"));
        const ok=v&&v.cleanup_receipt==="simulator_deleted"&&v.simulator==="deleted_and_absent"&&
          /^[0-9]+$/.test(e.RUN_ID)&&/^[0-9]+$/.test(e.RUN_ATTEMPT)&&
          v.github_run_id===e.RUN_ID&&v.github_run_attempt===e.RUN_ATTEMPT&&
          o&&o.github_run_id===e.RUN_ID&&o.github_run_attempt===e.RUN_ATTEMPT&&
          typeof o.udid==="string"&&String(v.simulator_udid).toUpperCase()===o.udid.toUpperCase()&&v.simulator_name===o.name;
        process.exit(ok?0:1)' "$DEVICE_CLEANUP_RECORD" "$OWNERSHIP_RECEIPT" 2>/dev/null; then
      echo "stamp: device cleanup is not proven (delete step outcome=${STAMP_DEVICE_CLEANUP_OUTCOME:-missing}, record ${DEVICE_CLEANUP_RECORD}); product verdict suppressed, reporting a harness workflow error" >&2
      STAMP_POSTCLAIM_ERROR_PHASE=device_cleanup \
        STAMP_POSTCLAIM_ERROR_REASON="job-owned simulator deletion not proven (cleanup record missing or not simulator_deleted); product verdict suppressed" \
        "$0" workflow-error "$EV" || true
      exit 1
    fi
  fi
  prerequisite_rc="${3:-$evidence_rc}"
  VERDICT_FILE="$EV/verdict.json"
  OVERRIDE_FILE="${RUNNER_TEMP:-$EV}/stamp-harness-verdict-$STAMP_RUN_CASE_ID.json"
  # An UNKNOWN run (#222) never reports pass/fail.
  if node -e 'const v=require(require("path").resolve(process.argv[1]));process.exit(v.verdict==="unknown"?0:1)' "$VERDICT_FILE" 2>/dev/null; then
    node -e 'const fs=require("fs");fs.writeFileSync(process.argv[1],JSON.stringify({verdict:"harness",reason:"UNKNOWN: login window or evidence not provably clean; product verdict suppressed"}))' "$OVERRIDE_FILE"
    VERDICT_FILE="$OVERRIDE_FILE"
  elif [ "$prerequisite_rc" != "0" ]; then
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
