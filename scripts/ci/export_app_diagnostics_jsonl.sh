#!/usr/bin/env bash
# Probe-only helper (task #283 / mermaid draw investigation, 2026-09-22).
# Pull the app's diagnostics JSONL off the emulator AFTER the visual capture so
# CI can read the shared-log probe lines (slockLog* go to the app sink, never to
# logcat once SlockApplication installs it). Three-tier outcome, per HuaTuo:
#   lines found            -> readings usable
#   0 lines                -> NOT PROVIDED (rotation/timing), never "no entry"
#   no run-as and no root  -> NOT COVERED, never a negative
set -u
out="${1:?usage: export_app_diagnostics_jsonl.sh <out-file> [action-start-file]}"
start_file="${2:-}"
mkdir -p "$(dirname "$out")"
: > "$out"
rel='files/raft_diagnostics_logs'
pkgs="$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -E '^build\.raft\.app' || true)"
echo "export_app_diagnostics: packages=[$(echo "$pkgs" | tr '\n' ' ')]"
for pkg in $pkgs; do
  # 1) debuggable path
  if adb shell "run-as $pkg sh -c 'ls $rel >/dev/null 2>&1'" >/dev/null 2>&1; then
    adb shell "run-as $pkg sh -c 'for f in \$(find $rel -name raft-diagnostics.jsonl 2>/dev/null); do echo \"### \$f\"; cat \"\$f\"; done'" 2>/dev/null | tr -d '\r' >> "$out"
    echo "export_app_diagnostics: run-as $pkg ok"
    continue
  fi
  # 2) rooted emulator path (google_apis images accept adb root)
  if adb root >/dev/null 2>&1 && adb wait-for-device >/dev/null 2>&1; then
    adb shell "sh -c 'for f in \$(find /data/data/$pkg/$rel -name raft-diagnostics.jsonl 2>/dev/null); do echo \"### \$f\"; cat \"\$f\"; done'" 2>/dev/null | tr -d '\r' >> "$out"
    echo "export_app_diagnostics: root read $pkg ok"
    continue
  fi
  echo "export_app_diagnostics: $pkg NOT COVERED (no run-as, no root)"
done
# Window check (HuaTuo): the JSONL rotates in place; if its FIRST record is later
# than the action start, the window is incomplete and a 0 for any tag is NOT
# PROVIDED rather than a negative. Record both so the reader can judge.
win="${out%.jsonl}.window.txt"
action_raw="$( [ -n "$start_file" ] && [ -f "$start_file" ] && cat "$start_file" || echo "unknown none" )"
action_start="${action_raw%% *}"; action_res="${action_raw##* }"
first_ts="$(grep -v '^###' "$out" | head -1 | grep -oE '"(timestampMillis|ts|timestamp)":[ ]*[0-9]+' | head -1 | grep -oE '[0-9]+$' || true)"
{
  echo "capture_mode=tail_at_end"
  echo "action_start_device_ms=$action_start"
  echo "action_start_resolution=$action_res   # ms = comparable to JSONL timestampMillis; s = truncated, may be up to 999ms early (biases toward NOT PROVIDED)"
  echo "first_record_ts=${first_ts:-none}"
  echo "total_lines=$(grep -vc '^###' "$out" || true)"
  echo "bytes=$(wc -c < "$out")"
  for tag in mermaid_drawcb_entry mermaid_drawcb_redraw_enter mermaid_drawcb_setframe_enter mermaid_drawcb_setframe_returned; do
    echo "count_$tag=$(grep -c "$tag" "$out" || true)"
  done
} | tee "$win"
