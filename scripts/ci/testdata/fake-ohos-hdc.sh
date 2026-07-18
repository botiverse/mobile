#!/usr/bin/env bash

set -euo pipefail

printf '%s\n' "$*" >>"$FAKE_HDC_LOG"
[ "$1" = "-p" ]
[ "$2" = "-t" ]
[ "$3" = "$OHOS_SERIAL" ]
shift 3

if [ "$1" = "shell" ] && [ "$2" = "cat" ]; then
  printf '%s\n' "$FAKE_MARKER"
elif [ "$1" = "shell" ] && [ "$2" = "param" ] && [ "$3" = "get" ]; then
  case "$4" in
    const.product.model) printf '%s\n' "$OHOS_EXPECT_MODEL" ;;
    const.ohos.apiversion) printf '%s\n' "$OHOS_EXPECT_API" ;;
    const.product.software.version) printf '%s\n' '6.1.0.115(SP11DEVC00E115R4P11)' ;;
    *) exit 1 ;;
  esac
elif [ "$1" = "shell" ]; then
  printf 'fake-shell-ok\n'
else
  printf 'fake-hdc-ok\n'
fi
