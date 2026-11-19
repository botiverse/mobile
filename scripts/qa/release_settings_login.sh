#!/usr/bin/env bash
# qa-acceptance.yml release-prelogin lane, inside the bound job "Acceptance run (M3 agent on
# emulator)" (Testbed #316/#318). The ONLY process that receives the QA password
# (QA_RELEASE_LOGIN_EMAIL / QA_RELEASE_LOGIN_PASSWORD from that step's env):
#
#   1. Testbed challenge (attempt id, nonce, pairing salt) -> a private 0600 file in RUNNER_TEMP;
#   2. release_auto_login.mjs --identity-receipt: the release APK's own browser login; nothing is
#      captured; the provider receipt + OS facts are observed privately by Testbed; only a
#      "matched" decision for this attempt and request makes it exit 0;
#   3. only on exit 0 is qa-login-ok created (the caller starts the recorder and the agent).
#
# Its working evidence stays in RUNNER_TEMP (never uploaded). qa-evidence gets only the public
# projection: result, scenario/identity statuses and cleanup statuses.
set -euo pipefail

: "${STAMP_RUN_CASE_ID:?}" "${RUNNER_TEMP:?}"
rm -f qa-login-ok
apk=$(find apks -type f -name '*.apk' -print -quit)
[[ -s "$apk" ]] || { echo "::error::release login: no APK under apks/" >&2; exit 1; }
# Two separate runner-temp directories: the private one (0700, receipt + OS facts) must not
# contain, or be inside, the run's working evidence (release_auto_login.mjs checks both ways).
private_dir="$RUNNER_TEMP/release-identity-private"
work="$RUNNER_TEMP/release-login-evidence"
mkdir -p "$private_dir" "$work"
chmod 700 "$private_dir" "$work"

node scripts/qa/testbed_release_settings.mjs challenge

rc=0
env -u PI_API_KEY -u QA_TEST_EMAIL -u QA_TEST_PASSWORD \
  QA_LOGIN_EMAIL="${QA_RELEASE_LOGIN_EMAIL:-}" QA_LOGIN_PASSWORD="${QA_RELEASE_LOGIN_PASSWORD:-}" \
  QA_ATTEMPT_CHALLENGE_FILE="$RUNNER_TEMP/testbed-release-challenge-${STAMP_RUN_CASE_ID}.json" \
  QA_IDENTITY_PRIVATE_OUT="$private_dir/identity-private-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}.json" \
  QA_TESTBED_RELEASE_SETTINGS=1 \
  node scripts/qa/release_auto_login.mjs --identity-receipt --env production \
    --package "$(cat qa-app-package.txt 2>/dev/null || echo build.raft.app)" \
    --apk "$apk" --capture-browser-apk "$RUNNER_TEMP/capture-browser.apk" \
    --out "$work" || rc=$?

mkdir -p qa-evidence
node -e '
const fs = require("fs");
let s = {};
try { s = JSON.parse(fs.readFileSync(process.argv[1], "utf8")); } catch {}
const out = { result: s.result ?? "missing", identity_receipt: s.identity_receipt ?? { state: "unknown" }, cleanup: s.cleanup ?? {} };
for (const key of Object.keys(out.cleanup)) if (/_error$|_route$|_log$|_check$|holders|reenabled/.test(key)) delete out.cleanup[key];
fs.writeFileSync(process.argv[2], JSON.stringify(out, null, 2) + "\n");
' "$work/summary.json" qa-evidence/release-login-summary.json

if [[ "$rc" == 0 ]] && node -e 'process.exit(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).identity_receipt?.state==="matched"?0:1)' qa-evidence/release-login-summary.json; then
  touch qa-login-ok
  echo "release login: matched; capture may open"
else
  echo "::error::release login: Testbed did not answer matched for this attempt (rc=$rc); nothing captured, no scenario"
fi
rm -f "$RUNNER_TEMP/testbed-release-challenge-${STAMP_RUN_CASE_ID}.json"
exit 0
