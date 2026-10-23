#!/usr/bin/env bash
# Self-test for check_android_hands_channel.sh. Runs without a network or an Android SDK.
#
# Each case asserts BOTH the exit code and a distinguishing phrase from the message, so a
# guard that fails for the wrong reason does not look like a pass. (Mutation-checked: with
# the empty-channel branch removed, the empty cases still exit 1 via the mismatch branch —
# only the message separates them, so the message is what the test has to pin.)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="${SCRIPT_DIR}/../check_android_hands_channel.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0

# expect <exit> <name> <channel> <metadata body> [required phrase in the message]
expect() {
  local want="$1" name="$2" channel="$3" body="$4" phrase="${5:-}"
  printf '%s\n' "${body}" > "${WORK}/${name}.json"
  local got=0
  "${GUARD}" --channel "${channel}" --metadata "${WORK}/${name}.json" >"${WORK}/${name}.out" 2>&1 || got=$?
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

# The failure this guard exists for: 1.15.3 registered the preview APK on main, and the
# registration carried no channel at all.
expect 1 no-channel-field 'main' '{"app_slug":"raft-android"}' 'declares no channel'
expect 1 null-channel 'main' '{"channel":null}' 'declares no channel'
expect 1 empty-channel 'main' '{"channel":""}' 'declares no channel'

# A package built for another channel must not be republished to this one.
expect 1 preview-to-main 'main' '{"channel":"preview"}' "declares channel 'preview'"
expect 1 main-to-preview 'preview' '{"channel":"main"}' "declares channel 'main'"

# The ordinary case.
expect 0 main-to-main 'main' '{"channel":"main"}'
expect 0 preview-to-preview 'preview' '{"channel":"preview"}'

# Channel names that would make the comparison meaningless are rejected outright.
expect 1 channel-with-space 'ma in' '{"channel":"main"}' 'not a slug'

# Missing arguments are errors, not silent passes.
got=0
"${GUARD}" --metadata "${WORK}/main-to-main.json" >/dev/null 2>&1 || got=$?
if [ "${got}" = "1" ]; then
  echo "ok   missing-channel (exit 1)"
else
  echo "FAIL missing-channel: expected exit 1, got ${got}" >&2
  failures=$((failures + 1))
fi

if [ "${failures}" -ne 0 ]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all channel-guard cases passed"
