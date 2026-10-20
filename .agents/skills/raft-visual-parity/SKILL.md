---
name: raft-visual-parity
description: Use when checking or fixing React-web vs Android/iOS visual parity — dispatching visual-testing.yml with case_filter, adding a visual case/fixture, harvesting and reading diff artifacts, measuring a fix, and deciding whether a residual (text anti-aliasing, sub-pixel) is a bug. For a single-case dev loop on a warm VM see blacksmith-visual-testbox.
---

# Raft visual parity

React web is the baseline. `.github/workflows/visual-testing.yml` captures
React (playwright) and Android (connected test), optionally iOS, diffs provider
pairs and publishes https://botiverse.github.io/mobile/visual-testing/.

Detail: `docs/visual-fix-bot.md` (capture, measure, triptych),
`docs/visual-testing-android-provider.md` (CLI shim, publish target),
`docs/kb/mobile-visual-evidence-ceiling.md` (what a screenshot proves),
AGENTS.md "Visual Testing (Cross-Provider Parity)".

## 1. Find the case

- Android cases: `@Test` methods in
  `compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt`;
  each body holds the literal case id (`"components.ui.button.states"`) and a
  `pageName`.
- Fixture pages: `@Page("compose_..._visual", supportInLocal = true)` under
  `compose/shared/src/visualFixtures/kotlin/build/raft/app/` (compiled only with
  `-PslockVisualFixtures=true`).
- React side + declared divergences: `packages/visual-testing/shared/sharedCases.json`
  in `botiverse/slock`.
- List the Android set / map ids to methods:
  ```bash
  python3 scripts/visual_android_case_filter.py --all --format case-ids
  python3 scripts/visual_android_case_filter.py --cases <id1,id2> --format methods
  ```

## 2. Add a case (when none exists)

1. Fixture page that renders the real production composable with typed fixture
   state — never a production route that needs a session/auth gate.
2. Regenerate the registry: `python3 scripts/cmp_generate_page_registry.py`.
3. Add a `@Test` method whose body contains the exact case id literal. Case ids
   must not be prefixes of each other's per-case files (cleanup is exact-name,
   see visual-fix-bot §9).
4. Add the React counterpart in slock `sharedCases.json` (separate PR there) or
   expect the targeted run's React upload step to go red (see §4).

## 3. Dispatch a targeted round on your PR head

```bash
gh workflow run visual-testing.yml --repo botiverse/mobile \
  --ref <pr-branch> \
  -f case_filter="components.ui.button.states,screens.thread.header" \
  [-f capture_locale=zh-CN] [-f include_ios=true] [-f slock_source_ref=staging]
```

- `case_filter` (comma-separated ids) drives both React `--case` and the
  Android instrumentation filter; targeted rounds use Android shard 0.
- Leave `publish=false` on PR rounds. Never set `allow_case_drop` unless a case
  is being retired on purpose.
- `include_ios` costs a macOS runner; off by default, on in the nightly.
- Pure copy/i18n changes are verified by i18n tests, not captures; only capture
  locales for layout changes or when the owner asks.

## 4. Read the result

- A red run is not necessarily a failed capture: an id with no React equivalent
  makes `Upload react results` exit 1. Check the per-job status.
- Download artifacts:
  ```bash
  gh run download <run-id> --repo botiverse/mobile -n visual-results-android-shard-0 -D /tmp/vis
  ```
  Each case: `<caseId>.png` + `<caseId>.metadata.json`. Logs:
  `visual-logcat-shard-0`, `visual-harness-shard-0`.
- Baselines: `gh-pages:visual-testing/latest/{react,android,ios}/<caseId>.png`.

## 5. Decide: real diff or residual

Compare at the same density/theme/crop. Name the component property that
changed; if you cannot, it is not yet a finding.

Residual (report-only, never a blocker, never "fixed" by tolerance tuning):
- text anti-aliasing / sub-pixel glyph rasterisation between Chromium and
  Skia (Android text already follows web: CSS line box, subpixel positioning,
  Space Grotesk — PR #3263), 1px AA on rounded corners/diagonals;
- ±1–2px rounding at density 3.0 (CI emulator 480dpi: 1dp = 3px);
- accepted geometry residuals documented in visual-fix-bot §7.

Real (fix it): spacing step, border width/colour, radius class, fill token,
missing/extra element, font weight/size/tracking, clipping or overlap.

Known intentional platform differences go in the case manifest as
`declaredDivergences`; they are never subtracted from Different counts.

## 6. Prove the fix

- Measure, don't eyeball: `python3 scripts/visual_verify.py --spec spec.json`
  (colours, diff bbox, geometry in dp). Confirm the measured image came from
  your PR head run.
- Put a `react target | before | after` triptych and the measured values in the
  PR body. Commit subject scope: `fix(visual): ...`.
- Report bounded claims ("matches under light theme, fixture data; iOS not
  captured"), per `docs/kb/declaring-your-verification-ceiling.md`.
