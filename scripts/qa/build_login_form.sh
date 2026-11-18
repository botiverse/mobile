#!/usr/bin/env bash
# Compile only the on-device accessibility driver. This step never receives the QA account.
set -euo pipefail
platform="${ANDROID_HOME:?}/platforms/android-34"
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
libs="$platform/uiautomator.jar:$platform/optional/android.test.base.jar"
javac --release 8 -classpath "$platform/android.jar:$libs" -d "$classes" scripts/qa/android/RaftQaLoginForm.java
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 34 --lib "$platform/android.jar" \
  --classpath "$platform/uiautomator.jar" --classpath "$platform/optional/android.test.base.jar" \
  --output "${1:?output jar required}" "$classes"/*.class
