#!/usr/bin/env bash
# OHOS shared-compile resource receipt — fail-close classifier (task #91).
#
# Reads the pre/post memory snapshots and the compile phase receipt, then emits
# a single verdict. Phase 1 only ever emits:
#   resource_exhaustion_suspected | indeterminate
# plus `healthy` when the compile phase receipt positively shows exit_code=0.
# Absence of a compile-phase receipt is NOT evidence of success: it is signed
# `indeterminate` with reason `no_phase` (the job may have died before the
# wrapped step recorded anything). It deliberately does NOT emit
# `confirmed_infra` — that upgrade is reserved for a future phase once an
# independent oom_kill delta has actually been observed and trusted.
#
# Evidence semantics (fail-close):
# - memory.events.oom_kill is the kernel counter for "processes in THIS cgroup
#   killed by ANY OOM killer" — that includes a host/VM-level OOM killer taking
#   out a cgroup process, so a positive oom_kill delta IS a valid exhaustion
#   signal even when memory.max=max (no cgroup limit). The genuine blind spot is
#   the whole runner/VM dying first, in which case the always() post step never
#   runs — detectable as a missing post/always_executed receipt, not via deltas.
# - A single pre/post host_mem_available is NOT used to upgrade classification:
#   after an OOM kill frees memory the available value rebounds, so it is not a
#   monotonic exhaustion signal.
#
# Never fails the job and never changes the gate result.
set +e +o pipefail

receipt_dir="${OHOS_RECEIPT_DIR:-${RUNNER_TEMP:-/tmp}/ohos-resource-receipt}"
pre="$receipt_dir/pre.env"
post="$receipt_dir/post.env"
phase="$receipt_dir/compile-phase.env"
out="$receipt_dir/verdict.env"

# Read `key=value` from an env file; echo empty when the value is unavailable().
get() {
  file="$1"
  key="$2"
  [ -f "$file" ] || { printf ''; return; }
  val="$(awk -F= -v k="$key" '$1==k{sub(/^[^=]*=/,"");print;exit}' "$file" 2>/dev/null)"
  case "$val" in
    unavailable*) printf '' ;;
    *) printf '%s' "$val" ;;
  esac
}

is_num() { case "$1" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }

compile_exit="$(get "$phase" exit_code)"
# rc>128 only SUGGESTS a signal (128+N convention); it is not proof the process
# was killed by signal N, so the field is named signal_candidate, not signal.
compile_signal_candidate="$(get "$phase" signal_candidate)"

# No compile phase recorded (e.g. the runner/VM died before the wrapped step
# wrote compile-phase.env). Absence of evidence is NOT evidence of no failure:
# fail-close to indeterminate(no_phase). Never sign a healthy/no-failure here.
if [ -z "$compile_exit" ]; then
  verdict="indeterminate"
  reason="no compile-phase receipt recorded (no_phase): cannot judge compile outcome"
elif [ "$compile_exit" = "0" ]; then
  # Positive evidence: the wrapped compile step recorded a clean exit.
  verdict="healthy"
  reason="compile exit_code=0"
else
  # Compile failed. Gather resource evidence; fail-close to indeterminate when
  # the evidence is absent/unreadable.
  pre_oom_kill="$(get "$pre" memory_events_oom_kill)"
  post_oom_kill="$(get "$post" memory_events_oom_kill)"
  pre_oom="$(get "$pre" memory_events_oom)"
  post_oom="$(get "$post" memory_events_oom)"
  pre_max_events="$(get "$pre" memory_events_max)"
  post_max_events="$(get "$post" memory_events_max)"
  mem_peak="$(get "$post" memory_peak)"
  mem_max="$(get "$post" memory_max)"
  psi_full="$(get "$post" psi_memory_full_avg10)"

  oom_kill_delta=""
  if is_num "$pre_oom_kill" && is_num "$post_oom_kill"; then
    oom_kill_delta=$((post_oom_kill - pre_oom_kill))
  fi
  oom_delta=""
  if is_num "$pre_oom" && is_num "$post_oom"; then
    oom_delta=$((post_oom - pre_oom))
  fi
  max_delta=""
  if is_num "$pre_max_events" && is_num "$post_max_events"; then
    max_delta=$((post_max_events - pre_max_events))
  fi

  # peak >= 95% of a NUMERIC max => the cgroup pressed hard against its limit.
  # When memory.max=max (unlimited cgroup; the VM is the boundary) this stays
  # false and the oom_kill delta carries the exhaustion signal instead.
  near_ceiling="false"
  if is_num "$mem_peak" && is_num "$mem_max" && [ "$mem_max" -gt 0 ]; then
    if [ $((mem_peak * 100)) -ge $((mem_max * 95)) ]; then
      near_ceiling="true"
    fi
  fi

  evidence="none"
  if is_num "$oom_kill_delta" && [ "$oom_kill_delta" -gt 0 ]; then
    evidence="oom_kill_delta=$oom_kill_delta"
  elif is_num "$oom_delta" && [ "$oom_delta" -gt 0 ]; then
    evidence="oom_delta=$oom_delta"
  elif [ "$near_ceiling" = "true" ]; then
    evidence="peak_near_max(peak=${mem_peak},max=${mem_max})"
  elif is_num "$max_delta" && [ "$max_delta" -gt 0 ]; then
    evidence="max_events_delta=$max_delta"
  fi

  if [ "$evidence" != "none" ]; then
    # Strongest Phase 1 claim: suspicious, but NOT confirmed_infra (reserved).
    verdict="resource_exhaustion_suspected"
    reason="compile exit_code=$compile_exit signal_candidate=${compile_signal_candidate:-none}; $evidence"
  else
    verdict="indeterminate"
    reason="compile exit_code=$compile_exit signal_candidate=${compile_signal_candidate:-none}; no independent resource evidence (metrics unavailable or unchanged)"
  fi
fi

{
  printf 'verdict=%s\n' "$verdict"
  printf 'reason=%s\n' "$reason"
  printf 'compile_exit_code=%s\n' "${compile_exit:-unavailable(no_phase)}"
  printf 'compile_signal_candidate=%s\n' "${compile_signal_candidate:-none}"
  printf 'oom_kill_delta=%s\n' "${oom_kill_delta:-unavailable}"
  printf 'oom_delta=%s\n' "${oom_delta:-unavailable}"
  printf 'max_events_delta=%s\n' "${max_delta:-unavailable}"
  printf 'memory_peak=%s\n' "${mem_peak:-unavailable}"
  printf 'memory_max=%s\n' "${mem_max:-unavailable}"
  printf 'near_ceiling=%s\n' "${near_ceiling:-unavailable}"
  printf 'psi_memory_full_avg10=%s\n' "${psi_full:-unavailable}"
  # Phase 1 policy marker: confirmed_infra is intentionally never emitted here.
  printf 'classifier_policy=phase1_fail_close(confirmed_infra_reserved)\n'
} > "$out"

echo "----- ohos-resource-receipt [verdict] -----"
cat "$out"
echo "-------------------------------------------"

exit 0
