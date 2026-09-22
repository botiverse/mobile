#!/usr/bin/env bash
# Probe-only helper (task #283 / mermaid draw investigation, 2026-09-22).
# Pull the app's diagnostics JSONL off the emulator AFTER the visual capture so
# CI can read the shared-log probe lines (slockLog* go to the app sink, never to
# logcat once SlockApplication installs it). Three-tier outcome, per HuaTuo:
#   lines found            -> readings usable
#   0 lines                -> NOT PROVIDED (rotation/timing), never "no entry"
#   no run-as and no root  -> NOT COVERED, never a negative
set -u
out="${1:?usage: export_app_diagnostics_jsonl.sh <out-file>}"
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
echo "export_app_diagnostics: total_lines=$(wc -l < "$out") mermaid_lines=$(grep -c 'mermaid_drawcb' "$out" || true)"
