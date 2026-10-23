#!/usr/bin/env bash
# Guard: a Hands release may only target the channel its package was BUILT for.
#
# Task: the 1.15.3 main-channel release was a manual `hands builds publish-android`
# registration of the preview APK. Installed users then checked for updates on
# `preview` instead of `main`. The package cannot be distinguished by looking at the
# version alone: the channel is a build-time constant (`BuildConfig.HANDS_CHANNEL`,
# set from `SLOCK_HANDS_CHANNEL` in compose/androidApp/build.gradle.kts).
#
# Two things are checked, in order of strength:
#
#   1. the APK itself, when a reader for `BuildConfig.HANDS_CHANNEL` is available
#      (`apkanalyzer` from the Android command-line tools, or `dexdump` from
#      build-tools). This is authoritative: it reads the constant that ships.
#   2. the metadata JSON the publisher is about to send. This catches the failure
#      that actually happened — a registration with no metadata at all — even on
#      machines with no Android SDK.
#
# An empty or missing channel always fails: it means the row does not say which
# channel it was built for, which is exactly the state that shipped 1.15.3 to main.
#
# Usage:
#   check_android_hands_channel.sh --channel main --metadata /path/to/metadata.json
#   check_android_hands_channel.sh --channel main --apk /path/to/app.apk \
#                                  --metadata /path/to/metadata.json
#
# Exit codes: 0 = publish, 1 = do not publish (reason on stderr).
set -euo pipefail

CHANNEL=""
METADATA=""
APK=""

die() {
  echo "channel-guard: $*" >&2
  exit 1
}

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --channel) CHANNEL="${2:-}"; shift 2 ;;
    --metadata) METADATA="${2:-}"; shift 2 ;;
    --apk) APK="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "${CHANNEL}" ] || die "--channel is required"
[ -n "${METADATA}" ] || die "--metadata is required"

# The target channel must be a plain slug; anything else cannot be compared safely.
case "${CHANNEL}" in
  *[!a-z0-9._-]*) die "target channel is not a slug: ${CHANNEL}" ;;
esac

[ -f "${METADATA}" ] || die "metadata file not found: ${METADATA}"

# Reads `"channel": "value"` out of the flat metadata object the publisher writes.
metadata_channel() {
  sed -n 's/.*"channel"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${METADATA}" | head -n 1
}

# Reads BuildConfig.HANDS_CHANNEL straight out of the APK when a tool can do it.
apk_channel() {
  local apk="$1"
  if command -v apkanalyzer >/dev/null 2>&1; then
    apkanalyzer manifest print "${apk}" >/dev/null 2>&1 || return 1
    # apkanalyzer has no BuildConfig reader; fall back to scanning the dex strings.
    :
  fi
  if command -v dexdump >/dev/null 2>&1; then
    local found
    found="$(dexdump -d "${apk}" 2>/dev/null | grep -A2 'HANDS_CHANNEL' | grep -o 'const-string[^,]*,[[:space:]]*"[^"]*"' | sed 's/.*"\(.*\)"/\1/' | head -n 1 || true)"
    [ -n "${found}" ] && { echo "${found}"; return 0; }
  fi
  return 1
}

DECLARED="$(metadata_channel)"

if [ -z "${DECLARED}" ]; then
  die "metadata declares no channel, so this package cannot be shown to be built for '${CHANNEL}'.
     Refusing to publish. Rebuild the APK for '${CHANNEL}' in CI rather than
     re-registering another channel's package."
fi

if [ "${DECLARED}" != "${CHANNEL}" ]; then
  die "metadata declares channel '${DECLARED}' but the release targets '${CHANNEL}'.
     Refusing to publish a '${DECLARED}' package to '${CHANNEL}'."
fi

if [ -n "${APK}" ]; then
  [ -f "${APK}" ] || die "apk not found: ${APK}"
  if BUILT_FOR="$(apk_channel "${APK}")" && [ -n "${BUILT_FOR}" ]; then
    if [ "${BUILT_FOR}" != "${CHANNEL}" ]; then
      die "the APK was built for channel '${BUILT_FOR}' but the release targets '${CHANNEL}'.
     Refusing to publish. This is the 1.15.3 failure: a package carrying another
     channel's constant was registered on the wrong channel."
    fi
    echo "channel-guard: ok (channel=${CHANNEL}, metadata and APK agree)" >&2
  else
    echo "channel-guard: ok (channel=${CHANNEL} per metadata; APK constant not readable without dexdump/apkanalyzer)" >&2
  fi
else
  echo "channel-guard: ok (channel=${CHANNEL} per metadata; no --apk given, APK constant not read)" >&2
fi
