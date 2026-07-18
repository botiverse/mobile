#!/usr/bin/env bash

set -euo pipefail

HDC_REAL="${HDC_REAL:-/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc}"
SLOT_MARKER_PATH="${OHOS_SLOT_MARKER_PATH:-/data/local/tmp/raft-runner-slot-id}"
LOCK_ROOT="${OHOS_SLOT_LOCK_ROOT:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/raft-ohos-runner-locks}"

die() {
  printf 'ohos-slot-guard: %s\n' "$*" >&2
  exit 64
}

require_env() {
  local name="$1"
  [ -n "${!name:-}" ] || die "required environment variable is missing: $name"
}

validate_contract_values() {
  [[ "$OHOS_SLOT_ID" =~ ^[a-z0-9][a-z0-9._-]*$ ]] ||
    die "OHOS_SLOT_ID contains unsupported characters: $OHOS_SLOT_ID"
  [[ "$OHOS_INSTANCE_UUID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    die "OHOS_INSTANCE_UUID contains unsupported characters: $OHOS_INSTANCE_UUID"
  [[ "$OHOS_SERIAL" =~ ^127\.0\.0\.1:([0-9]+)$ ]] ||
    die "OHOS_SERIAL must be an explicit loopback TCP endpoint, got: $OHOS_SERIAL"

  local port="${BASH_REMATCH[1]}"
  ((port >= 10000 && port <= 16555)) ||
    die "OHOS_SERIAL port must be in the DevEco emulator range 10000-16555, got: $port"
}

require_contract() {
  require_env OHOS_EXPECT_HOST_USER
  require_env OHOS_SLOT_ID
  require_env OHOS_SERIAL
  require_env OHOS_INSTANCE_UUID
  require_env OHOS_EXPECT_MODEL
  require_env OHOS_EXPECT_API
  [ -x "$HDC_REAL" ] || die "hdc is not executable: $HDC_REAL"
  validate_contract_values

  local host_user
  host_user="$(id -un)"
  [ "$host_user" = "$OHOS_EXPECT_HOST_USER" ] ||
    die "host user mismatch: expected=$OHOS_EXPECT_HOST_USER actual=$host_user"
}

direct_hdc() {
  "$HDC_REAL" -p -t "$OHOS_SERIAL" "$@"
}

read_param() {
  direct_hdc shell param get "$1" | tr -d '\r\n' | sed 's/[[:space:]]*$//'
}

expected_marker() {
  printf '%s|%s' "$OHOS_SLOT_ID" "$OHOS_INSTANCE_UUID"
}

verify_slot() {
  require_contract

  local marker model api software
  marker="$(direct_hdc shell cat "$SLOT_MARKER_PATH" 2>/dev/null | tr -d '\r\n' | sed 's/[[:space:]]*$//')"
  model="$(read_param const.product.model)"
  api="$(read_param const.ohos.apiversion)"
  software="$(read_param const.product.software.version)"

  [ "$marker" = "$(expected_marker)" ] ||
    die "slot marker mismatch: slot=$OHOS_SLOT_ID serial=$OHOS_SERIAL expected=$(expected_marker) actual=${marker:-<missing>}"
  [ "$model" = "$OHOS_EXPECT_MODEL" ] ||
    die "model mismatch: expected=$OHOS_EXPECT_MODEL actual=$model"
  [ "$api" = "$OHOS_EXPECT_API" ] ||
    die "API mismatch: expected=$OHOS_EXPECT_API actual=$api"

  if [ -n "${OHOS_EXPECT_SOFTWARE_CONTAINS:-}" ]; then
    case "$software" in
      *"$OHOS_EXPECT_SOFTWARE_CONTAINS"*) ;;
      *) die "software mismatch: expected to contain '$OHOS_EXPECT_SOFTWARE_CONTAINS', actual=$software" ;;
    esac
  fi

  printf 'host_user=%s slot=%s serial=%s marker=%s model=%s api=%s software=%s\n' \
    "$OHOS_EXPECT_HOST_USER" "$OHOS_SLOT_ID" "$OHOS_SERIAL" "$marker" "$model" "$api" "$software"
}

lock_dir() {
  printf '%s/%s.lock' "$LOCK_ROOT" "$OHOS_SLOT_ID"
}

lock_owner() {
  if [ -n "${OHOS_SLOT_LOCK_OWNER:-}" ]; then
    printf '%s' "$OHOS_SLOT_LOCK_OWNER"
  else
    printf '%s:%s:%s' \
      "${GITHUB_RUN_ID:-manual}" \
      "${GITHUB_RUN_ATTEMPT:-1}" \
      "${GITHUB_JOB:-manual}"
  fi
}

acquire_slot() {
  verify_slot >/dev/null
  mkdir -p "$LOCK_ROOT"

  local dir owner
  dir="$(lock_dir)"
  owner="$(lock_owner)"
  if ! mkdir "$dir" 2>/dev/null; then
    die "slot is already locked: $dir"
  fi
  printf '%s\n' "$owner" >"$dir/owner"
  printf 'acquired slot=%s lock=%s owner=%s\n' "$OHOS_SLOT_ID" "$dir" "$owner"
}

release_slot() {
  require_env OHOS_SLOT_ID

  local dir expected actual
  dir="$(lock_dir)"
  expected="$(lock_owner)"
  if [ ! -d "$dir" ]; then
    printf 'slot=%s lock=%s already-released\n' "$OHOS_SLOT_ID" "$dir"
    return
  fi

  actual="$(cat "$dir/owner" 2>/dev/null || true)"
  [ "$actual" = "$expected" ] ||
    die "refusing to release lock owned by another job: lock=$dir expected=$expected actual=${actual:-<missing>}"
  rm -f "$dir/owner"
  rmdir "$dir"
  printf 'released slot=%s lock=%s owner=%s\n' "$OHOS_SLOT_ID" "$dir" "$expected"
}

provision_marker() {
  require_contract
  [ "${ALLOW_SLOT_PROVISION:-0}" = "1" ] ||
    die "marker provisioning requires ALLOW_SLOT_PROVISION=1"
  direct_hdc shell "printf '%s' '$(expected_marker)' > '$SLOT_MARKER_PATH'"
  verify_slot
}

usage() {
  cat <<'USAGE'
Usage: ohos-slot-guard.sh verify
       ohos-slot-guard.sh acquire
       ohos-slot-guard.sh release
       ohos-slot-guard.sh provision-marker
       ohos-slot-guard.sh hdc <hdc command...>

All device commands run through direct single-client HDC:
  hdc -p -t "$OHOS_SERIAL" ...

The guard requires a fixed DevEco emulator endpoint (port 10000-16555) and
verifies the device-resident marker plus model/API/software before executing
install, shell, file transfer, UI input, or cleanup commands.
USAGE
}

command="${1:-}"
case "$command" in
  verify)
    verify_slot
    ;;
  acquire)
    acquire_slot
    ;;
  release)
    release_slot
    ;;
  provision-marker)
    provision_marker
    ;;
  hdc)
    shift
    [ "$#" -gt 0 ] || die "hdc subcommand requires arguments"
    verify_slot >/dev/null
    direct_hdc "$@"
    ;;
  -h | --help | help | "")
    usage
    ;;
  *)
    die "unknown command: $command"
    ;;
esac
