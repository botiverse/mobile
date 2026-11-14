#!/usr/bin/env bash
# Hands side of the Android in-app update e2e (.github/workflows/android-update-e2e.yml).
#
# The fixture carrier is the one docs/qa-acceptance/android-app-update/README.md
# defines: Hands app raft-android, channel debug, full APK (no delta). A is never
# published; only B gets a release, scoped to ONE exact-rollout device group, so
# no installation outside that group is ever offered it. A device-group-only
# release does not supersede the channel's full-scope release.
#
# Subcommands (all need HANDS_AUTH_TOKEN; HANDS_API defaults to https://hands.build):
#   plan                         -> prints version_major / baseline_build / target_build /
#                                   fixture_generation for the next unused QA versionCodes
#   group                        -> prints the e2e device group id (created once if missing)
#   publish APK NAME CODE GROUP SHA
#                                -> prints release_id of B on debug, active, scoped to GROUP
#   add-member GROUP DEVICE_ID LABEL
#   cleanup GROUP RELEASE_ID DEVICE_ID   (best effort, every step attempted)
set -euo pipefail

APP_SLUG=raft-android
# Hard-wired: this helper must never write to preview, main or any user-facing channel.
CHANNEL=debug
GROUP_NAME="${HANDS_E2E_GROUP_NAME:-GitHub Actions update e2e emulators}"
# Reserved QA versionCode window: majors 200..209 with minor = patch = 0.
RANGE_LO=2000000000
RANGE_HI=2100000000

die() { echo "android-update-hands: $*" >&2; exit 1; }
[ -n "${HANDS_AUTH_TOKEN:-}" ] || die "HANDS_AUTH_TOKEN is required"
command -v hands >/dev/null || die "hands CLI is not installed"

app_id() {
  hands --json api GET /api/apps | python3 -c '
import json, sys
slug = sys.argv[1]
d = json.load(sys.stdin)
print(next(a["id"] for a in d.get("apps", []) if a.get("slug") == slug))' "${APP_SLUG}"
}

releases_json() {
  hands --json api GET "/api/apps/$(app_id)/releases?limit=1000"
}

cmd="${1:-}"
shift || true
case "${cmd}" in
  plan)
    releases_json | python3 -c '
import json, sys
lo, hi = int(sys.argv[1]), int(sys.argv[2])
codes = [r.get("version_code") or 0 for r in json.load(sys.stdin).get("releases", [])]
used = [c for c in codes if lo <= c < hi]
nxt = (max(used) + 1) if used else lo + 1
major, rest = divmod(nxt, 10_000_000)
if rest + 1 > 999:  # A and B share the major; minor = patch = 0
    major, rest = major + 1, 1
if major * 10_000_000 + rest + 1 >= hi:
    sys.exit("reserved QA versionCode window is exhausted")
print(f"version_major={major}")
print(f"baseline_build={rest}")
print(f"target_build={rest + 1}")
print(f"fixture_generation={(major - 200) * 1000 + rest + 1}")
' "${RANGE_LO}" "${RANGE_HI}"
    ;;

  group)
    gid="$(hands device-groups list "${APP_SLUG}" --json | python3 -c '
import json, sys
name = sys.argv[1]
print(next((g["id"] for g in json.load(sys.stdin).get("groups", []) if g.get("name") == name), ""))' "${GROUP_NAME}")"
    if [ -z "${gid}" ]; then
      gid="$(hands device-groups create "${APP_SLUG}" --name "${GROUP_NAME}" \
        --description "Only Hands installation ids of the throwaway emulator of an android-update-e2e.yml run; the run removes its member when it ends. Never use for personal or public rollout." \
        --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("group") or d)["id"])')"
    fi
    echo "${gid}"
    ;;

  publish)
    apk="$1" name="$2" code="$3" group="$4" sha="$5"
    [ -s "${apk}" ] || die "B APK not found: ${apk}"
    existing="$(releases_json | python3 -c '
import json, sys
code = int(sys.argv[1])
rs = [r for r in json.load(sys.stdin).get("releases", []) if r.get("version_code") == code and r.get("status") != "superseded"]
rs.sort(key=lambda r: r.get("created_at") or 0, reverse=True)
print(" ".join([rs[0]["id"], rs[0]["status"]]) if rs else "")' "${code}")"
    if [ -z "${existing}" ]; then
      out="$(hands builds publish-android "${APP_SLUG}" --apk "${apk}" --version-name "${name}" --version-code "${code}" \
        --channel "${CHANNEL}" --arch x86_64 --draft --source-commit "${sha}" --ci-provider github-actions \
        --ci-run-id "${GITHUB_RUN_ID:-local}" --ci-url "${GITHUB_SERVER_URL:-}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}" \
        --changelog "Android update e2e fixture B (${name}); device-group scoped test release." --json)"
      rid="$(printf '%s' "${out}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
r = d.get("release") if isinstance(d.get("release"), dict) else None
print((r or {}).get("id") or d.get("release_id") or d.get("releaseId") or "")')"
      [ -n "${rid}" ] || { printf '%s\n' "${out}" >&2; die "publish-android returned no release id"; }
      status=draft
    else
      read -r rid status <<<"${existing}"
      echo "reusing Hands release ${rid} (${status}) for versionCode ${code}" >&2
    fi
    case "${status}" in
      draft)
        hands releases update "${APP_SLUG}" "${rid}" --device-group "${group}" --json >/dev/null
        hands releases publish "${APP_SLUG}" "${rid}" --device-group "${group}" --json >/dev/null
        ;;
      cancelled)
        hands releases update "${APP_SLUG}" "${rid}" --device-group "${group}" --json >/dev/null 2>&1 || true
        hands releases restore "${APP_SLUG}" "${rid}" --json >/dev/null
        st="$(hands releases show "${APP_SLUG}" "${rid}" --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["release"]["status"])')"
        if [ "${st}" = "draft" ]; then
          hands releases update "${APP_SLUG}" "${rid}" --device-group "${group}" --json >/dev/null
          hands releases publish "${APP_SLUG}" "${rid}" --device-group "${group}" --json >/dev/null
        fi
        ;;
      active) ;;
      *) die "release ${rid} for versionCode ${code} is ${status}; cannot reuse it" ;;
    esac
    # Fail closed unless B is active and scoped to exactly the e2e group.
    hands releases show "${APP_SLUG}" "${rid}" --json | python3 -c '
import json, sys
d = json.load(sys.stdin)
r = d["release"]
status = r["status"]
assert status == "active", f"release status is {status}, expected active"
assert not r.get("is_full"), "release is full-scope; it must be device-group scoped"
' || die "Hands release ${rid} is not an active device-group-only release"
    echo "${rid}"
    ;;

  add-member)
    hands device-groups add-member "${APP_SLUG}" "$1" --device-id "$2" --label "$3" --json >/dev/null
    ;;

  cleanup)
    group="${1:-}" rid="${2:-}" device="${3:-}" rc=0
    if [ -n "${group}" ] && [ -n "${device}" ]; then
      hands device-groups remove-member "${APP_SLUG}" "${group}" --device-id "${device}" --json >/dev/null || { echo "::warning::could not remove the emulator from the Hands device group" >&2; rc=1; }
    fi
    if [ -n "${rid}" ]; then
      hands releases cancel "${APP_SLUG}" "${rid}" --json >/dev/null || { echo "::warning::could not cancel Hands release ${rid}" >&2; rc=1; }
    fi
    exit "${rc}"
    ;;

  *)
    die "usage: $0 plan|group|publish|add-member|cleanup ..."
    ;;
esac
