#!/usr/bin/env bash
# qa-acceptance.yml release-prelogin lane, inside the bound job "Acceptance run (M3 agent on
# emulator)" (Testbed #316/#318). The QA account reaches only release_auto_login.mjs, through the
# 0600 file the step "Stage release login credential" wrote; it is removed as soon as it is read
# (--consume-credential-file) and again below. No step-wide env carries it.
#
#   1. Testbed challenge (attempt id, nonce, pairing salt) -> a private 0600 file in RUNNER_TEMP;
#   2. release_auto_login.mjs --identity-receipt: the release APK's own browser login; nothing is
#      captured; the provider receipt + OS facts are observed privately by Testbed;
#   3. only after a "matched" decision for this attempt and request, and inside the same app
#      process, the case runs as its scenario (scenarios/release_settings_case.mjs ->
#      release_settings_case.sh: recorder, agent, post-run reads; verdict qa-evidence/agent-rc.txt);
#   4. then its cleanup (web sign-out, capture browser, `pm disable`, which kills the app).
#
# Its working evidence stays in RUNNER_TEMP (never uploaded). qa-evidence gets only the public
# projection: result, identity statuses, the process check and cleanup statuses.
set -euo pipefail

: "${STAMP_RUN_CASE_ID:?}" "${RUNNER_TEMP:?}"
credential="$RUNNER_TEMP/release-login-credential/account"
trap 'rm -f "$credential" "$RUNNER_TEMP/testbed-release-challenge-${STAMP_RUN_CASE_ID}.json"' EXIT
mkdir -p qa-evidence
rm -f qa-evidence/agent-rc.txt qa-evidence/login-record.json
touch qa-release-lane
apk=$(find apks -type f -name '*.apk' -print -quit)
[[ -s "$apk" ]] || { echo "::error::release login: no APK under apks/" >&2; echo 2 > qa-evidence/agent-rc.txt; exit 0; }
# Two separate runner-temp directories: the private one (0700, receipt + OS facts) must not
# contain, or be inside, the run's working evidence (release_auto_login.mjs checks both ways).
private_dir="$RUNNER_TEMP/release-identity-private"
work="$RUNNER_TEMP/release-login-evidence"
mkdir -p "$private_dir" "$work"
chmod 700 "$private_dir" "$work"

rc=0
node scripts/qa/testbed_release_settings.mjs challenge || rc=$?
if [[ "$rc" == 0 ]]; then
  env -u QA_TEST_EMAIL -u QA_TEST_PASSWORD -u QA_LOGIN_EMAIL -u QA_LOGIN_PASSWORD \
    QA_ATTEMPT_CHALLENGE_FILE="$RUNNER_TEMP/testbed-release-challenge-${STAMP_RUN_CASE_ID}.json" \
    QA_IDENTITY_PRIVATE_OUT="$private_dir/identity-private-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}.json" \
    QA_TESTBED_RELEASE_SETTINGS=1 \
    node scripts/qa/release_auto_login.mjs --identity-receipt --env production \
      --credential-file "$credential" --consume-credential-file \
      --package "$(cat qa-app-package.txt 2>/dev/null || echo build.raft.app)" \
      --apk "$apk" --capture-browser-apk "$RUNNER_TEMP/capture-browser.apk" \
      --scenario scripts/qa/scenarios/release_settings_case.mjs \
      --out "$work" || rc=$?
fi

node -e '
const fs = require("fs");
let s = {};
try { s = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch {}
const out = { result: s.result ?? "missing", identity_receipt: s.identity_receipt ?? { state: "unknown" },
  identity_process: s.identity_process ?? "not_checked", scenario_result: s.scenario_result ?? "not_run", cleanup: s.cleanup ?? {} };
for (const key of Object.keys(out.cleanup)) if (/_error$|_route$|_log$|_check$|holders|reenabled/.test(key)) delete out.cleanup[key];
fs.writeFileSync(process.argv[2], JSON.stringify(out, null, 2) + "\n");
' "$work/summary.json" qa-evidence/release-login-summary.json

matched=$(node -e 'const s=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));process.stdout.write(s.identity_receipt?.state==="matched"&&s.identity_process==="same"&&s.scenario_result==="pass"?"yes":"no")' qa-evidence/release-login-summary.json)
if [[ "$rc" == 0 && "$matched" == yes && -s qa-evidence/agent-rc.txt ]]; then
  echo "release login: matched; the case ran in the matched process (agent rc $(cat qa-evidence/agent-rc.txt))"
elif [[ -s qa-evidence/agent-rc.txt ]]; then
  # The case ran but its process or cleanup cannot be vouched for: harness/incomplete, not a verdict.
  echo "::error::release login: the case ran but the run did not complete cleanly (rc=$rc); verdict withheld"
  echo 3 > qa-evidence/agent-rc.txt
else
  echo "::error::release login: Testbed did not answer matched for this attempt (rc=$rc); nothing captured, no scenario"
  echo 2 > qa-evidence/agent-rc.txt
fi
exit 0
