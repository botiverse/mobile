#!/usr/bin/env bash
# play_internal_promote.sh against a stub Hands CLI: the call order, that only the
# internal track is used (and with --open-testing, beta after it, at the re-read
# revision), and that a build without a sealed AAB stops before any write.
set -euo pipefail
cd "$(dirname "$0")/../.."
WORK="$(mktemp -d)"; trap 'rm -rf "${WORK}"' EXIT
failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

cat >"${WORK}/hands" <<'STUB'
#!/usr/bin/env bash
# hands api <METHOD> <path> [--data <json>] --json
shift; method="$1" path="$2" data=""
[ "${3:-}" = "--data" ] && data="$4"
echo "${method} ${path} ${data}" >>"${STUB_LOG}"
case "${method} ${path}" in
  "GET /api/apps/app/android-release-artifacts/build-1")
    echo "{\"status\":\"${STUB_BUNDLE_STATUS:-ready}\",\"version_code\":11800009,\"source\":{\"ci_run_id\":\"42\",\"commit_sha\":\"abcdef0123456789\"},\"artifacts\":[{\"kind\":\"aab\",\"asset_id\":\"aab-1\",\"status\":\"sealed\"},{\"kind\":\"apk\",\"asset_id\":\"apk-1\",\"status\":\"sealed\"}]}" ;;
  "POST /api/apps/app/releases/draft") echo '{"id":"rel-1","status":"draft","revision":0}' ;;
  "POST /api/apps/app/releases/rel-1/receipts/acceptance") echo '{"receipt_id":"acc-1"}' ;;
  "GET /api/apps/app/releases/rel-1")
    # Revision 1 after the acceptance, 3 after the internal promotion.
    if grep -q 'play/promote' "${STUB_LOG}"; then echo '{"release":{"id":"rel-1","revision":3}}'; else echo '{"release":{"id":"rel-1","revision":1}}'; fi ;;
  "POST /api/apps/app/releases/rel-1/distributions/play/promote")
    case "${data}" in
      *'"track":"internal"'*) echo '{"receipt_id":"pro-1","track":"internal","version_code":11800009,"revision":2}' ;;
      *'"track":"beta"'*) [ -n "${STUB_BETA_FAILS:-}" ] && { echo "409 changes in review" >&2; exit 1; }
        echo '{"receipt_id":"pro-2","track":"beta","version_code":11800009,"revision":4}' ;;
      *) echo "unexpected track: ${data}" >&2; exit 9 ;;
    esac ;;
  *) echo "unexpected call: ${method} ${path}" >&2; exit 9 ;;
esac
STUB
chmod +x "${WORK}/hands"
export HANDS_CLI="${WORK}/hands" HANDS_ANDROID_APP_ID=app STUB_LOG="${WORK}/log"

out="$(scripts/play_internal_promote.sh build-1)" || fail "happy path exited $?"
[ "$(cut -d' ' -f1-2 "${WORK}/log" | tr '\n' ';')" = "GET /api/apps/app/android-release-artifacts/build-1;POST /api/apps/app/releases/draft;POST /api/apps/app/releases/rel-1/receipts/acceptance;GET /api/apps/app/releases/rel-1;POST /api/apps/app/releases/rel-1/distributions/play/promote;" ] ||
  fail "unexpected call order: $(cat "${WORK}/log")"
grep 'receipts/acceptance' "${WORK}/log" | grep -q '"artifact_id":"aab-1".*"expected_revision":0' || fail "acceptance is not bound to the sealed AAB at revision 0"
grep 'play/promote' "${WORK}/log" | grep -q '"track":"internal","expected_revision":1' || fail "promotion is not internal at the re-read revision"
grep -q 'release_id=rel-1' <<<"${out}" || fail "release id not printed"
grep -q 'receipt pro-1' <<<"${out}" || fail "promotion receipt not printed"
grep -q '"track":"beta"' "${WORK}/log" && fail "beta promoted without --open-testing"

: >"${WORK}/log"
out="$(scripts/play_internal_promote.sh --open-testing build-1)" || fail "open testing path exited $?"
[ "$(grep -c 'play/promote' "${WORK}/log")" = 2 ] || fail "open testing did not promote twice: $(cat "${WORK}/log")"
grep 'play/promote' "${WORK}/log" | sed -n 1p | grep -q '"track":"internal","expected_revision":1' || fail "open testing: internal is not first at revision 1"
grep 'play/promote' "${WORK}/log" | sed -n 2p | grep -q '"track":"beta","expected_revision":3' || fail "open testing: beta is not second at the re-read revision 3"
grep 'play/promote' "${WORK}/log" | sed -n 2p | grep -q 'rollout' && fail "open testing sent a rollout percent"
grep -q 'receipt pro-2' <<<"${out}" || fail "beta receipt not printed"
grep -q 'release_id=rel-1' <<<"${out}" || fail "open testing: release id not printed"

: >"${WORK}/log"
err="$(STUB_BETA_FAILS=1 scripts/play_internal_promote.sh --open-testing build-1 2>&1 >/dev/null)" && fail "a failed beta step exited 0"
grep -q 'internal is done' <<<"${err}" || fail "a failed beta step does not say internal is done: ${err}"
grep -q 'play-promote raft-android rel-1 --track beta' <<<"${err}" || fail "a failed beta step does not print the retry command"

: >"${WORK}/log"
STUB_BUNDLE_STATUS=uploading scripts/play_internal_promote.sh build-1 >/dev/null 2>&1 && fail "a build that is not ready was promoted"
[ "$(wc -l <"${WORK}/log")" = 1 ] || fail "a build that is not ready got writes: $(cat "${WORK}/log")"

if [ "${failures}" -ne 0 ]; then echo "play_internal_promote: ${failures} failure(s)" >&2; exit 1; fi
echo "play_internal_promote: PASS"
