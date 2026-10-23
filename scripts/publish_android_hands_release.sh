#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HANDS_CLI="${HANDS_CLI:-hands}"
HANDS_CLI_PACKAGE="${HANDS_CLI_PACKAGE:-@botiverse/hands-cli@0.5.4}"
APP_SLUG="${HANDS_APP_SLUG:-raft-android}"
CHANNEL="${HANDS_CHANNEL:-${SLOCK_HANDS_CHANNEL:-main}}"
VARIANT="${SLOCK_ANDROID_VARIANT:-release}"
ARCH="${SLOCK_ANDROID_ARCH:-arm64-v8a}"
VERSION_X="${SLOCK_ANDROID_VERSION_MAJOR:-1}"
VERSION_Y="${SLOCK_ANDROID_VERSION_MINOR:-0}"
VERSION_Z="${SLOCK_ANDROID_VERSION_PATCH:-0}"
VERSION_BUILD="${SLOCK_ANDROID_VERSION_BUILD:-0}"
VERSION_SUFFIX="${SLOCK_ANDROID_VERSION_SUFFIX:-}"
SKIP_GRADLE_BUILD="${SLOCK_SKIP_GRADLE_BUILD:-0}"
COLLECT_SYMBOLS="${SLOCK_COLLECT_ANDROID_SYMBOLS:-1}"
REQUIRE_SYMBOLS="${SLOCK_REQUIRE_ANDROID_SYMBOLS:-0}"
EXTERNAL_SYMBOL_ARCHIVE="${SLOCK_ANDROID_SYMBOL_ARCHIVE:-}"
DRY_RUN="${SLOCK_HANDS_DRY_RUN:-0}"

die() {
  echo "error: $*" >&2
  exit 64
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_size() {
  wc -c <"$1" | tr -d '[:space:]'
}

json_string() {
  python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"
}

run_hands() {
  if [ -f "${HANDS_CLI}" ]; then
    case "${HANDS_CLI}" in
      *.js)
        node "${HANDS_CLI}" "$@"
        ;;
      *)
        "${HANDS_CLI}" "$@"
        ;;
    esac
  else
    if command -v "${HANDS_CLI}" >/dev/null 2>&1; then
      "${HANDS_CLI}" "$@"
    elif command -v npm >/dev/null 2>&1; then
      npm exec --yes --package "${HANDS_CLI_PACKAGE}" -- hands "$@"
    else
      die "Hands CLI not found: ${HANDS_CLI}; install with: npm install -g @botiverse/hands-cli"
    fi
  fi
}

version_name() {
  if [ -n "${SLOCK_ANDROID_VERSION_NAME:-}" ]; then
    printf '%s\n' "${SLOCK_ANDROID_VERSION_NAME}"
  elif [ -n "${VERSION_SUFFIX}" ]; then
    printf '%s.%s.%s-%s\n' "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}" "${VERSION_SUFFIX}"
  else
    printf '%s.%s.%s\n' "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}"
  fi
}

version_code() {
  if [ -n "${SLOCK_ANDROID_VERSION_CODE:-}" ]; then
    printf '%s\n' "${SLOCK_ANDROID_VERSION_CODE}"
  else
    scripts/ci/version_code.sh "${VERSION_X}" "${VERSION_Y}" "${VERSION_Z}" "${VERSION_BUILD}"
  fi
}

abs_path() {
  local path="$1"
  if [ -d "${path}" ]; then
    (cd "${path}" && pwd)
  else
    (cd "$(dirname "${path}")" && printf '%s/%s\n' "$(pwd)" "$(basename "${path}")")
  fi
}

variant_capitalized() {
  python3 -c 'import sys; s=sys.argv[1]; print(s[:1].upper()+s[1:])' "$1"
}

usage() {
  cat <<'USAGE'
Usage: scripts/publish_android_hands_release.sh [options]

Builds a Slock Android APK, collects support artifacts, and publishes an
Android release to Hands.

Environment overrides:
  HANDS_APP_SLUG=raft-android
  HANDS_CHANNEL=main
  HANDS_API=https://hands.build
  HANDS_AUTH_TOKEN=<agent or CI bearer token>
  HANDS_CLI=hands
  HANDS_CLI_PACKAGE=@botiverse/hands-cli@0.5.4
  SLOCK_ANDROID_VERSION_MAJOR=1
  SLOCK_ANDROID_VERSION_MINOR=0
  SLOCK_ANDROID_VERSION_PATCH=0
  SLOCK_ANDROID_VERSION_BUILD=0
  SLOCK_ANDROID_VERSION_SUFFIX=rc1
  SLOCK_ANDROID_VERSION_CODE=1000001
  SLOCK_ANDROID_VERSION_NAME=1.0.0-rc1
  SLOCK_ANDROID_VARIANT=release
  SLOCK_ANDROID_ARCH=arm64-v8a
  SLOCK_SKIP_GRADLE_BUILD=1
  SLOCK_COLLECT_ANDROID_SYMBOLS=0
  SLOCK_REQUIRE_ANDROID_SYMBOLS=1
  SLOCK_ANDROID_SYMBOL_ARCHIVE=/path/to/exact-native-symbols.zip
  SLOCK_RELEASE_CHANGELOG_ZH_FILE=change-logs/1.6.0/android/zh-CN.md
  SLOCK_RELEASE_CHANGELOG_EN_FILE=change-logs/1.6.0/android/en.md
  SLOCK_RELEASE_CHANGELOG_JA_FILE=change-logs/1.8.0/android/ja.md
  SLOCK_RELEASE_CHANGELOG_FILE=changelog.txt
  SLOCK_HANDS_DRY_RUN=1

Options:
  --channel <slug>       Hands channel. Default: $HANDS_CHANNEL or main.
  --app <slug>           Hands app slug. Default: raft-android.
  --variant <name>       Gradle build variant. Default: release.
  --build <n>            Version build component.
  --suffix <value>       Version name suffix.
  --skip-gradle-build    Reuse existing APK outputs.
  --dry-run              Collect and print artifacts without publishing.
  -h, --help             Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --channel)
      [ "$#" -ge 2 ] || die "--channel requires value"
      CHANNEL="$2"
      shift 2
      ;;
    --app)
      [ "$#" -ge 2 ] || die "--app requires value"
      APP_SLUG="$2"
      shift 2
      ;;
    --variant)
      [ "$#" -ge 2 ] || die "--variant requires value"
      VARIANT="$2"
      shift 2
      ;;
    --build)
      [ "$#" -ge 2 ] || die "--build requires value"
      VERSION_BUILD="$2"
      shift 2
      ;;
    --suffix)
      [ "$#" -ge 2 ] || die "--suffix requires value"
      VERSION_SUFFIX="$2"
      shift 2
      ;;
    --skip-gradle-build)
      SKIP_GRADLE_BUILD=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

case "${VARIANT}" in
  release|debug) ;;
  play)
    die "the Hands publisher cannot publish the Play carrier; retain its signed AAB as a Play artifact instead"
    ;;
  *)
    die "Hands variant must be release or debug"
    ;;
esac

VERSION_NAME="$(version_name)"
VERSION_CODE="$(version_code)"
GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse HEAD)"
GIT_SHORT="$(git -C "${ROOT_DIR}" rev-parse --short=8 HEAD)"
GIT_BRANCH="$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD)"
BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
VARIANT_CAP="$(variant_capitalized "${VARIANT}")"
APK_PATH="${ROOT_DIR}/compose/androidApp/build/outputs/apk/${VARIANT}/androidApp-${VARIANT}.apk"
ARTIFACT_DIR="${ROOT_DIR}/artifacts/android-hands/${VERSION_NAME}-${VERSION_CODE}-${GIT_SHORT}"
METADATA_PATH="${ARTIFACT_DIR}/build-metadata.json"

if [ "${SKIP_GRADLE_BUILD}" != "1" ]; then
  GRADLE_ARGS=(
    --no-daemon
    --console=plain
    --stacktrace
    ":androidApp:assemble${VARIANT_CAP}"
    "-PversionX=${VERSION_X}"
    "-PversionY=${VERSION_Y}"
    "-PversionZ=${VERSION_Z}"
    "-PversionBuild=${VERSION_BUILD}"
  )
  if [ -n "${VERSION_SUFFIX}" ]; then
    GRADLE_ARGS+=("-PversionSuffix=${VERSION_SUFFIX}")
  fi
  (
    cd "${ROOT_DIR}/compose"
    ./gradlew "${GRADLE_ARGS[@]}"
  )
fi

[ -f "${APK_PATH}" ] || die "APK not found: ${APK_PATH}"
mkdir -p "${ARTIFACT_DIR}"

SYMBOL_ARCHIVE=""
MAPPING_PATH="${ROOT_DIR}/compose/androidApp/build/outputs/mapping/${VARIANT}/mapping.txt"
if [ -n "${EXTERNAL_SYMBOL_ARCHIVE}" ]; then
  [ -f "${EXTERNAL_SYMBOL_ARCHIVE}" ] || die "native symbol archive not found: ${EXTERNAL_SYMBOL_ARCHIVE}"
  SYMBOL_ARCHIVE="$(abs_path "${EXTERNAL_SYMBOL_ARCHIVE}")"
  "${ROOT_DIR}/scripts/verify_android_native_symbols.sh" \
    --apk "${APK_PATH}" \
    --symbols "${SYMBOL_ARCHIVE}"
elif [ "${COLLECT_SYMBOLS}" = "1" ]; then
  if "${ROOT_DIR}/scripts/prepare_android_hands_symbols.sh" \
      --variant "${VARIANT}" \
      --version "${VERSION_NAME}" \
      --build-no "${VERSION_CODE}" \
      --build-label "${GIT_SHORT}" \
      --output-dir "${ARTIFACT_DIR}/hands-symbols" >/tmp/slock-hands-symbols.log 2>&1; then
    SYMBOL_ARCHIVE="$(find "${ARTIFACT_DIR}/hands-symbols" -maxdepth 1 -name '*.zip' -type f | head -n 1)"
  else
    echo "warning: symbol collection skipped; see /tmp/slock-hands-symbols.log" >&2
  fi
fi
if [ "${REQUIRE_SYMBOLS}" = "1" ] && { [ -z "${SYMBOL_ARCHIVE}" ] || [ ! -f "${SYMBOL_ARCHIVE}" ]; }; then
  die "required exact native symbols are missing"
fi

{
  printf '{\n'
  printf '  "app_slug": %s,\n' "$(json_string "${APP_SLUG}")"
  printf '  "channel": %s,\n' "$(json_string "${CHANNEL}")"
  printf '  "version_name": %s,\n' "$(json_string "${VERSION_NAME}")"
  printf '  "version_code": %s,\n' "${VERSION_CODE}"
  printf '  "variant": %s,\n' "$(json_string "${VARIANT}")"
  printf '  "arch": %s,\n' "$(json_string "${ARCH}")"
  printf '  "source_commit": %s,\n' "$(json_string "${GIT_COMMIT}")"
  printf '  "source_branch": %s,\n' "$(json_string "${GIT_BRANCH}")"
  printf '  "build_time": %s,\n' "$(json_string "${BUILD_TIME}")"
  printf '  "apk": { "path": %s, "sha256": %s, "size_bytes": %s },\n' \
    "$(json_string "$(abs_path "${APK_PATH}")")" \
    "$(json_string "$(sha256_file "${APK_PATH}")")" \
    "$(file_size "${APK_PATH}")"
  if [ -f "${MAPPING_PATH}" ]; then
    printf '  "mapping": { "path": %s, "sha256": %s, "size_bytes": %s },\n' \
      "$(json_string "$(abs_path "${MAPPING_PATH}")")" \
      "$(json_string "$(sha256_file "${MAPPING_PATH}")")" \
      "$(file_size "${MAPPING_PATH}")"
  else
    printf '  "mapping": null,\n'
  fi
  if [ -n "${SYMBOL_ARCHIVE}" ] && [ -f "${SYMBOL_ARCHIVE}" ]; then
    printf '  "native_symbols": { "path": %s, "sha256": %s, "size_bytes": %s }\n' \
      "$(json_string "$(abs_path "${SYMBOL_ARCHIVE}")")" \
      "$(json_string "$(sha256_file "${SYMBOL_ARCHIVE}")")" \
      "$(file_size "${SYMBOL_ARCHIVE}")"
  else
    printf '  "native_symbols": null\n'
  fi
  printf '}\n'
} >"${METADATA_PATH}"

echo "Prepared Hands Android release artifacts:"
echo "  APK:      ${APK_PATH}"
echo "  Metadata: ${METADATA_PATH}"
[ -f "${MAPPING_PATH}" ] && echo "  Mapping:  ${MAPPING_PATH}"
[ -n "${SYMBOL_ARCHIVE}" ] && echo "  Symbols:  ${SYMBOL_ARCHIVE}"

if [ "${DRY_RUN}" = "1" ]; then
  echo "Dry run: not publishing to Hands."
  exit 0
fi


HANDS_ARGS=(
  builds
  publish-android
  "${APP_SLUG}"
  --channel "${CHANNEL}"
  --apk "${APK_PATH}"
  --version-name "${VERSION_NAME}"
  --version-code "${VERSION_CODE}"
  --arch "${ARCH}"
  --source-commit "${GIT_COMMIT}"
  --source-branch "${GIT_BRANCH}"
  --build-time "${BUILD_TIME}"
  --metadata "${METADATA_PATH}"
)
if [ -n "${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ] && [ ! -s "${SLOCK_RELEASE_CHANGELOG_JA_FILE}" ]; then
  die "SLOCK_RELEASE_CHANGELOG_JA_FILE must name a non-empty file"
fi
if [ -n "${SLOCK_RELEASE_CHANGELOG_ZH_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_ZH_FILE}" ] &&
  [ -n "${SLOCK_RELEASE_CHANGELOG_EN_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_EN_FILE}" ]; then
  HANDS_ARGS+=(
    --changelog-file "zh-CN=${SLOCK_RELEASE_CHANGELOG_ZH_FILE}"
    --changelog-file "en=${SLOCK_RELEASE_CHANGELOG_EN_FILE}"
  )
  if [ -n "${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ]; then
    HANDS_ARGS+=(--changelog-file "ja=${SLOCK_RELEASE_CHANGELOG_JA_FILE}")
  fi
elif [ -n "${SLOCK_RELEASE_CHANGELOG_ZH_FILE:-}${SLOCK_RELEASE_CHANGELOG_EN_FILE:-}${SLOCK_RELEASE_CHANGELOG_JA_FILE:-}" ]; then
  die "reviewed changelog variables require non-empty zh-CN and en files"
elif [ -n "${SLOCK_RELEASE_CHANGELOG_FILE:-}" ] && [ -s "${SLOCK_RELEASE_CHANGELOG_FILE}" ]; then
  HANDS_ARGS+=(--changelog-file "${SLOCK_RELEASE_CHANGELOG_FILE}")
fi
if [ "${SLOCK_HANDS_DRAFT:-0}" = "1" ]; then
  HANDS_ARGS+=(--draft)
fi
if [ -f "${MAPPING_PATH}" ]; then
  HANDS_ARGS+=(--mapping "${MAPPING_PATH}")
fi
if [ -n "${SYMBOL_ARCHIVE}" ]; then
  HANDS_ARGS+=(--symbols "${SYMBOL_ARCHIVE}")
fi
# Generate archive-patcher delta patches from the last N published versions so
# clients on recent versions download a small patch instead of the full APK.
# Works for drafts too (CI creates drafts): patches are stored on the new build
# and offered once it's published. CI has a JDK; set SLOCK_DELTA_VERSIONS=0 to
# disable. (Hands CLI >= 0.5.4.)
DELTA_VERSIONS="${SLOCK_DELTA_VERSIONS:-3}"
if [ "${DELTA_VERSIONS}" != "0" ]; then
  HANDS_ARGS+=(--generate-deltas "${DELTA_VERSIONS}")
fi

run_hands "${HANDS_ARGS[@]}"
