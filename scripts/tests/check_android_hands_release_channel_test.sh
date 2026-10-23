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

if [ "${failures}" -ne 0 ]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all release-channel-guard cases passed"
