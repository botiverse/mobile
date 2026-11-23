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
#   4. then its cleanup (web sign-out, capture browser, and `pm disable` of the receipt provider,
#      which kills the app, only for an older package where this run had to `pm enable` it).
#
# Its working evidence stays in RUNNER_TEMP (never uploaded). release_settings_terminal.mjs then
# takes the one terminal decision for both exits and writes the public projection.
set -euo pipefail

: "${STAMP_RUN_CASE_ID:?}" "${RUNNER_TEMP:?}"
credential="$RUNNER_TEMP/release-login-credential/account"
trap 'rm -f "$credential" "$RUNNER_TEMP/testbed-release-challenge-${STAMP_RUN_CASE_ID}.json"' EXIT
mkdir -p qa-evidence
rm -f qa-evidence/agent-rc.txt qa-evidence/login-record.json qa-evidence/release-terminal.json qa-evidence/recording.json
touch qa-release-lane
# The release case has its own goal; never the workflow's default (it opens a channel).
if [[ -z "${QA_GOAL:-}" ]]; then echo "::error::release login: no settings goal (QA_GOAL); stopping before sign-in" >&2; echo 2 > qa-evidence/agent-rc.txt; exit 0; fi
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
  env -u QA_TEST_EMAIL -u QA_TEST_PASSWORD -u QA_LOGIN_ACCEPTANCE_EMAIL -u QA_LOGIN_ACCEPTANCE_PASSWORD -u QA_LOGIN_EMAIL -u QA_LOGIN_PASSWORD \
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

# One terminal decision before either exit (artifact upload, Stamp evidence + verdict).
node scripts/qa/release_settings_terminal.mjs --summary "$work/summary.json" --rc "$rc" --evidence qa-evidence
exit 0
