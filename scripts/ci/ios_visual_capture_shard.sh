#!/usr/bin/env bash
# Capture one shard of the iOS visual case list in one job-owned simulator.
#
# Usage: ios_visual_capture_shard.sh <udid> <cases.tsv> <out-dir>
#
# cases.tsv rows: caseId <TAB> pageName <TAB> pageData(json)
# Each case is a cold launch with the fixture pageData passed via
# SIMCTL_CHILD_ env — identical semantics to the original serial loop in
# visual-testing.yml, only run per-shard in parallel simulators.
# Per-case artifacts land in <out-dir> (PNGs) so shards can write disjoint
# files into a shared results tree without collision; diagnostics go to
# <out-dir>/../ios-diagnostics when the caller passes visual-testing-results/ios.
# -e: an unexpected simctl/screenshot failure must fail the shard (and the
# job), as it did in the legacy inline step; expected per-case failures are
# counted explicitly below. No pipefail: `find | head -1` may SIGPIPE find.
set -eu
udid=$1
cases_tsv=$2
out_dir=$3
diag_dir=$(dirname "$out_dir")/ios-diagnostics

app=$(find "$IOS_CAPTURE_TEMP/DerivedData/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)
test -n "$app" || { echo "no built .app under $IOS_CAPTURE_TEMP/DerivedData" >&2; exit 1; }
bundle_id=$("${PLISTBUDDY:-/usr/libexec/PlistBuddy}" -c 'Print CFBundleIdentifier' "$app/Info.plist")
echo "shard udid=$udid app=$app bundle_id=$bundle_id"
xcrun simctl install "$udid" "$app"

mkdir -p "$out_dir" "$diag_dir"
launch_log="$IOS_CAPTURE_TEMP/launch.$udid.log"
launch_pid=
stop_console() {
  if [ -n "$launch_pid" ]; then
    kill "$launch_pid" 2>/dev/null || true
    wait "$launch_pid" 2>/dev/null || true
    launch_pid=
  fi
}
trap stop_console EXIT

failed=0
ok=0
total=$(wc -l < "$cases_tsv" | tr -d ' ')
echo "shard capturing $total cases on $udid"
i=0
while IFS=$'\t' read -r case_id page_name page_data; do
  i=$((i+1))
  stop_console
  xcrun simctl terminate "$udid" "$bundle_id" 2>/dev/null || true
  : > "$launch_log"
  SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE="$page_name" \
  SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE_DATA="$page_data" \
    xcrun simctl launch --console-pty "$udid" "$bundle_id" > "$launch_log" 2>&1 &
  launch_pid=$!
  ready=0
  for _ in $(seq 1 60); do
    if grep -q "SLOCK_VISUAL_FIXTURE_READY page=$page_name" "$launch_log"; then
      ready=1; break
    fi
    sleep 1
  done
  if [ "$ready" != "1" ]; then
    echo "::warning::[$i/$total] $case_id: READY marker not seen in 60s (page=$page_name)"
    failed=$((failed+1))
    continue
  fi
  sleep 1
  # READY alone is not enough: a renderer exception (e.g. resource
  # init crash, task #421) can pop a "kuikly error" dialog AFTER the
  # first frame, turning the screenshot into a crash dialog while the
  # case still counts OK. Check the console log before accepting.
  #
  # A handled scroll-boundary rejection is different: the iOS
  # renderer deliberately logs an attributed [kuikly error] before it
  # drops a non-finite event/touch so it cannot reach JSON bridging
  # (task #499). Treating that safety diagnostic as an exception makes
  # the provider reject a healthy frame and recreates the missing-case
  # symptom even though the original NSException is gone.
  handled_scroll_drops="$(
    grep -iE '\[kuikly error\]\[KRScrollView\] non-finite scroll event value field=.* action=drop_(event|touch)' \
      "$launch_log" || true
  )"
  unhandled_kuikly_errors="$(
    grep -i 'kuikly error' "$launch_log" \
      | grep -ivE '\[kuikly error\]\[KRScrollView\] non-finite scroll event value field=.* action=drop_(event|touch)' \
      || true
  )"
  if grep -qi '\[Kuikly Exception\]' "$launch_log" \
    || [ -n "$unhandled_kuikly_errors" ]; then
    echo "::warning::[$i/$total] $case_id: renderer exception after READY (kuikly error dialog); not capturing"
    xcrun simctl io "$udid" screenshot "$diag_dir/$case_id.error.png" || true
    cp "$launch_log" "$diag_dir/$case_id.launch.log" || true
    failed=$((failed+1))
    continue
  fi
  if [ -n "$handled_scroll_drops" ]; then
    echo "[$i/$total] $case_id: handled non-finite scroll diagnostic(s); frame remains eligible"
    printf '%s\n' "$handled_scroll_drops" \
      | tee "$out_dir/$case_id.scroll-safety.log"
  fi
  xcrun simctl io "$udid" screenshot "$out_dir/$case_id.png"
  # task #441: stash any SLOCK_RICHTEXT_CAPS marker line(s) printed
  # after the first frame so the metadata step can merge per-case caps.
  grep "SLOCK_RICHTEXT_CAPS " "$launch_log" > "$out_dir/$case_id.caps.log" 2>/dev/null || true
  # task #60 diag: surface per-element inline-box advance dumps so the
  # trailing-cavity origin is visible in the job log AND the artifact.
  if grep -q "KR_INLINE_BOX_DIAG" "$launch_log"; then
    grep "KR_INLINE_BOX_DIAG" "$launch_log" | tee "$out_dir/$case_id.inlinebox-diag.log"
  fi
  echo "[$i/$total] $case_id OK"
  ok=$((ok+1))
done < "$cases_tsv"

# Machine-readable per-shard tally for the parent step's merged verdict.
# Count successes in-process: out_dir is shared across shards, so a glob
# here would count other shards' PNGs and double-report.
echo "shard_result captured=$ok failed=$failed total=$total"
printf 'captured=%s\nfailed=%s\ntotal=%s\n' "$ok" "$failed" "$total" > "$IOS_CAPTURE_TEMP/shard-result.$udid"
