#!/usr/bin/env bash
# Builds the QA capture browser APK from this directory with the Android SDK's own tools
# (aapt2, javac, d8, zipalign, apksigner): no Gradle, nothing shared with the app build.
# Signed with a throwaway key generated per build. Usage: build.sh <out.apk>
set -euo pipefail
OUT="${1:?usage: build.sh <out.apk>}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "${SDK}" ] || { echo "ANDROID_HOME is not set" >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BT="$(ls -d "${SDK}"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "${SDK}"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="${PLATFORM}/android.jar"
[ -f "${ANDROID_JAR}" ] || { echo "no android.jar under ${SDK}/platforms" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

"${BT}/aapt2" link -o "${WORK}/base.apk" --manifest "${HERE}/AndroidManifest.xml" -I "${ANDROID_JAR}" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 1 --version-name 1.0
mkdir -p "${WORK}/classes" "${WORK}/dex"
javac --release 11 -classpath "${ANDROID_JAR}" -d "${WORK}/classes" \
  "${HERE}/src/build/qa/capturebrowser/CaptureActivity.java"
"${BT}/d8" --min-api 24 --lib "${ANDROID_JAR}" --output "${WORK}/dex" $(find "${WORK}/classes" -name '*.class')
(cd "${WORK}/dex" && zip -q -j "${WORK}/base.apk" classes.dex)
"${BT}/zipalign" -f 4 "${WORK}/base.apk" "${WORK}/aligned.apk"
keytool -genkeypair -keystore "${WORK}/qa.jks" -storepass qa-capture -keypass qa-capture -alias qa \
  -keyalg RSA -keysize 2048 -validity 2 -dname "CN=QA capture browser" >/dev/null 2>&1
mkdir -p "$(dirname "${OUT}")"
"${BT}/apksigner" sign --ks "${WORK}/qa.jks" --ks-pass pass:qa-capture --key-pass pass:qa-capture \
  --out "${OUT}" "${WORK}/aligned.apk"
"${BT}/apksigner" verify "${OUT}"
echo "capture browser: ${OUT}"
