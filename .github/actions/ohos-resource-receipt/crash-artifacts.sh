#!/usr/bin/env bash
# OHOS shared-compile resource receipt — JVM/Gradle crash artifact inventory
# (task #91). Produces a bounded, SECRET-SAFE inventory of JVM fatal-error logs
# (hs_err_pid*.log) and Gradle daemon logs so a no-diagnostic
# `kspKotlinOhosArm64` kill leaves a recognizable signature behind.
#
# Secret-safety is mechanical, not incidental:
#  - hs_err files are reduced to an ALLOWLIST of diagnostic lines (error type,
#    signal, problematic frame, JRE/VM version) — command line, VM arguments and
#    environment sections (which can carry -D tokens) are never emitted;
#  - every emitted line then passes a mechanical redaction that masks values of
#    sensitive-looking keys (TOKEN/PASSWORD/SECRET/KEY/CREDENTIAL/...) as
#    defense in depth;
#  - daemon logs are recorded as path+size ONLY (no content), since they are
#    verbose and not the primary JVM crash signature.
# A checked-in fixture (test_crash_artifacts.sh) proves a sensitive JVM arg does
# not reach the artifact while the diagnostic fields are retained.
#
# Never fails the job.
set +e +o pipefail

receipt_dir="${OHOS_RECEIPT_DIR:-${RUNNER_TEMP:-/tmp}/ohos-resource-receipt}"
mkdir -p "$receipt_dir"
out="$receipt_dir/crash-artifacts.txt"

gradle_home="${GRADLE_USER_HOME:-${HOME}/.gradle}"
workspace="${GITHUB_WORKSPACE:-.}"

# Mechanical redaction (defense in depth on top of the allowlist): mask the
# value of any sensitive-looking KEY=VALUE / KEY: VALUE pair.
redact() {
  sed -E \
    -e 's/([A-Za-z0-9_.-]*(TOKEN|PASSWORD|PASSWD|SECRET|CREDENTIAL|APIKEY|API_KEY|ACCESS_KEY|PRIVATE_KEY|AUTH)[A-Za-z0-9_.-]*[=:])[^[:space:]]+/\1***REDACTED***/Ig' \
    2>/dev/null
}

# Allowlisted diagnostic lines from a JVM hs_err fatal-error log. These are the
# crash signature (type/signal/frame/version); they deliberately exclude the
# "Command line", "VM Arguments" and "Environment Variables" sections.
hs_err_allowlist() {
  grep -aE \
    -e '^#[[:space:]]*(A fatal error has been detected|There is insufficient memory|Native memory allocation|Failed to (reserve|allocate)|Out of Memory)' \
    -e '^#[[:space:]]+(SIG[A-Z0-9]+|EXC_[A-Z_]+|BUS_|SEGV|ILL_|FPE_|ABRT)' \
    -e '^#[[:space:]]*Problematic frame:' \
    -e '^#[[:space:]]+[CJjVvMm][[:space:]]+' \
    -e '^#[[:space:]]*(JRE version|Java VM|Java Runtime|Core dump)' \
    -e '^#[[:space:]]*(pid|tid|siginfo|memory|stack)' \
    "$1" 2>/dev/null | head -n 25 | redact
}

{
  printf '# ohos-resource-receipt crash-artifact inventory (allowlisted, redacted)\n'
  printf '# generated=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null)"

  found=0

  # JVM fatal-error logs (hs_err_pid*.log) — the classic OOM/SIGKILL signature.
  for base in "$workspace" "$gradle_home" /tmp "${RUNNER_TEMP:-/tmp}"; do
    [ -d "$base" ] || continue
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      found=1
      printf '\n## hs_err %s (%s bytes)\n' "$f" "$(wc -c < "$f" 2>/dev/null | tr -d '[:space:]')"
      body="$(hs_err_allowlist "$f")"
      if [ -n "$body" ]; then
        printf '%s\n' "$body"
      else
        printf '(no allowlisted diagnostic lines extracted)\n'
      fi
    done <<EOF
$(find "$base" -maxdepth 4 -name 'hs_err_pid*.log' -type f 2>/dev/null | head -n 10)
EOF
  done

  # Gradle / Kotlin daemon logs — inventory only (path+size), no content dump.
  for base in "$gradle_home/daemon" "$gradle_home/kotlin-profile"; do
    [ -d "$base" ] || continue
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      found=1
      printf '\n## daemon-log %s (%s bytes) [inventory-only, content omitted]\n' \
        "$f" "$(wc -c < "$f" 2>/dev/null | tr -d '[:space:]')"
    done <<EOF
$(find "$base" -maxdepth 4 \( -name 'daemon-*.log' -o -name 'kotlin-daemon.*.log' \) -type f -newer "$receipt_dir/pre.env" 2>/dev/null | head -n 10)
EOF
  done

  if [ "$found" -eq 0 ]; then
    printf '\nno JVM/Gradle crash artifacts found\n'
  fi
} > "$out"

echo "----- ohos-resource-receipt [crash-artifacts] -----"
cat "$out"
echo "---------------------------------------------------"

exit 0
