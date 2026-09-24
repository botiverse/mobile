#!/usr/bin/env bash
# Select full Xcode for this Actions job, without changing xcode-select globally.
set -euo pipefail
app="${XCODE_APP_PATH:-}"
if [ -n "$app" ]; then
  developer="$app/Contents/Developer"
elif [ -n "${DEVELOPER_DIR:-}" ] && [ -x "$DEVELOPER_DIR/usr/bin/xcodebuild" ]; then
  developer="$DEVELOPER_DIR"
elif [ -x /Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild ]; then
  developer=/Applications/Xcode.app/Contents/Developer
else
  shopt -s nullglob
  candidates=()
  for path in /Applications/Xcode*.app/Contents/Developer; do
    [ ! -x "$path/usr/bin/xcodebuild" ] || candidates+=("$path")
  done
  if [ "${#candidates[@]}" -ne 1 ]; then
    echo '::error::Full Xcode is missing or ambiguous. Install Xcode from Apple, finish its initial setup, or pass xcode_app_path to the bootstrap workflow.' >&2
    exit 1
  fi
  developer="${candidates[0]}"
fi
case "$developer" in
  /*.app/Contents/Developer) ;;
  *) echo '::error::Expected an absolute full Xcode .app path, not CommandLineTools' >&2; exit 1 ;;
esac
case "$developer" in
  *$'\n'*|*$'\r'*) echo '::error::Invalid Xcode path' >&2; exit 1 ;;
esac
[ -x "$developer/usr/bin/xcodebuild" ] || { echo "::error::No full Xcode at $developer" >&2; exit 1; }
export DEVELOPER_DIR="$developer"
"$developer/usr/bin/xcodebuild" -version
printf 'DEVELOPER_DIR=%s\n' "$developer" >> "${GITHUB_ENV:?}"
