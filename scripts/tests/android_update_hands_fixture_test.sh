#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/scripts/qa/android_update_hands_fixture.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

mkdir -p "${WORK}/bin" "${WORK}/files" "${WORK}/out"
printf 'apk-a-bytes' >"${WORK}/files/a.apk"
printf 'apk-b-bytes' >"${WORK}/files/b.apk"
SHA_A="$(sha256sum "${WORK}/files/a.apk" | cut -d' ' -f1)"
SHA_B="$(sha256sum "${WORK}/files/b.apk" | cut -d' ' -f1)"

cat >"${WORK}/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
out=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
src="${url#file://}"
cp "${src}" "${out}"
CURL
chmod +x "${WORK}/bin/curl"

cat >"${WORK}/bin/hands" <<'HANDS'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${STUB_LOG}"
if [ "$1" = "--json" ] && [ "$2" = "api" ] && [ "$3" = "GET" ]; then
  case "$4" in
    /api/apps)
      echo '{"apps":[{"id":"app-1","slug":"raft-android"}]}'
      ;;
    /api/apps/app-1/releases*)
      cat "${STUB_RELEASES_JSON}"
      ;;
    *)
      echo "unexpected api path: $4" >&2; exit 2
      ;;
  esac
  exit 0
fi
if [ "$1" = "releases" ] && [ "$2" = "show" ]; then
  rid="$4"
  cat "${STUB_SHOW_DIR}/${rid}.json"
  exit 0
fi
if [ "$1" = "builds" ] && [ "$2" = "presign-asset" ]; then
  asset_id="$5"
  case "${asset_id}" in
    asset-a) echo "{\"download_url\":\"file://${STUB_FILES}/a.apk\"}" ;;
    asset-b) echo "{\"download_url\":\"file://${STUB_FILES}/b.apk\"}" ;;
    *) echo "unexpected asset: ${asset_id}" >&2; exit 2 ;;
  esac
  exit 0
fi
if [ "$1" = "releases" ] && [ "$2" = "update" ]; then
  rid="$4"
  echo '{"ok":true}'
  exit 0
fi
if [ "$1" = "releases" ] && [ "$2" = "publish" ]; then
  rid="$4"
  if [ "${STUB_PENDING_APPROVAL:-0}" = "1" ]; then
    echo '{"status":"pending_approval","approval_request_id":"req-99","next_action":"approve in Hands"}'
    exit 0
  fi
  python3 -c '
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["release"]["status"] = "active"
d["release"]["is_full"] = 0
json.dump(d, open(p, "w"))
' "${STUB_SHOW_DIR}/${rid}.json"
  echo '{"status":"active"}'
  exit 0
fi
if [ "$1" = "device-groups" ] && [ "$2" = "remove-member" ]; then
  echo '{"ok":true}'
  exit 0
fi
if [ "$1" = "releases" ] && [ "$2" = "cancel" ]; then
  echo "UNEXPECTED_CANCEL" >>"${STUB_LOG}"
  echo '{"ok":true}'
  exit 0
fi
echo "unexpected hands invocation: $*" >&2
exit 2
HANDS
chmod +x "${WORK}/bin/hands"

export PATH="${WORK}/bin:${PATH}"
export HANDS_AUTH_TOKEN="test-token"
export STUB_FILES="${WORK}/files"
export STUB_SHOW_DIR="${WORK}/show"
export STUB_RELEASES_JSON="${WORK}/releases.json"
export STUB_LOG="${WORK}/hands.log"
mkdir -p "${STUB_SHOW_DIR}"

reset_fixtures() {
  : >"${STUB_LOG}"
  cat >"${STUB_RELEASES_JSON}" <<JSON
{"releases":[
  {"id":"rel-a","channel":"preview","status":"active","is_full":1,"version_code":12800010,"version_name":"1.28.0"},
  {"id":"rel-b","channel":"preview","status":"draft","is_full":0,"version_code":12900001,"version_name":"1.29.0"}
]}
JSON
  cat >"${STUB_SHOW_DIR}/rel-a.json" <<JSON
{"release":{"id":"rel-a","channel":"preview","status":"active","is_full":1,"build_id":"build-a"},
 "build":{"id":"build-a","version_code":12800010,"version_name":"1.28.0"},
 "assets":[{"id":"asset-a","filetype":"apk","artifact_kind":"installable","arch":"arm64-v8a","size_bytes":11,"file_hash":"${SHA_A}"}]}
JSON
  cat >"${STUB_SHOW_DIR}/rel-b.json" <<JSON
{"release":{"id":"rel-b","channel":"preview","status":"draft","is_full":0,"build_id":"build-b"},
 "build":{"id":"build-b","version_code":12900001,"version_name":"1.29.0"},
 "assets":[{"id":"asset-b","filetype":"apk","artifact_kind":"installable","arch":null,"size_bytes":11,"file_hash":"${SHA_B}"}]}
JSON
}

# 1. assert-asset accepts universal (arch: null) for x86_64 and rejects arm64-v8a for x86_64.
reset_fixtures
bash "${SCRIPT}" assert-asset rel-b x86_64 "${WORK}/files/b.apk" 2>/dev/null
if bash "${SCRIPT}" assert-asset rel-a x86_64 2>/dev/null; then
  echo "FAIL: arm64-v8a-only release should not match x86_64" >&2; exit 1
fi

# 2. preview-prepare happy path (universal B, arm64-v8a A downloaded & sha256 verified, B activated device-group only).
reset_fixtures
out="$(bash "${SCRIPT}" preview-prepare rel-b grp-1 "${WORK}/out")"
grep -q '^a_release_id=rel-a$' <<<"${out}"
grep -q '^b_release_id=rel-b$' <<<"${out}"
[ -s "${WORK}/out/a/a.apk" ] && [ -s "${WORK}/out/b/b.apk" ]

# 3. preview-prepare fails clearly on pending_approval JSON.
reset_fixtures
if err="$(STUB_PENDING_APPROVAL=1 bash "${SCRIPT}" preview-prepare rel-b grp-1 "${WORK}/out" 2>&1)"; then
  echo "FAIL: pending_approval should fail closed" >&2; exit 1
fi
grep -q 'status=pending_approval, approval_request_id=req-99' <<<"${err}"

# 4. preview-prepare refuses non-preview channel, full-scope active release, and non-increasing versionCode.
reset_fixtures
python3 -c 'import json; p="'"${STUB_SHOW_DIR}"'/rel-b.json"; d=json.load(open(p)); d["release"]["channel"]="main"; json.dump(d, open(p,"w"))'
if bash "${SCRIPT}" preview-prepare rel-b grp-1 "${WORK}/out" 2>/dev/null; then
  echo "FAIL: non-preview release must be rejected" >&2; exit 1
fi

reset_fixtures
python3 -c 'import json; p="'"${STUB_SHOW_DIR}"'/rel-b.json"; d=json.load(open(p)); d["release"]["status"]="active"; d["release"]["is_full"]=1; json.dump(d, open(p,"w"))'
if bash "${SCRIPT}" preview-prepare rel-b grp-1 "${WORK}/out" 2>/dev/null; then
  echo "FAIL: full-scope active release must be rejected" >&2; exit 1
fi

reset_fixtures
python3 -c 'import json; p="'"${STUB_SHOW_DIR}"'/rel-b.json"; d=json.load(open(p)); d["build"]["version_code"]=12800010; json.dump(d, open(p,"w"))'
if bash "${SCRIPT}" preview-prepare rel-b grp-1 "${WORK}/out" 2>/dev/null; then
  echo "FAIL: versionCode <= A must be rejected" >&2; exit 1
fi

# 5. preview-cleanup removes member from group and NEVER cancels B.
reset_fixtures
bash "${SCRIPT}" preview-cleanup grp-1 dev-42
grep -q 'device-groups remove-member raft-android grp-1 --device-id dev-42' "${STUB_LOG}"
if grep -q 'UNEXPECTED_CANCEL' "${STUB_LOG}"; then
  echo "FAIL: preview-cleanup must never cancel B" >&2; exit 1
fi

echo "android_update_hands_fixture_test: PASS"
