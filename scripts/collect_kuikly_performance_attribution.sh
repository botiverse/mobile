#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/artifacts/kuikly-performance/$(date +%Y%m%d-%H%M%S)"
HDC_BIN="${HDC_BIN:-hdc}"
APP_BUNDLE="${APP_BUNDLE:-build.raft.app}"
HILOG_SECONDS="${HILOG_SECONDS:-20}"
COLLECT_HILOG=1
RUN_ANALYZER=1

usage() {
  cat <<'USAGE'
Usage:
  HDC_BIN=/path/to/hdc scripts/collect_kuikly_performance_attribution.sh [options]

Options:
  --out <dir>             Output directory.
  --app-bundle <id>       App bundle name for metadata. Default: build.raft.app.
  --hdc <path>            hdc executable. Default: $HDC_BIN or hdc.
  --hilog-seconds <n>     Seconds to stream hilog. Default: 20.
  --no-hilog             Skip live hilog capture.
  --no-analyze           Only collect files; do not run the analyzer.
  -h, --help             Show this help.

Before running:
  1. Run one focused scenario.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      OUT_DIR="$2"
      shift 2
      ;;
    --app-bundle)
      APP_BUNDLE="$2"
      shift 2
      ;;
    --hdc)
      HDC_BIN="$2"
      shift 2
      ;;
    --hilog-seconds)
      HILOG_SECONDS="$2"
      shift 2
      ;;
    --no-hilog)
      COLLECT_HILOG=0
      shift
      ;;
    --no-analyze)
      RUN_ANALYZER=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$OUT_DIR"

if ! command -v "$HDC_BIN" >/dev/null 2>&1; then
  cat >&2 <<EOF
hdc not found: $HDC_BIN

Set HDC_BIN to the DevEco hdc executable, for example:
  HDC_BIN=/Applications/DevEco-Studio.app/Contents/tools/hdc scripts/collect_kuikly_performance_attribution.sh
EOF
  exit 127
fi

{
  echo "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "root=$ROOT_DIR"
  echo "git_rev=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || true)"
  echo "git_status=$(git -C "$ROOT_DIR" status --short 2>/dev/null | wc -l | tr -d ' ') changed file(s)"
  echo "app_bundle=$APP_BUNDLE"
  echo "hdc_bin=$HDC_BIN"
} > "$OUT_DIR/metadata.txt"

"$HDC_BIN" list targets > "$OUT_DIR/hdc_targets.txt" 2>&1 || true

if [[ "$COLLECT_HILOG" -eq 1 ]]; then
  perl -e 'alarm shift; exec @ARGV' "$HILOG_SECONDS" "$HDC_BIN" shell hilog > "$OUT_DIR/hilog.txt" 2>&1 || true
fi

"$HDC_BIN" shell hidumper -h > "$OUT_DIR/hidumper_help.txt" 2>&1 || true
"$HDC_BIN" shell top -H -n 1 > "$OUT_DIR/top_threads.txt" 2>&1 || true

if [[ "$RUN_ANALYZER" -eq 1 ]]; then
  python3 "$ROOT_DIR/scripts/analyze_kuikly_performance_attribution.py" \
    --input "$OUT_DIR" \
    --output "$OUT_DIR/summary.md"
fi

echo "Kuikly performance artifacts: $OUT_DIR"
if [[ -f "$OUT_DIR/summary.md" ]]; then
  echo "Summary: $OUT_DIR/summary.md"
fi
