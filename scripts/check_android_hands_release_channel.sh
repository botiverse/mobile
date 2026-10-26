#!/usr/bin/env bash
# Guard: before publishing a Hands release, check that the build actually behind it was
# built for that channel.
#
# Task: 1.15.3 was published to `main` by manually registering the **preview** APK with
# `hands builds publish-android`. The channel is a compile-time constant
# (`BuildConfig.HANDS_CHANNEL`, from `SLOCK_HANDS_CHANNEL`), so every installed 1.15.3
# client then checked `preview` for updates.
#
# Why this asks Hands instead of reading the metadata we are about to send:
# the publisher writes `"channel"` into the metadata from the same `${CHANNEL}` it would
# be comparing against, so a local comparison is a tautology and blocks nothing. The
# build's recorded metadata, on the other hand, is written by the **packaging** step and
# is therefore independent of the release target. For 1.15.3 the two disagreed:
# the release says `main`, the build's metadata says nothing at all.
#
# The APK itself is not used: release builds run R8, which inlines `HANDS_CHANNEL` and
# drops the field, so a dex scan would find nothing and pass for the wrong reason.
#
# Usage:
#   check_android_hands_release_channel.sh --app <app-uuid> --build <build-uuid> --channel main
#   check_android_hands_release_channel.sh --app <app-uuid> --build <build-uuid> --channel main \
#                                          --hands-json recorded.json      # offline / for tests
#
# Exit codes: 0 = publish, 1 = do not publish (reason on stderr).
set -euo pipefail

APP=""
BUILD=""
CHANNEL=""
HANDS_JSON=""
# Same CLI selection as finalize_android_hands_release.sh, which exports these: the
# pinned npm package unless HANDS_CLI names a local binary.
HANDS_CLI="${HANDS_CLI:-}"
HANDS_CLI_PACKAGE="${HANDS_CLI_PACKAGE:-@botiverse/hands-cli@0.5.32}"
HANDS_NPM_REGISTRY="${HANDS_NPM_REGISTRY:-https://registry.npmjs.org/}"

die() {
  echo "release-channel-guard: $*" >&2
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --app) APP="${2:-}"; shift 2 ;;
    --build) BUILD="${2:-}"; shift 2 ;;
    --channel) CHANNEL="${2:-}"; shift 2 ;;
    --hands-json) HANDS_JSON="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 2
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "${APP}" ] || die "--app is required"
[ -n "${BUILD}" ] || die "--build is required"
[ -n "${CHANNEL}" ] || die "--channel is required"

case "${CHANNEL}" in
  *[!a-z0-9._-]*) die "target channel is not a slug: ${CHANNEL}" ;;
esac

if [ -n "${HANDS_JSON}" ]; then
  [ -f "${HANDS_JSON}" ] || die "hands json not found: ${HANDS_JSON}"
  BODY="$(cat "${HANDS_JSON}")"
else
  if [ -n "${HANDS_CLI}" ]; then
    command -v "${HANDS_CLI}" >/dev/null 2>&1 ||
      die "HANDS_CLI=${HANDS_CLI} not found; pass --hands-json to check a recorded response"
    HANDS=("${HANDS_CLI}")
  else
    command -v npm >/dev/null 2>&1 ||
      die "npm is required to run ${HANDS_CLI_PACKAGE}; pass --hands-json to check a recorded response"
    HANDS=(npm exec --yes --registry "${HANDS_NPM_REGISTRY}" --package "${HANDS_CLI_PACKAGE}" -- hands)
  fi
  BODY="$("${HANDS[@]}" api GET "/api/apps/${APP}/builds/${BUILD}" --json 2>/dev/null)" ||
    die "could not read build ${BUILD} from Hands"
fi

[ -n "${BODY}" ] || die "Hands returned an empty body for build ${BUILD}"

# `build_metadata_json` is a JSON *string* nested inside the response. Parse both layers
# with a real JSON reader rather than pattern-matching the escaped text: the outer object
# also carries its own `channel`, and a regex can pick the wrong one.
recorded_channel() {
  printf '%s' "${BODY}" | python3 -c '
import json
import sys

try:
    response = json.load(sys.stdin)
except Exception:
    raise SystemExit(0)
raw = response.get("build_metadata_json") if isinstance(response, dict) else None
if isinstance(raw, str):
    try:
        raw = json.loads(raw)
    except Exception:
        raise SystemExit(0)
if isinstance(raw, dict):
    value = raw.get("channel")
    if isinstance(value, str):
        print(value)
'
}

RECORDED="$(recorded_channel)"

if [ -z "${RECORDED}" ]; then
  die "build ${BUILD} records no channel in its metadata, so it cannot be shown to have
     been built for '${CHANNEL}'. Refusing to publish.
     1.15.3 reached main this way: a preview package registered by hand, with no
     recorded channel. Rebuild the APK for '${CHANNEL}' instead."
fi

if [ "${RECORDED}" != "${CHANNEL}" ]; then
  die "build ${BUILD} was built for channel '${RECORDED}' but the release targets
     '${CHANNEL}'. Refusing to publish."
fi

echo "release-channel-guard: ok (build ${BUILD} recorded channel=${RECORDED})" >&2
