#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

echo "Android UI/theme ownership audit"
echo "repo: $ROOT_DIR"
echo

echo "1) MainActivity Android UI/theme imports"
rg -n \
  "import build\\.raft\\.app\\.ui\\.(theme|components|icons)\\.|import build\\.raft\\.app\\.feature\\..*(Screen|KuiklyScreen)" \
  compose/androidApp/src/main/java/build/raft/app/MainActivity.kt \
  || true

echo
echo "2) Runtime Android product screens still referenced by MainActivity"
rg -n \
  "ThreadScreen\\(|InboxScreen\\(|SettingsHomeScreen\\(|Settings(Account|Billing|Mobile|Server|Storage|About|Diagnostics).*Screen\\(" \
  compose/androidApp/src/main/java/build/raft/app/MainActivity.kt \
  || true

echo
echo "3) Android product theme/component import consumers"
for package in theme components icons; do
  count="$(
    rg -l "build\\.raft\\.app\\.ui\\.$package" compose/androidApp/src/main/java compose/androidApp/src/test/java compose/androidApp/src/androidTest/java 2>/dev/null \
      | wc -l \
      | tr -d ' '
  )"
  echo "ui/$package import consumers: $count"
  rg -l "build\\.raft\\.app\\.ui\\.$package" compose/androidApp/src/main/java compose/androidApp/src/test/java compose/androidApp/src/androidTest/java 2>/dev/null \
    | sed 's#^#  - #' \
    || true
done

echo
echo "4) Files under Android product UI/theme directories"
find compose/androidApp/src/main/java/build/raft/app/ui compose/androidApp/src/main/java/build/raft/app/feature \
  -type f \
  | sort \
  | sed 's#^#  - #'

echo
echo "5) Platform windows outside RaftWindowDialog (CI gate: scripts/check_raft_window_dialog.sh)"
scripts/check_raft_window_dialog.sh || true

echo
echo "Interpretation:"
echo "- Android host-only APIs such as ui/system and Kuikly native render adapters may remain Android-owned."
echo "- Product visual tokens/components/screens should trend to compose/shared/commonMain."
echo "- Every dialog/sheet/modal window opens through designsystem RaftWindowDialog: theme bars, one scrim, back-swipe block."
echo "- A file is not safe to delete until this audit shows no runtime MainActivity/product caller, or the caller has a shared replacement."
