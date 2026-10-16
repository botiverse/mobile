#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/raft-external-link-tests.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
xcrun swiftc "$SCRIPT_DIR/../iosApp/RaftIosExternalLinkIngress.swift" \
  "$SCRIPT_DIR/test_external_link_ingress.swift" -o "$BUILD_DIR/ingress-tests"
"$BUILD_DIR/ingress-tests"
