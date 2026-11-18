#!/usr/bin/env bash
# emulator-runner runs each workflow line in a new shell. Keep the package
# identity from this APK through installation and launcher startup.
set -euo pipefail

apk=$(find apks -type f -name '*.apk' -print -quit)
if [[ ! -s "$apk" ]]; then
  echo "::error::No acceptance APK found under apks/" >&2
  exit 1
fi

build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
package=$("$build_tools/aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
if [[ -z "$package" ]]; then
  echo "::error::Could not read the acceptance APK's application ID" >&2
  exit 1
fi

# Grant runtime permissions at install so the notification prompt cannot
# interrupt login. Bootstrap starts the app itself after binding the device.
adb install -r -g "$apk"
printf '%s\n' "$package" > qa-app-package.txt
if [[ ${STAMP_BOOTSTRAP_REQUIRED:-false} == true ]]; then
  exit 0
fi

# The input-event tool can exit successfully after injecting zero events when
# a different process has an ANR. Ask Activity Manager to launch this package
# and check its actual completion before the protected login phase starts.
component=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER -p "$package" | tr -d '\r' | tail -1)
if [[ "$component" != "$package/"?* ]]; then
  echo "::error::The installed acceptance app has no unambiguous launcher activity" >&2
  exit 1
fi
if ! launch=$(adb shell am start -W -a android.intent.action.MAIN \
     -c android.intent.category.LAUNCHER -n "$component" | tr -d '\r') ||
   ! grep -Fqx 'Status: ok' <<< "$launch" ||
   ! awk -v prefix="$package/" '$1 == "Activity:" && index($2, prefix) == 1 { found = 1 } END { exit !found }' <<< "$launch"; then
  echo "::error::Android did not confirm startup of the installed acceptance app" >&2
  exit 1
fi
echo "Android confirmed startup of $package"
