# Visual-fix bot runbook

How a React↔Android/iOS visual divergence goes from nightly detection to a
merged, pixel-verified fix. The pipeline has two halves that hand off to each
other:

- **Analysis half** — nightly detection → candidate selection → patch. _(owner:
  CC-Wow2 / fix-bot; sections marked “(analysis half)” are stubs to be filled.)_
- **Capture / verification half** — deterministic capture, measure-first pixel
  acceptance, 3-column evidence. _(owner: XiShi / capture-CI line; documented
  below.)_

Pipeline at a glance:

```
nightly AI analysis → candidate selection → true-value-chain patch
      → CI capture on PR head → measure-first pixel verification
      → 3-column evidence (react target | before | after)
      → adversarial review (2–4 rounds) → merge
```

---

## 1. Nightly analysis (analysis half)

> (analysis half) — nightly AI diff analysis over the published run: what it
> reads, how divergences are scored, output format. To be filled by CC-Wow2.

## 2. Candidate selection (analysis half)

> (analysis half) — worst-first over `vt_sweep` + `platformDivergences.json`;
> fixture-vs-mapping classification; how a candidate becomes a PR. To be filled
> by CC-Wow2.

## 3. True-value-chain patch (analysis half)

> (analysis half) — deriving the react true value (e.g. group-opacity flattened
> composites, token lookups) and the minimal call-site change. To be filled by
> CC-Wow2.

---

## 4. Capture on the PR head (capture/verify half)

Visual capture runs the `Visual Testing` workflow (`.github/workflows/visual-testing.yml`)
against the PR branch head, so the “after” reflects exactly the proposed fix.

```bash
gh workflow run visual-testing.yml --repo botiverse/mobile \
  --ref <PR-head-branch> \
  -f case_filter="components.ui.slug-input.states,components.ui.button.states" \
  [-f capture_locale="zh-CN"]
```

- **`case_filter`** (comma-separated caseIds) drives BOTH the React `--case`
  and the Android instrumentation method filter — a fast targeted round instead
  of the full manifest. The Android build maps each caseId to its `@Test` method
  by finding the method whose body contains the `"<caseId>"` string literal
  (`Derive android method filter` step). Targeted runs use shard 0 only.
- **`capture_locale`** (optional, e.g. `zh-CN`) forces the app KmmResource locale
  for the capture — see §6.
- Cases are **deterministic visual fixtures** (`@Page(..., supportInLocal = true)`
  in the `visualFixtures` source set), NOT production session routes. A page that
  depends on persisted session / auth gate (e.g. `compose_slock_app_shell`) is
  **not** a stable capture target — add a dedicated fixture that renders the
  content directly (e.g. `compose_app_shell_restore_splash_visual`).

### Reading “failure” correctly

A run can show **red while the Android capture fully succeeded**: if `case_filter`
lists caseIds that have no React equivalent, the React `Upload react results`
step finds nothing and exits 1, marking the whole workflow failed and skipping
the aggregate report. Check the individual jobs — the `visual-results-android-shard-0`
artifact still holds the images. Don’t read the top-level red as “capture failed.”

## 5. Harvest artifacts

```bash
gh run download <run-id> --repo botiverse/mobile -n visual-results-android-shard-0 -D <dir>
```

Each case yields `<caseId>.png` + `<caseId>.metadata.json`.

## 6. Locale capture (`capture_locale`)

The app seeds the global `StringDesc.localeType` from the persisted `AppLocale`
at process start (CI runs English by default). For a zh capture, the harness
sets the locale **before the page’s first composition** — the KmmResource global
has no auto-recompose semantics — and restores it per case in `finally`, so cases
that don’t set a locale keep the English baseline byte-for-byte:

- driven by the `capture_locale` instrumentation arg (`zh-CN`→SimplifiedChinese,
  `en`→English, empty→no-op);
- `StringDesc` usage is confined to a shared `commonMain` helper
  (`VisualCaptureLocaleSupport.kt`, `AppLocale`-only surface) because the
  `androidTest` module has no KmmResource `StringDesc` on its classpath;
- metadata records `appLocale` / `stringDescLocale` for locale-labelled evidence.

**Scope note:** artin’s standing directive is that **pure i18n copy changes**
(localizing a string, adding an MR resource) are verified by the i18n/compile
gates (KmmResourceI18nTest, XML/key parity), **not** by visual capture. Only
dispatch a zh capture when the change is layout/geometry, or when artin/the owner
explicitly asks for pixel confirmation.

## 7. Measure-first pixel verification (capture/verify half)

Never eyeball a fix. Measure the specific target values:

- **Colors** — sample the changed element and assert the exact composite. React
  `opacity-N` over a background flattens to a known RGB, e.g. black `opacity-40`
  over white = `0.6×255 = 153 = #999999`. Confirm the after hits it (small
  tolerance for AA).
- **Geometry** — measure in device px and convert at the run’s density (CI
  emulator is 480dpi → density 3.0 → 1dp = 3px). E.g. a 42dp row = 126px;
  a row top at 76dp = 228px.
- **Isolate the change** — a before→after pixel diff over the whole image shows
  exactly which pixels moved and their `#before → #after` transitions; this both
  proves the fix landed AND that unrelated regions (e.g. enabled states) are
  byte-unchanged.
- **Distinguish data from chrome** — fixture data (server names, emails,
  usernames) is not “residual English”; only untranslated UI chrome is.

Sub-pixel deltas (±1–2px at density 3.0) are expected rounding. Persistent
geometry residuals that are accepted practice elsewhere (e.g. the android 42dp
border-box vs react 44px, shared with the #549 form-field family) are logged
**report-only**, not treated as blockers.

## 8. Three-column evidence

Evidence is a per-case triptych **`react target | before | after`**:

- **react target** — `gh-pages:visual-testing/latest/react/<caseId>.png`
- **before** — `gh-pages:visual-testing/latest/android/<caseId>.png` (pre-fix
  baseline; absent for a brand-new case)
- **after** — the PR-head run’s `<caseId>.png`

Publish the crops to `gh-pages` under a run-scoped path (e.g.
`visual-fix-evidence/…`, not `latest/`) and embed the triptych + the measured
values in the PR body so the review is self-contained.

## 9. Data-integrity lesson: exact-name artifact cleanup

`VisualAndroidArtifacts` originally cleaned per-case artifacts by
`startsWith(caseId)` / `LIKE 'caseId%'` prefix match. When one caseId is a
**prefix** of another (e.g. `components.auth.forgot-password` vs
`…forgot-password.sent`), the shorter case’s startup cleanup — run later in the
shard by `MethodSorter` order — deletes the longer case’s already-written
`.png`/`.metadata.json`, **silently dropping it** from the pulled artifact
(observed: 6 cases dispatched, 5 pulled, all `CASE_END status=OK` in logcat).

Fix (PR #577): match the **exact** per-case filenames (`.png`, `.metadata.json`,
`.failure.png`, `.failure.json`) instead of any prefix. **Rule:** any harness
cleanup keyed on caseId must use exact names — never a prefix — or new
prefix-type caseIds will silently lose images by run order.

## 10. Adversarial review & merge (analysis half)

> (analysis half) — independent skeptic rounds (drives fix to convergence over
> 2–4 rounds); commit-message arithmetic gets re-derived; every round in the PR
> comments; scope rules (e.g. `components.ui.*`) gate borderline candidates to an
> artin decision. To be filled by CC-Wow2.
