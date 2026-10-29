# shellcheck shell=bash
# Retry a Hands CLI command when it fails with a transient server or network error.
#
# Source this file, then run `hands_retry run_hands builds publish-android ...`.
#
# Hands is a Cloudflare Worker in front of R2. R2 documents 503 ServiceUnavailable as
# transient and asks clients to back off and retry; CI saw it twice on 2026-10-28 and a
# plain re-run passed. Only failures whose output looks like a 5xx or a dropped
# connection are retried. Anything else (4xx, validation, guard failures, a missing CLI)
# fails on the first attempt with the command's own exit code.
#
# A retried publish starts over, so an earlier attempt may leave a `pending` build
# behind on Hands. That is harmless: Hands keeps it pending and never offers it.
#
# Output contract: stderr streams live on every attempt. stdout is held until the
# attempt ends; the successful attempt's stdout is printed once, on stdout, so callers
# that parse `release:` / `build_id=` lines see exactly one copy. A failed attempt's
# stdout goes to stderr.
#
# Tuning (tests set these to keep the run fast):
#   HANDS_RETRY_ATTEMPTS  total attempts, default 3
#   HANDS_RETRY_DELAYS    seconds to wait before attempts 2, 3, ...; default "10 30 90"

HANDS_RETRY_TRANSIENT_PATTERN='(^|[^0-9])5[0-9][0-9] (Internal Server Error|Not Implemented|Bad Gateway|Service Unavailable|Gateway Time-?out)|(HTTP|[Ss]tatus( code)?|Error:?)[ :=]*5[0-9][0-9]([^0-9]|$)|fetch failed|ECONNRESET|ETIMEDOUT|ECONNREFUSED|EAI_AGAIN|socket hang up|UND_ERR_(SOCKET|CONNECT_TIMEOUT|HEADERS_TIMEOUT|BODY_TIMEOUT)'

hands_retry() {
  local max="${HANDS_RETRY_ATTEMPTS:-3}"
  local -a delays
  read -r -a delays <<<"${HANDS_RETRY_DELAYS:-10 30 90}"
  local attempt=1 status delay out err errexit=0
  case "$-" in *e*) errexit=1 ;; esac
  out="$(mktemp)"
  err="$(mktemp)"
  while :; do
    : >"${out}"
    : >"${err}"
    # stdout -> file (held), stderr -> live stderr + file (for classification).
    set +e
    { "$@" 2>&1 1>&3 | tee "${err}" >&2; } 3>"${out}"
    status="${PIPESTATUS[0]}"
    [ "${errexit}" = "1" ] && set -e
    if [ "${status}" = "0" ]; then
      cat "${out}"
      rm -f "${out}" "${err}"
      return 0
    fi
    if [ "${attempt}" -ge "${max}" ] || ! grep -Eq "${HANDS_RETRY_TRANSIENT_PATTERN}" "${out}" "${err}"; then
      cat "${out}" >&2
      if [ "${attempt}" -gt 1 ]; then
        echo "hands: attempt ${attempt}/${max} failed (exit ${status}); giving up" >&2
      fi
      rm -f "${out}" "${err}"
      return "${status}"
    fi
    delay="${delays[$((attempt - 1))]:-${delays[${#delays[@]}-1]:-10}}"
    cat "${out}" >&2
    echo "hands: attempt ${attempt}/${max} failed (transient, exit ${status}): $(grep -Eh "${HANDS_RETRY_TRANSIENT_PATTERN}" "${out}" "${err}" | head -n 3 | tr '\n' ' ')" >&2
    echo "hands: retrying in ${delay}s" >&2
    sleep "${delay}"
    attempt=$((attempt + 1))
  done
}
