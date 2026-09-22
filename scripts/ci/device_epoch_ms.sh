#!/usr/bin/env bash
# Probe-only helper: print the DEVICE clock as epoch milliseconds, plus the
# resolution actually obtained, e.g. "1790000000123 ms" or "1790000000000 s".
# Tries, in order: toybox `date +%s%3N` (13 digits), mksh $EPOCHREALTIME
# (sec.usec), then plain seconds. Never assumes GNU semantics on-device.
set -u
v="$(adb shell 'date -u +%s%3N' 2>/dev/null | tr -d '\r\n')"
if [[ "$v" =~ ^[0-9]{13}$ ]]; then echo "$v ms"; exit 0; fi
v="$(adb shell 'echo $EPOCHREALTIME' 2>/dev/null | tr -d '\r\n')"
if [[ "$v" =~ ^[0-9]+\.[0-9]{3,}$ ]]; then s="${v%%.*}"; f="${v#*.}"; echo "${s}${f:0:3} ms"; exit 0; fi
v="$(adb shell 'date -u +%s' 2>/dev/null | tr -d '\r\n')"
if [[ "$v" =~ ^[0-9]{10}$ ]]; then echo "${v}000 s"; exit 0; fi
echo "unknown none"
