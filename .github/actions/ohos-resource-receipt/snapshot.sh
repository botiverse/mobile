#!/usr/bin/env bash
# OHOS shared-compile resource receipt — memory snapshot (task #91, DX collab).
#
# Captures the runner's cgroup memory accounting around the OHOS shared compile
# so a future no-diagnostic `kspKotlinOhosArm64` kill can be classified
# fail-close (resource_exhaustion_suspected vs indeterminate) instead of being
# guessed as "infra". Phase 1 only COLLECTS; it never changes the gate result,
# never auto-greens, and never expands permissions to reach cgroup data — if a
# metric is missing or unreadable it is recorded as a typed `unavailable(reason)`,
# which is itself an honest Phase 1 result.
#
# Usage: snapshot.sh <label>   (label = pre | post | ...)
# Writes key=value lines to $OHOS_RECEIPT_DIR/<label>.env. Never fails the job.
#
# Portable by contract: Bash 3.2+, GNU/BSD userland, no mapfile/find -printf.
set +e +o pipefail

label="${1:-snapshot}"
receipt_dir="${OHOS_RECEIPT_DIR:-${RUNNER_TEMP:-/tmp}/ohos-resource-receipt}"
mkdir -p "$receipt_dir"
out="$receipt_dir/$label.env"

# Emit `key=value`; value is the file's trimmed content, or `unavailable(reason)`
# when the path is absent/unreadable. We do NOT attempt to gain access — a
# missing/unreadable cgroup file is reported as-is (DX fail-close contract).
emit_file() {
  key="$1"
  path="$2"
  if [ ! -e "$path" ]; then
    printf '%s=unavailable(absent:%s)\n' "$key" "$path"
  elif [ ! -r "$path" ]; then
    printf '%s=unavailable(unreadable:%s)\n' "$key" "$path"
  else
    val="$(head -n 1 "$path" 2>/dev/null | tr -d '[:space:]')"
    if [ -z "$val" ]; then
      printf '%s=unavailable(empty:%s)\n' "$key" "$path"
    else
      printf '%s=%s\n' "$key" "$val"
    fi
  fi
}

# memory.events is a multi-line "key count" table; pull one counter by name.
emit_memory_event() {
  key="$1"
  counter="$2"
  path="/sys/fs/cgroup/memory.events"
  if [ ! -e "$path" ]; then
    printf '%s=unavailable(absent:%s)\n' "$key" "$path"
  elif [ ! -r "$path" ]; then
    printf '%s=unavailable(unreadable:%s)\n' "$key" "$path"
  else
    val="$(awk -v c="$counter" '$1==c{print $2; exit}' "$path" 2>/dev/null)"
    if [ -z "$val" ]; then
      printf '%s=unavailable(counter_missing:%s)\n' "$key" "$counter"
    else
      printf '%s=%s\n' "$key" "$val"
    fi
  fi
}

# /proc/pressure/memory line: some avg10=X.XX avg60=... full avg10=...
emit_psi() {
  key="$1"
  kind="$2" # some | full
  path="/proc/pressure/memory"
  if [ ! -e "$path" ]; then
    printf '%s=unavailable(absent:%s)\n' "$key" "$path"
  elif [ ! -r "$path" ]; then
    printf '%s=unavailable(unreadable:%s)\n' "$key" "$path"
  else
    val="$(awk -v k="$kind" '$1==k{for(i=2;i<=NF;i++){if($i ~ /^avg10=/){sub(/^avg10=/,"",$i);print $i;exit}}}' "$path" 2>/dev/null)"
    if [ -z "$val" ]; then
      printf '%s=unavailable(field_missing:%s)\n' "$key" "$kind"
    else
      printf '%s=%s\n' "$key" "$val"
    fi
  fi
}

{
  printf 'label=%s\n' "$label"
  printf 'timestamp=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null)"

  # Detect cgroup flavor so the classifier knows which evidence model applies.
  if [ -e /sys/fs/cgroup/memory.events ]; then
    printf 'cgroup_version=2\n'
  elif [ -e /sys/fs/cgroup/memory/memory.oom_control ]; then
    printf 'cgroup_version=1\n'
  else
    printf 'cgroup_version=unavailable(no_cgroup_memory_controller)\n'
  fi

  # cgroup v2 memory accounting. v1 paths are best-effort fallbacks; if neither
  # exists the typed unavailable reasons make the gap explicit.
  emit_memory_event memory_events_oom_kill oom_kill
  emit_memory_event memory_events_oom oom
  emit_memory_event memory_events_max max
  emit_memory_event memory_events_high high
  emit_file memory_current /sys/fs/cgroup/memory.current
  emit_file memory_peak /sys/fs/cgroup/memory.peak
  emit_file memory_max /sys/fs/cgroup/memory.max
  # cgroup v1 fallbacks (only meaningful when cgroup_version=1).
  emit_file memory_v1_usage /sys/fs/cgroup/memory/memory.usage_in_bytes
  emit_file memory_v1_max /sys/fs/cgroup/memory/memory.limit_in_bytes
  emit_file memory_v1_oom_control /sys/fs/cgroup/memory/memory.oom_control

  # Memory PSI (optional pressure signal).
  emit_psi psi_memory_some_avg10 some
  emit_psi psi_memory_full_avg10 full

  # Host-level context (cheap, helps correlate VM ceiling).
  if command -v free >/dev/null 2>&1; then
    memtotal="$(free -b 2>/dev/null | awk '/^Mem:/{print $2; exit}')"
    memavail="$(free -b 2>/dev/null | awk '/^Mem:/{print $7; exit}')"
    printf 'host_mem_total_bytes=%s\n' "${memtotal:-unavailable(free_parse)}"
    printf 'host_mem_available_bytes=%s\n' "${memavail:-unavailable(free_parse)}"
  else
    printf 'host_mem_total_bytes=unavailable(no_free)\n'
    printf 'host_mem_available_bytes=unavailable(no_free)\n'
  fi
} > "$out"

# Echo into the job log for in-line visibility (bounded, no secrets).
echo "----- ohos-resource-receipt [$label] -----"
cat "$out"
echo "------------------------------------------"

exit 0
