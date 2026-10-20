#!/usr/bin/env bash
# Cold-start simpleperf capture for Slock Android (RaftLegacyHostActivity).
# Records native CPU samples at 1ms frequency during cold start window.
# Stops automatically after --duration-seconds.
#
# Usage:
#   bash scripts/piw_cold_start_simpleperf.sh \
#       --device emulator-5554 \
#       --package build.raft.app \
#       --component build.raft.app/.MainActivity \
#       --duration-seconds 10 \
#       --frequency 999 \
#       --out /Users/artin/Downloads/piw-coldstart-simpleperf
#
# This script intentionally does not parse/analyze - it only records + pulls.
# Analysis is done by report-N.py / report-sample.py from the perf-data script.

set -euo pipefail

DEVICE=""
PACKAGE=""
COMPONENT=""
DURATION=10
FREQ=999
OUT="/tmp/piw-coldstart-simpleperf"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --component) COMPONENT="$2"; shift 2 ;;
    --duration-seconds) DURATION="$2"; shift 2 ;;
    --frequency) FREQ="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$DEVICE" || -z "$PACKAGE" || -z "$COMPONENT" ]]; then
  echo "usage: $0 --device SERIAL --package PKG --component ACT [--duration-seconds N] [--frequency HZ] [--out DIR]" >&2
  exit 2
fi

mkdir -p "$OUT"
RUN_ID=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$OUT/$RUN_ID"
mkdir -p "$RUN_DIR"

echo "[$RUN_ID] device=$DEVICE pkg=$PACKAGE component=$COMPONENT duration=${DURATION}s freq=${FREQ}Hz"
echo "[$RUN_ID] output: $RUN_DIR"

echo "[stage] force-stop + clear logcat"
adb -s "$DEVICE" shell "am force-stop $PACKAGE"
sleep 1
adb -s "$DEVICE" shell "logcat -c"

echo "[stage] launch"
adb -s "$DEVICE" shell "am start -W -n $COMPONENT" > "$RUN_DIR/am_start.txt" 2>&1
cat "$RUN_DIR/am_start.txt" | head -10

# Wait briefly for process to come up so simpleperf has a real PID.
PID=""
for i in $(seq 1 30); do
  PID=$(adb -s "$DEVICE" shell "pidof $PACKAGE" | tr -d '\r')
  if [[ -n "$PID" ]]; then
    break
  fi
  sleep 0.1
done

if [[ -z "$PID" ]]; then
  echo "[error] no pid found for $PACKAGE after launch"
  exit 3
fi
echo "[stage] app pid=$PID"

# Recording runs for DURATION seconds; auto-stops with `--duration`.
# -m 8192 pages = 32 MB kernel buffer, ample for ~12s @ 999Hz.
echo "[stage] simpleperf record -p $PID -f $FREQ -m 8192 -g --duration $DURATION"
adb -s "$DEVICE" shell "simpleperf record -p $PID -f $FREQ -m 8192 -g --duration $DURATION" \
  > "$RUN_DIR/simpleperf_record.log" 2>&1 || true

echo "[stage] capture final logcat"
adb -s "$DEVICE" logcat -d -v threadtime > "$RUN_DIR/logcat_full.txt" 2>&1

# Pull perf.data and the binary cache (for symbolization).
echo "[stage] pull perf.data"
PERFDATA_PATH=$(adb -s "$DEVICE" shell "ls /data/local/tmp/perf.data 2>/dev/null" | tr -d '\r' | head -1)
if [[ -z "$PERFDATA_PATH" ]]; then
  # Try via run-as (works for debuggable apps; release may fail).
  adb -s "$DEVICE" shell "run-as $PACKAGE cat /data/local/tmp/perf.data" > "$RUN_DIR/perf.data" 2>/dev/null || \
    echo "[warn] could not pull perf.data"
else
  adb -s "$DEVICE" pull "$PERFDATA_PATH" "$RUN_DIR/perf.data" || true
fi

# Try to pull the binary cache for symbolization.
CACHE_PATH=$(adb -s "$DEVICE" shell "ls /data/local/tmp/perf.data.binary_cache 2>/dev/null" | tr -d '\r' | head -1)
if [[ -n "$CACHE_PATH" ]]; then
  adb -s "$DEVICE" pull "$CACHE_PATH" "$RUN_DIR/perf.data.binary_cache" || true
fi

# Per-thread CPU snapshot via top -H at the end.
echo "[stage] top -H -p $PID"
adb -s "$DEVICE" shell "top -H -n 1 -b -p $PID" > "$RUN_DIR/top_threads.txt" 2>&1

# Per-thread /proc stat snapshots for all threads.
echo "[stage] per-thread /proc stat"
THREADS=$(adb -s "$DEVICE" shell "ls /proc/$PID/task/" | tr -d '\r')
PROC_DIR="$RUN_DIR/proc_task_stat"
mkdir -p "$PROC_DIR"
for tid in $THREADS; do
  name=$(adb -s "$DEVICE" shell "cat /proc/$PID/task/$tid/comm" | tr -d '\r')
  stat=$(adb -s "$DEVICE" shell "cat /proc/$PID/task/$tid/stat" | tr -d '\r')
  echo "$stat" > "$PROC_DIR/${tid}_${name}.stat"
done

echo "[done] artifacts in $RUN_DIR"