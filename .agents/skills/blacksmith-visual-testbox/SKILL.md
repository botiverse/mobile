---
name: blacksmith-visual-testbox
description: >
  Run one or a few Android visual capture cases in a warm Blacksmith Testbox
  for fast development feedback. Produces non-terminal preflight evidence only.
---

# Blacksmith Android Visual Testbox

Use this workflow only for a focused Android visual development loop. It does
not replace the scheduled visual matrix, Hosted CI, DCO, physical-device,
Stamp, RC, or release gates, and it must never produce a product PASS/FAIL.

## Safety boundary

- Run every `blacksmith testbox` command from the repository root.
- Always pass an explicit immutable ref, workflow job, and short idle timeout.
- The workflow is dispatch-only and may not gain pull-request, push, schedule,
  release, upload, Pages, OIDC-write, or signing steps.
- Never print or download environment dumps, auth tokens, packages credentials,
  `/tmp/.testbox`, SSH state, adb private keys, app databases/shared prefs, or
  whole-system logcat.
- Download only the allowlisted preflight bundle described below.
- Stop the Testbox explicitly and delete any temporary exact-ref branch after
  the session.

The setup hydrates the Raft artifacts and connected-test APKs, then stops all
Gradle daemons and removes checkout credentials before the interactive action
begins. Do not add credentials inside Testbox. A change that needs a previously
uncached private dependency requires a fresh, reviewed workflow setup; it never
authorizes exposing a token interactively.

The workflow/job are:

    visual-android-testbox.yml / android_visual

GitHub workflow dispatch does not accept an arbitrary commit SHA as `--ref`.
For an exact-bound run, create a temporary immutable remote branch or tag that
points to the reviewed full SHA, use that name as `--ref`, and delete it only
after the Testbox is stopped.

## Warmup

    blacksmith testbox warmup visual-android-testbox.yml \
      --ref <immutable-exact-ref> \
      --job android_visual \
      --idle-timeout 10

The setup boots one API 34 `google_apis` x86_64 Pixel 6 Pro AVD and leaves it
running. Reuse that AVD for fast reruns, but use the canonical runner for every
capture; it clears the dedicated result directory and scoped logcat, clears the
two test packages, resets IME/window state, reapplies viewport/density/theme/
font/animation controls, reinstalls both APKs, and verifies installed hashes.
Reboot the AVD only after an IME, system-window, UID/package, or emulator anomaly.

## Build focused visual APKs

Run from the repository root through Testbox:

    blacksmith testbox run --id <ID> \
      "cd compose && ./gradlew --no-daemon --console=plain -PslockVisualFixtures=true :androidApp:assembleConnectedTest :androidApp:assembleConnectedTestAndroidTest"

This Testbox workflow deliberately has no release or rotated-signing secrets.
Its APKs are development-only inputs and are not release artifacts.

## Resolve the canonical case filter

Case IDs are resolved by scanning the canonical Android harness source. Do not
add or maintain a second case-to-method mapping table.

    blacksmith testbox run --id <ID> \
      "python3 scripts/visual_android_case_filter.py --require-testbox-eligible --cases 'library.raft-ui.segmented-control.states'"

Recommended first cases:

- harness smoke: `library.raft-ui.segmented-control.states`
- real product route: `components.settings.appearance.page`

For multiple comma-separated cases, the shared runner starts one instrumentation
process per case by default so a hung case cannot silently drop later cases.

## Capture with the shared runner

    blacksmith testbox run --id <ID> \
      'APP_APK=$(find compose/androidApp/build/outputs/apk/connectedTest -maxdepth 1 -type f -name "*.apk" | sort | head -1); TEST_APK=$(find compose/androidApp/build/outputs/apk/androidTest/connectedTest -maxdepth 1 -type f -name "*.apk" | sort | head -1); FILTER=$(python3 scripts/visual_android_case_filter.py --require-testbox-eligible --cases "components.settings.appearance.page"); bash scripts/run_android_visual_capture.sh --app-apk "$APP_APK" --test-apk "$TEST_APK" --method-filter "$FILTER" --blacksmith-cli-version "0.4.49" --output-dir visual-testing-results'

A failed capture must remain failed. The runner persists its scoped evidence and
receipt before returning a failure verdict; do not append `|| true` or otherwise
mask the exit code. `CASE_START` without `CASE_END`, zero tests, unexpected or
missing methods, failure JSON, APK identity mismatch, or incomplete screenshots/
metadata means `harness_incomplete`, not a product result.
Receipt v3 must inventory the exact PNG and metadata bytes for every completed
case, bind their capture wall/monotonic timestamps to this attempt, and freeze
the target package/UID/PID, device boot ID, and random run generation used by
the epoch-scoped logcat. Do not reuse, rename, or replace a prior run's raw
files after the receipt is written.

## Required same-exact controls

The real-route primary receipt is also the known-green receipt. Copy it locally
to `controls/known-green.json`. Then run a separate control directory with the
same actual case but an extra canonical expected method:

    blacksmith testbox run --id <ID> \
      'APP_APK=$(find compose/androidApp/build/outputs/apk/connectedTest -maxdepth 1 -type f -name "*.apk" | sort | head -1); TEST_APK=$(find compose/androidApp/build/outputs/apk/androidTest/connectedTest -maxdepth 1 -type f -name "*.apk" | sort | head -1); ACTUAL=$(python3 scripts/visual_android_case_filter.py --require-testbox-eligible --cases "components.settings.appearance.page"); EXTRA=$(python3 scripts/visual_android_case_filter.py --require-testbox-eligible --cases "library.raft-ui.segmented-control.states"); bash scripts/run_android_visual_capture.sh --app-apk "$APP_APK" --test-apk "$TEST_APK" --method-filter "$ACTUAL" --expected-method-filter "$ACTUAL,$EXTRA" --injected-fault expected_method_mismatch --blacksmith-cli-version "0.4.49" --output-dir visual-testing-known-red'

That command must return nonzero after persisting its receipt. Download only
`visual-testing-known-red/android-visual-runner-receipt.json` as
`controls/known-red.json`. It is valid only when the receipt says
`harness_incomplete`, names `expected_method_mismatch`, and contains a factual
missing/unexpected method list. Never mask it into a green product result.

## Allowlisted download

Only these paths may be downloaded:

- `visual-testing-results/android/*.png`
- `visual-testing-results/android/*.metadata.json`
- `visual-testing-results/android/*.failure.png`
- `visual-testing-results/android/*.failure.json`
- `visual-testing-results/logcat-shard-*.txt`
- `visual-testing-results/instr-rc-*.txt`
- `visual-testing-results/started-methods-*.txt`
- `visual-testing-results/ended-methods-*.txt`
- `visual-testing-results/expected-methods-*.txt`
- `visual-testing-results/actual-cases-*.txt`
- `visual-testing-results/expected-cases-*.txt`
- `visual-testing-results/actual-case-map-*.json`
- `visual-testing-results/expected-case-map-*.json`
- `visual-testing-results/instrumentation/*.txt`
- `visual-testing-results/android-visual-runner-receipt.json`
- `visual-testing-results/device-reset.txt`
- a validated `testbox-preflight-manifest.v1.json` and its `SHA256SUMS`
- local control receipts `controls/known-green.json`, `controls/known-red.json`,
  and the raw `controls/testbox-stop.txt`

Example:

    blacksmith testbox download --id <ID> visual-testing-results/ ./testbox-preflight-results/

Before sharing or attaching the bundle, run the repository manifest builder and
validator, confirm its sensitive scan passes, and confirm all downloaded files
are within the allowlist. A missing field, unknown required enum, hash mismatch,
or orphaned derived artifact rejects the entire bundle; partial ingestion is
forbidden.

## Stop

    mkdir -p ./testbox-preflight-results/controls
    { echo "testbox_id=<ID>"; blacksmith testbox stop --id <ID>; } \
      | tee ./testbox-preflight-results/controls/testbox-stop.txt

Preserve the raw explicit stop output; the manifest builder hashes it and must
find the exact Testbox ID plus a successful stop result. The Testbox ID, Actions run, workflow/job, CLI
version, exact source/ref, emulator identity, APK identities, runner receipt,
files and hash DAG belong in the preflight manifest. The manifest must keep:

    evidence_class = testbox_preflight
    terminal_eligible = false
    matrix_coverage = false
    product_verdict = not_allowed
    coordinate_id = null
