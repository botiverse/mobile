#!/usr/bin/env bash
# Select full Xcode for this Actions job, without changing xcode-select globally.
set -euo pipefail
trusted_root=/Applications
app="${XCODE_APP_PATH:-}"
if [ -n "$app" ]; then
  developer="$app/Contents/Developer"
elif [ -n "${DEVELOPER_DIR:-}" ] && [ -x "$DEVELOPER_DIR/usr/bin/xcodebuild" ]; then
  developer="$DEVELOPER_DIR"
elif [ -x "$trusted_root"/Xcode.app/Contents/Developer/usr/bin/xcodebuild ]; then
  developer="$trusted_root"/Xcode.app/Contents/Developer
else
  shopt -s nullglob
  candidates=()
  for path in "$trusted_root"/Xcode*.app/Contents/Developer; do
    [ ! -x "$path/usr/bin/xcodebuild" ] || candidates+=("$path")
  done
  if [ "${#candidates[@]}" -ne 1 ]; then
    echo '::error::Full Xcode is missing or ambiguous. Install Xcode from Apple, finish its initial setup, or pass xcode_app_path to the bootstrap workflow.' >&2
    exit 1
  fi
  developer="${candidates[0]}"
fi
case "$developer" in
  *$'\n'*|*$'\r'*) echo '::error::Invalid Xcode path' >&2; exit 1 ;;
esac
# Both explicit input and inherited DEVELOPER_DIR must stay in the installed
# applications root. Resolve symlinks before executing any selected binary.
app="${developer%/Contents/Developer}"
app_name="${app##*/}"
if [ "$developer" != "$app/Contents/Developer" ] || [ "${app%/*}" != "$trusted_root" ]; then
  echo '::error::Xcode must be installed directly under /Applications' >&2
  exit 1
fi
case "$app_name" in
  Xcode*.app) ;;
  *) echo '::error::Expected an installed Xcode*.app' >&2; exit 1 ;;
esac
physical="$(cd "$developer" 2>/dev/null && pwd -P)" || {
  echo '::error::Full Xcode developer directory is missing' >&2; exit 1;
}
[ "$physical" = "$developer" ] || {
  echo '::error::Xcode symlink or traversal paths are not accepted' >&2; exit 1;
}
[ -x "$developer/usr/bin/xcodebuild" ] || { echo "::error::No full Xcode at $developer" >&2; exit 1; }
export DEVELOPER_DIR="$developer"
"$developer/usr/bin/xcodebuild" -version
printf 'DEVELOPER_DIR=%s\n' "$developer" >> "${GITHUB_ENV:?}"
