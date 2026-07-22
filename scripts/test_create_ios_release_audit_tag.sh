#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="${ROOT_DIR}/scripts/create_ios_release_audit_tag.sh"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

REMOTE="${WORK_DIR}/remote.git"
REPO="${WORK_DIR}/repo"
FAKE_RAFT="${WORK_DIR}/raft"
APPS_JSON="${WORK_DIR}/apps.json"
RELEASE_JSON="${WORK_DIR}/release.json"
UPLOAD_JSON="${WORK_DIR}/upload.json"
STATUS_JSON="${WORK_DIR}/status.json"
BAD_RELEASE_JSON="${WORK_DIR}/release-bad.json"
BAD_UPLOAD_JSON="${WORK_DIR}/upload-bad.json"
NOISY_UPLOAD_JSON="${WORK_DIR}/upload-noisy.json"
BAD_STATUS_JSON="${WORK_DIR}/status-bad.json"

VERSION="9.9.9"
BUILD_NUMBER="9999999"
RELEASE_BRANCH="release-1.6.0"
APP_ID="00000000-0000-4000-8000-000000000000"
HANDS_BUILD="11111111-1111-4111-8111-111111111111"
HANDS_RELEASE="22222222-2222-4222-8222-222222222222"
ASC_BUILD_UPLOAD="33333333-3333-4333-8333-333333333333"
ASC_BUILD_ID="44444444-4444-4444-8444-444444444444"
FABRICATED_HANDS_BUILD="55555555-5555-4555-8555-555555555555"
IPA_SHA256="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

expect_fail() {
  if "$@" >"${WORK_DIR}/unexpected-success.log" 2>&1; then
    cat "${WORK_DIR}/unexpected-success.log" >&2
    fail "command unexpectedly succeeded: $*"
  fi
}

git init --bare --initial-branch="${RELEASE_BRANCH}" "${REMOTE}" >/dev/null
git init --initial-branch="${RELEASE_BRANCH}" "${REPO}" >/dev/null
git -C "${REPO}" config user.name "Release Test"
git -C "${REPO}" config user.email "release-test@mail.build"
printf 'release source\n' >"${REPO}/source.txt"
git -C "${REPO}" add source.txt
git -C "${REPO}" commit -m "release source" >/dev/null
git -C "${REPO}" remote add origin "${REMOTE}"
git -C "${REPO}" push -u origin "${RELEASE_BRANCH}" >/dev/null
SOURCE_COMMIT="$(git -C "${REPO}" rev-parse HEAD)"

git -C "${REPO}" tag -a "v${VERSION}" -m "existing Android audit tag" "${SOURCE_COMMIT}"
ANDROID_TAG_OBJECT="$(git -C "${REPO}" rev-parse "v${VERSION}")"

git -C "${REPO}" switch -c side >/dev/null
printf 'not in release\n' >"${REPO}/side.txt"
git -C "${REPO}" add side.txt
git -C "${REPO}" commit -m "side source" >/dev/null
SIDE_COMMIT="$(git -C "${REPO}" rev-parse HEAD)"
git -C "${REPO}" switch "${RELEASE_BRANCH}" >/dev/null

cat >"${APPS_JSON}" <<JSON
{
  "ok": true,
  "data": {
    "service": "hands-test",
    "action": "list-apps",
    "status": 200,
    "result": {
      "apps": [
        {"id": "${APP_ID}", "slug": "raft-ios", "name": "Raft iOS"}
      ]
    }
  }
}
JSON

cat >"${RELEASE_JSON}" <<JSON
{
  "ok": true,
  "data": {
    "service": "hands-test",
    "action": "get-release",
    "status": 200,
    "result": {
      "release": {
        "id": "${HANDS_RELEASE}",
        "app_id": "${APP_ID}",
        "build_id": "${HANDS_BUILD}",
        "product_type": "ios-ipa",
        "status": "draft"
      },
      "build": {
        "id": "${HANDS_BUILD}",
        "app_id": "${APP_ID}",
        "product_type": "ios-ipa",
        "status": "succeeded",
        "version_name": "${VERSION}",
        "version_code": ${BUILD_NUMBER},
        "provenance_json": {
          "source_commit": "${SOURCE_COMMIT}",
          "source_branch": "${RELEASE_BRANCH}"
        },
        "build_metadata_json": {
          "bundle_id": "build.raft.app",
          "version_name": "${VERSION}",
          "version_code": "${BUILD_NUMBER}",
          "source_commit": "${SOURCE_COMMIT}",
          "source_branch": "${RELEASE_BRANCH}",
          "ipa": {"sha256": "${IPA_SHA256}"},
          "ios": {"signed": true}
        }
      },
      "assets": [
        {
          "build_id": "${HANDS_BUILD}",
          "artifact_kind": "installable",
          "platform": "ios",
          "filetype": "ipa",
          "file_hash": "${IPA_SHA256}",
          "metadata_json": {"signed": true}
        }
      ]
    }
  }
}
JSON

cat >"${UPLOAD_JSON}" <<JSON
{
  "ok": true,
  "data": {
    "service": "hands-test",
    "action": "get-testflight-upload-status",
    "status": 200,
    "result": {
      "build_upload_id": "${ASC_BUILD_UPLOAD}",
      "state": {
        "state": "COMPLETE",
        "errors": [],
        "warnings": [],
        "infos": []
      },
      "version": "${VERSION}",
      "build_number": "${BUILD_NUMBER}"
    }
  }
}
JSON

cat >"${STATUS_JSON}" <<JSON
{
  "ok": true,
  "data": {
    "service": "hands-test",
    "action": "get-testflight-publish-status",
    "status": 200,
    "result": {
      "hands_build_id": "${HANDS_BUILD}",
      "bundle_id": "build.raft.app",
      "asc_build_id": "${ASC_BUILD_ID}",
      "version": "${VERSION}",
      "build_number": "${BUILD_NUMBER}",
      "processing_state": "VALID",
      "expired": false,
      "state": "ready"
    }
  }
}
JSON

python3 - "${RELEASE_JSON}" "${BAD_RELEASE_JSON}" <<'PY'
import json
import sys

source, target = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["data"]["result"]["build"]["build_metadata_json"]["ipa"]["sha256"] = "b" * 64
with open(target, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY

python3 - "${UPLOAD_JSON}" "${BAD_UPLOAD_JSON}" <<'PY'
import json
import sys

source, target = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["data"]["result"]["state"]["state"] = "PROCESSING"
with open(target, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY

python3 - "${UPLOAD_JSON}" "${NOISY_UPLOAD_JSON}" <<'PY'
import json
import sys

source, target = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["data"]["result"]["state"]["warnings"] = [{"code": "TEST_WARNING"}]
with open(target, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY

python3 - "${STATUS_JSON}" "${BAD_STATUS_JSON}" <<'PY'
import json
import sys

source, target = sys.argv[1:]
with open(source, encoding="utf-8") as handle:
    payload = json.load(handle)
payload["data"]["result"]["processing_state"] = "PROCESSING"
with open(target, "w", encoding="utf-8") as handle:
    json.dump(payload, handle)
PY

cat >"${FAKE_RAFT}" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "integration" ] && [ "${2:-}" = "login" ]; then
  exit 0
fi
if [ "${1:-}" != "integration" ] || [ "${2:-}" != "invoke" ]; then
  echo "unexpected fake Raft command: $*" >&2
  exit 2
fi

action=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --action)
      action="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

case "${action}" in
  list-apps)
    cat "${FAKE_RAFT_APPS_JSON}"
    ;;
  get-release)
    cat "${FAKE_RAFT_RELEASE_JSON}"
    ;;
  get-testflight-upload-status)
    cat "${FAKE_RAFT_UPLOAD_JSON}"
    ;;
  get-testflight-publish-status)
    cat "${FAKE_RAFT_STATUS_JSON}"
    ;;
  *)
    echo "unexpected fake Raft action: ${action}" >&2
    exit 2
    ;;
esac
SH
chmod +x "${FAKE_RAFT}"

COMMON_ARGS=(
  --version "${VERSION}"
  --build "${BUILD_NUMBER}"
  --source "${SOURCE_COMMIT}"
  --release-branch "${RELEASE_BRANCH}"
  --hands-build "${HANDS_BUILD}"
  --hands-release "${HANDS_RELEASE}"
  --asc-build-upload "${ASC_BUILD_UPLOAD}"
)

run_helper() {
  env \
    SLOCK_RELEASE_REPO="${REPO}" \
    RAFT_CLI="${FAKE_RAFT}" \
    HANDS_SERVICE="hands-test" \
    FAKE_RAFT_APPS_JSON="${APPS_JSON}" \
    FAKE_RAFT_RELEASE_JSON="${FAKE_RAFT_RELEASE_JSON:-${RELEASE_JSON}}" \
    FAKE_RAFT_UPLOAD_JSON="${FAKE_RAFT_UPLOAD_JSON:-${UPLOAD_JSON}}" \
    FAKE_RAFT_STATUS_JSON="${FAKE_RAFT_STATUS_JSON:-${STATUS_JSON}}" \
    "${HELPER}" "${COMMON_ARGS[@]}" "$@"
}

run_helper >"${WORK_DIR}/check-only.log"
grep -q "mode: check-only" "${WORK_DIR}/check-only.log" || fail "check-only receipt missing"
if git -C "${REPO}" show-ref --verify --quiet "refs/tags/ios-v${VERSION}-b${BUILD_NUMBER}"; then
  fail "check-only mode created a local tag"
fi
[ "$(git -C "${REPO}" rev-parse "v${VERSION}")" = "${ANDROID_TAG_OBJECT}" ] ||
  fail "check-only mode changed the existing Android tag"

FAKE_RAFT_RELEASE_JSON="${BAD_RELEASE_JSON}" expect_fail run_helper
FAKE_RAFT_UPLOAD_JSON="${BAD_UPLOAD_JSON}" expect_fail run_helper
FAKE_RAFT_UPLOAD_JSON="${NOISY_UPLOAD_JSON}" expect_fail run_helper
FAKE_RAFT_STATUS_JSON="${BAD_STATUS_JSON}" expect_fail run_helper

expect_fail env \
  SLOCK_RELEASE_REPO="${REPO}" \
  RAFT_CLI="${FAKE_RAFT}" \
  HANDS_SERVICE="hands-test" \
  FAKE_RAFT_APPS_JSON="${APPS_JSON}" \
  FAKE_RAFT_RELEASE_JSON="${RELEASE_JSON}" \
  FAKE_RAFT_UPLOAD_JSON="${UPLOAD_JSON}" \
  FAKE_RAFT_STATUS_JSON="${STATUS_JSON}" \
  "${HELPER}" \
  --version "${VERSION}" \
  --build "${BUILD_NUMBER}" \
  --source "${SOURCE_COMMIT}" \
  --release-branch "${RELEASE_BRANCH}" \
  --hands-build "${FABRICATED_HANDS_BUILD}" \
  --hands-release "${HANDS_RELEASE}" \
  --asc-build-upload "${ASC_BUILD_UPLOAD}"

expect_fail env \
  SLOCK_RELEASE_REPO="${REPO}" \
  RAFT_CLI="${FAKE_RAFT}" \
  "${HELPER}" \
  --version "${VERSION}" \
  --build "${BUILD_NUMBER}" \
  --source "${SOURCE_COMMIT}" \
  --release-branch "${SOURCE_COMMIT}" \
  --hands-build "${HANDS_BUILD}" \
  --hands-release "${HANDS_RELEASE}" \
  --asc-build-upload "${ASC_BUILD_UPLOAD}"

expect_fail env \
  SLOCK_RELEASE_REPO="${REPO}" \
  RAFT_CLI="${FAKE_RAFT}" \
  "${HELPER}" \
  --version "${VERSION}" \
  --build "${BUILD_NUMBER}" \
  --source "${SIDE_COMMIT}" \
  --release-branch "${RELEASE_BRANCH}" \
  --hands-build "${HANDS_BUILD}" \
  --hands-release "${HANDS_RELEASE}" \
  --asc-build-upload "${ASC_BUILD_UPLOAD}"

expect_fail env \
  SLOCK_RELEASE_REPO="${REPO}" \
  RAFT_CLI="${FAKE_RAFT}" \
  "${HELPER}" \
  --version "${VERSION}" \
  --build "${BUILD_NUMBER}" \
  --source "${SOURCE_COMMIT:0:12}" \
  --release-branch "${RELEASE_BRANCH}" \
  --hands-build "${HANDS_BUILD}" \
  --hands-release "${HANDS_RELEASE}" \
  --asc-build-upload "${ASC_BUILD_UPLOAD}"

GIT_COMMITTER_NAME="Release Test" \
GIT_COMMITTER_EMAIL="release-test@mail.build" \
run_helper --confirm-push >"${WORK_DIR}/push.log"
REMOTE_TARGET="$(git ls-remote --tags "${REMOTE}" "refs/tags/ios-v${VERSION}-b${BUILD_NUMBER}^{}" | awk '{print $1}')"
[ "${REMOTE_TARGET}" = "${SOURCE_COMMIT}" ] || fail "remote peeled target mismatch"
TAG_TEXT="$(git -C "${REPO}" cat-file -p "refs/tags/ios-v${VERSION}-b${BUILD_NUMBER}")"
grep -q "IPA-SHA256: ${IPA_SHA256}" <<<"${TAG_TEXT}" || fail "tag is missing IPA identity"
grep -q "ASC-Build-Upload: ${ASC_BUILD_UPLOAD}" <<<"${TAG_TEXT}" || fail "tag is missing upload id"
grep -q "ASC-Upload-State: COMPLETE" <<<"${TAG_TEXT}" || fail "tag is missing upload state"
grep -q "ASC-Build-ID: ${ASC_BUILD_ID}" <<<"${TAG_TEXT}" || fail "tag is missing processed build id"
grep -q "ASC-Processing-State: VALID" <<<"${TAG_TEXT}" || fail "tag is missing processing state"
[ "$(git -C "${REPO}" rev-parse "v${VERSION}")" = "${ANDROID_TAG_OBJECT}" ] ||
  fail "push mode changed the existing Android tag"

expect_fail run_helper
git -C "${REPO}" tag -d "ios-v${VERSION}-b${BUILD_NUMBER}" >/dev/null
expect_fail run_helper

echo "create_ios_release_audit_tag: PASS"
