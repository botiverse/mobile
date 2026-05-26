#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "Android UI/theme ownership audit"
echo "repo: $ROOT_DIR"
echo

echo "1) MainActivity Android UI/theme imports"
rg -n \
  "import ai\\.slock\\.android\\.ui\\.(theme|components|icons)\\.|import ai\\.slock\\.android\\.feature\\..*(Screen|KuiklyScreen)" \
  app/src/main/java/ai/slock/android/MainActivity.kt \
  || true

echo
echo "2) Runtime Android product screens still referenced by MainActivity"
rg -n \
  "ThreadScreen\\(|InboxScreen\\(|SettingsHomeScreen\\(|Settings(Account|Billing|Mobile|Server|Storage|About|Diagnostics).*Screen\\(" \
  app/src/main/java/ai/slock/android/MainActivity.kt \
  || true

echo
echo "3) Android product theme/component import consumers"
for package in theme components icons; do
  count="$(
    rg -l "ai\\.slock\\.android\\.ui\\.$package" app/src/main/java app/src/test/java app/src/androidTest/java 2>/dev/null \
      | wc -l \
      | tr -d ' '
  )"
  echo "ui/$package import consumers: $count"
  rg -l "ai\\.slock\\.android\\.ui\\.$package" app/src/main/java app/src/test/java app/src/androidTest/java 2>/dev/null \
    | sed 's#^#  - #' \
    || true
done

echo
echo "4) Files under Android product UI/theme directories"
find app/src/main/java/ai/slock/android/ui app/src/main/java/ai/slock/android/feature \
  -type f \
  | sort \
  | sed 's#^#  - #'

echo
echo "Interpretation:"
echo "- Android host-only APIs such as ui/system and Kuikly native render adapters may remain Android-owned."
echo "- Product visual tokens/components/screens should trend to compose/shared/commonMain."
echo "- A file is not safe to delete until this audit shows no runtime MainActivity/product caller, or the caller has a shared replacement."
