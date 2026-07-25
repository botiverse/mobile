#!/usr/bin/env bash
set -euo pipefail

canonical_raw="${1:-}"
legacy_raw="${2:-}"
default_raw="${3-false}"

die() {
  echo "error: $*" >&2
  exit 64
}

normalize_optional_bool() {
  local name="$1"
  local raw="$2"
  local value

  value="$(printf '%s' "${raw}" | tr '[:upper:]' '[:lower:]' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  case "${value}" in
    "") printf '\n' ;;
    true|1) printf 'true\n' ;;
    false|0) printf 'false\n' ;;
    *) die "${name} must be true or false when set; got: ${raw}" ;;
  esac
}

canonical="$(normalize_optional_bool publish_hands "${canonical_raw}")"
legacy="$(normalize_optional_bool publish_quiver "${legacy_raw}")"
default_value="$(normalize_optional_bool default "${default_raw}")"
[ -n "${default_value}" ] || die "default must be true or false"

if [ -n "${canonical}" ] && [ -n "${legacy}" ] && [ "${canonical}" != "${legacy}" ]; then
  die "publish_hands and deprecated publish_quiver disagree; refusing to choose a publish action"
fi

if [ -n "${legacy}" ]; then
  echo "warning: publish_quiver is deprecated; migrate the caller to publish_hands before 2026-08-31" >&2
fi

if [ -n "${canonical}" ]; then
  printf '%s\n' "${canonical}"
elif [ -n "${legacy}" ]; then
  printf '%s\n' "${legacy}"
else
  printf '%s\n' "${default_value}"
fi
