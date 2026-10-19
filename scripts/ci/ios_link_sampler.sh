#!/usr/bin/env bash
# Diagnostic sampler for slow Kotlin/Native framework links on CI runners.
# Prints memory pressure, swap, JVM heap/GC and the compiler thread's stack
# every INTERVAL seconds so a link that never finishes still leaves evidence.
INTERVAL="${1:-120}"
while true; do
  sleep "$INTERVAL"
  echo "===== link-sampler $(date -u +%H:%M:%SZ) ====="
  sysctl -n vm.swapusage 2>/dev/null
  memory_pressure 2>/dev/null | tail -1
  ps -axo pid,rss,%cpu,etime,command | awk 'NR==1 || /java|clang|ld /' | grep -v awk | cut -c1-200 | head -8
  for pid in $(pgrep -f GradleDaemon); do
    jstat -gcutil "$pid" 2>/dev/null | tail -2
    # Top frames of the thread doing the K/N work (codegen/LLVM/link).
    jstack "$pid" 2>/dev/null | awk '
      /^"/ { keep = 0; buf = $0; n = 0; next }
      { if (n < 30) { buf = buf "\n" $0; n++ } }
      /konan|llvm|backend\.konan|kotlin\.backend/ && !keep { keep = 1 }
      /^$/ { if (keep) print buf; keep = 0; buf = "" }
    ' | head -45
  done
done
