#!/usr/bin/env bash
# Self-test for check_android_hands_release_channel.sh.
#
# Runs offline against recorded Hands responses. Each case asserts BOTH the exit code and
# a distinguishing phrase, because "no channel recorded" and "wrong channel" both exit 1 —
# an exit-code-only test would stay green if either branch were deleted.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="${SCRIPT_DIR}/../check_android_hands_release_channel.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
APP="a78f3891-f980-4f48-80d7-2a58e93a99aa"
BUILD="52a83d57-3efb-4193-994a-0c7e2ee82c59"

# A Hands build response, with `build_metadata_json` nested and escaped exactly as the
# real endpoint returns it.
hands_body() {
  local channel_json="$1"
  if [ "${channel_json}" = "__absent__" ]; then
    printf '{"id":"%s","channel":"main","build_metadata_json":"{\\"app_slug\\": \\"raft-android\\"}"}' "${BUILD}"
  else
    printf '{"id":"%s","channel":"main","build_metadata_json":"{\\"app_slug\\": \\"raft-android\\", \\"channel\\": \\"%s\\"}"}' \
      "${BUILD}" "${channel_json}"
  fi
}

# expect <exit> <name> <target channel> <recorded channel or __absent__> [phrase]
expect() {
  local want="$1" name="$2" channel="$3" recorded="$4" phrase="${5:-}"
  hands_body "${recorded}" > "${WORK}/${name}.json"
  local got=0
  "${GUARD}" --app "${APP}" --build "${BUILD}" --channel "${channel}" \
    --hands-json "${WORK}/${name}.json" >"${WORK}/${name}.out" 2>&1 || got=$?
  local bad=0
  if [ "${got}" != "${want}" ]; then
    echo "FAIL ${name}: expected exit ${want}, got ${got}" >&2
    bad=1
  fi
  if [ -n "${phrase}" ] && ! grep -qF -- "${phrase}" "${WORK}/${name}.out"; then
    echo "FAIL ${name}: message does not contain '${phrase}'" >&2
    bad=1
  fi
  if [ "${bad}" = "1" ]; then
    sed 's/^/    /' "${WORK}/${name}.out" >&2
    failures=$((failures + 1))
  else
    echo "ok   ${name} (exit ${got})"
  fi
}

# The counterexample this guard exists for: the build Hands recorded for 1.15.3 on main.
# Its release row says `main`; the build's own metadata carries no channel at all.
expect 1 one-fifteen-three-no-metadata 'main' '__absent__' 'records no channel'

# A package built for a different channel must not be published to this one.
expect 1 preview-to-main 'main' 'preview' "was built for channel 'preview'"
expect 1 main-to-preview 'preview' 'main' "was built for channel 'main'"

# The ordinary case, and an uninteresting-but-valid one.
expect 0 main-to-main 'main' 'main' 'recorded channel=main'
expect 0 preview-to-preview 'preview' 'preview' 'recorded channel=preview'

# Nonsense targets are rejected before any comparison happens.
expect 1 channel-with-space 'ma in' 'main' 'not a slug'

# Missing required arguments are errors, not silent passes.
got=0
"${GUARD}" --app "${APP}" --build "${BUILD}" >/dev/null 2>&1 || got=$?
if [ "${got}" = "1" ]; then
  echo "ok   missing-channel (exit 1)"
else
  echo "FAIL missing-channel: expected exit 1, got ${got}" >&2
  failures=$((failures + 1))
fi

# An unreadable Hands response must fail closed, not pass.
printf 'not json at all' > "${WORK}/garbage.json"
got=0
"${GUARD}" --app "${APP}" --build "${BUILD}" --channel main \
  --hands-json "${WORK}/garbage.json" >/dev/null 2>&1 || got=$?
if [ "${got}" = "1" ]; then
  echo "ok   unparseable-body (exit 1)"
else
  echo "FAIL unparseable-body: expected exit 1, got ${got}" >&2
  failures=$((failures + 1))
fi

# With neither a local CLI nor npm, the guard must fail closed rather than skip the check.
got=0
env HANDS_CLI=definitely-not-a-real-binary PATH=/usr/bin:/bin \
  "${GUARD}" --app "${APP}" --build "${BUILD}" --channel main >/dev/null 2>&1 || got=$?
if [ "${got}" = "1" ]; then
  echo "ok   no-cli-and-no-npm (exit 1)"
else
  echo "FAIL no-cli-and-no-npm: expected exit 1, got ${got}" >&2
  failures=$((failures + 1))
fi

# The npm fallback must not die for want of a local binary: with a recorded response it
# runs end to end. The live API path is NOT tested here — CI has no Hands credentials, so
# an authenticated call would fail for a reason unrelated to this script (that is exactly
# how the first version of this test broke the workflow).
if command -v npm >/dev/null 2>&1; then
  hands_body main > "${WORK}/npm-recorded.json"
  got=0
  env HANDS_CLI=definitely-not-a-real-binary \
    "${GUARD}" --app "${APP}" --build "${BUILD}" --channel main \
    --hands-json "${WORK}/npm-recorded.json" >"${WORK}/npm.out" 2>&1 || got=$?
  if [ "${got}" = "0" ]; then
    echo "ok   no-local-cli-with-recorded-response (exit 0)"
  else
    echo "FAIL no-local-cli-with-recorded-response: expected exit 0, got ${got}" >&2
    sed 's/^/    /' "${WORK}/npm.out" >&2
    failures=$((failures + 1))
  fi
else
  echo "skip no-local-cli-with-recorded-response (npm not installed)"
fi

# The npm fallback itself is proven by resolution, not by an authenticated API call:
# resolving the pinned package is what a credential-less machine needs to get past.
if command -v npm >/dev/null 2>&1; then
  got=0
  npm exec --yes --registry "${HANDS_NPM_REGISTRY:-https://registry.npmjs.org}" \
    --package "${HANDS_CLI_PACKAGE:-@botiverse/hands-cli@0.5.32}" -- hands --version \
    >"${WORK}/npmver.out" 2>&1 || got=$?
  if [ "${got}" = "0" ] && grep -qE "^[0-9]+\.[0-9]+\.[0-9]+$" "${WORK}/npmver.out"; then
    echo "ok   npm-resolves-pinned-package ($(tail -1 "${WORK}/npmver.out"))"
  elif grep -qiE "ENOTFOUND|EAI_AGAIN|network|403|404" "${WORK}/npmver.out" 2>/dev/null; then
    echo "skip npm-resolves-pinned-package (registry unavailable: $(tail -1 "${WORK}/npmver.out" | cut -c1-60))"
  else
    echo "FAIL npm-resolves-pinned-package: exit ${got}" >&2
    sed 's/^/    /' "${WORK}/npmver.out" >&2
    failures=$((failures + 1))
  fi
else
  echo "skip npm-resolves-pinned-package (npm not installed)"
fi

if [ "${failures}" -ne 0 ]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all release-channel-guard cases passed"
