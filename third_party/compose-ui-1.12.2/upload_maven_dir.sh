#!/usr/bin/env bash
# Upload every file of a staged Maven directory tree to a Maven repository with HTTP PUT.
#
#   upload_maven_dir.sh STAGING_DIR
#
# Environment: MAVEN_URL (repository root), MAVEN_USER (any non-empty user name),
# MAVEN_PASS (the token used as the password). Credentials go to curl through a 0600 netrc file
# in a private temp dir (never on a command line, never echoed) that is removed on exit.
#
# Releases are immutable: if the .pom of an artifact version already exists remotely, the upload
# fails before any file is written. Binaries (and their checksums) go first, the .pom and .module
# last, so a consumer never sees metadata whose files are missing. maven-metadata.xml is not
# written (consumers pin the exact version).
set -euo pipefail

staging="${1:?usage: upload_maven_dir.sh STAGING_DIR}"
: "${MAVEN_URL:?MAVEN_URL is required}" "${MAVEN_USER:?MAVEN_USER is required}" "${MAVEN_PASS:?MAVEN_PASS is required}"
base="${MAVEN_URL%/}"
case "$base" in https://*) ;; *) echo "upload: MAVEN_URL must be https" >&2; exit 1 ;; esac
host="${base#https://}"; host="${host%%/*}"; host="${host%%:*}"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
umask 077
printf 'machine %s\nlogin %s\npassword %s\n' "$host" "$MAVEN_USER" "$MAVEN_PASS" > "$workdir/netrc"
unset MAVEN_PASS

curl_auth() { curl --silent --show-error --netrc-file "$workdir/netrc" --retry 3 --retry-all-errors "$@"; }

cd "$staging"
# Refuse to overwrite a published version.
while IFS= read -r pom; do
  code="$(curl_auth --output /dev/null --write-out '%{http_code}' --head "$base/${pom#./}")" || code=000
  case "$code" in
    200) echo "upload: $base/${pom#./} already exists; bump the -raft.N version instead of overwriting" >&2; exit 1 ;;
    404|401|403) ;;  # 401/403 on HEAD: some repositories hide existence; the PUT below decides.
    *) echo "upload: unexpected HTTP $code checking ${pom#./}" >&2; exit 1 ;;
  esac
done < <(find . -type f -name '*.pom' | sort)

# Order: binaries + their checksums, then .pom/.module + their checksums.
{
  find . -type f ! -name '*.pom*' ! -name '*.module*' | sort
  find . -type f \( -name '*.pom*' -o -name '*.module*' \) | sort
} | while IFS= read -r file; do
  path="${file#./}"
  code="$(curl_auth --output /dev/null --write-out '%{http_code}' --upload-file "$file" "$base/$path")" || code=000
  case "$code" in
    200|201|204) echo "PUT $path -> $code" ;;
    *) echo "upload: PUT $path failed with HTTP $code" >&2; exit 1 ;;
  esac
done

# Read back what consumers resolve first and compare checksums.
while IFS= read -r file; do
  path="${file#./}"
  remote="$(curl_auth --fail "$base/$path" | shasum -a 256 | cut -d' ' -f1)"
  local_sum="$(shasum -a 256 "$file" | cut -d' ' -f1)"
  [ "$remote" = "$local_sum" ] || { echo "upload: read-back mismatch for $path" >&2; exit 1; }
  echo "verified $path"
done < <(find . -type f \( -name '*.pom' -o -name '*.module' -o -name '*.klib' -o -name '*.aar' \) | sort)
