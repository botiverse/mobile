#!/usr/bin/env bash
set -euo pipefail

NOTES_FILE="${SLOCK_ADB_DEVICE_NOTES:-${HOME}/.slock-adb-device-notes.tsv}"

usage() {
  cat <<'USAGE'
Usage: scripts/adb_devices_with_notes.sh [--notes-file <path>]

Prints `adb devices -l` with an optional local note column.

The notes file is machine-local and must not be committed. Default:
  ~/.slock-adb-device-notes.tsv

Format:
  <adb-serial><TAB><note>

Example:
  121.43.39.134:155	Android-Developer-4 dedicated CloudPhone
  emulator-5554	Local emulator

Override:
  SLOCK_ADB_DEVICE_NOTES=/path/to/notes.tsv scripts/adb_devices_with_notes.sh
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --notes-file)
      [ "$#" -ge 2 ] || {
        echo "error: --notes-file requires a path" >&2
        exit 64
      }
      NOTES_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      exit 64
      ;;
  esac
done

declare -A NOTES=()
if [ -f "${NOTES_FILE}" ]; then
  while IFS=$'\t' read -r serial note || [ -n "${serial:-}" ]; do
    [ -n "${serial}" ] || continue
    case "${serial}" in
      \#*) continue ;;
    esac
    NOTES["${serial}"]="${note:-}"
  done <"${NOTES_FILE}"
fi

printf '%-24s %-10s %s\n' "SERIAL" "STATE" "NOTE"
adb devices -l | tail -n +2 | while IFS= read -r line; do
  [ -n "${line}" ] || continue
  serial="$(printf '%s\n' "${line}" | awk '{print $1}')"
  state="$(printf '%s\n' "${line}" | awk '{print $2}')"
  note="${NOTES[${serial}]:-}"
  printf '%-24s %-10s %s\n' "${serial}" "${state}" "${note}"
done

if [ ! -f "${NOTES_FILE}" ]; then
  cat >&2 <<EOF

No notes file found at ${NOTES_FILE}.
Create it with tab-separated rows, for example:
  121.43.39.134:155	Android-Developer-4 dedicated CloudPhone
EOF
fi

