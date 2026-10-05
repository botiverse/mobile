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
      → adversarial review (fresh session, loop to convergence, 2-3 round cap)
      → CI capture on PR head → measure-first pixel verification
      → 3-column evidence (react target | before | after) embedded in PR body
      → owner review → merge
```

---

## 1. Nightly analysis (analysis half)

The nightly `Visual Testing` run publishes captures first, then a separate
`ai-analysis` job (cancellable, generation-guarded) analyzes every changed
pair and republishes under the same run id. Contracts:

- Model: the `PI_API_KEY` / `PI_MODEL` gateway (currently MiniMax-M3.1-Flash-Preview). Input =
  the pair's side-by-side PNG + case metadata + declared divergences; output =
  a JSON analysis per case-pair (`severity`, `summary[]`, `likelyCauses[]`,
  `suggestedFixes[]`, `suggestedOwner`) stored in the published site payload
  (`home-cases.json` → `pairs{}.analysis`).
- Caching: keyed on a perceptual fingerprint (32x32 grayscale, Hamming <= 4)
  plus a pixel-mismatch delta buster — NEVER on byte hashes (captures are not
  byte-stable across runs). Steady state re-analyzes only cases whose pixels
  actually changed.
- The analysis is an INDICTMENT, not a verdict: every suggestion must be
  re-derived from react source before any patch (section 3).

## 2. Candidate selection (analysis half)

Pull the latest published `home-cases.json` and filter:

1. **Scope gate (hard, owner-set): only `components.ui.*` cases** — the raft-ui
   component library. `screens.*` and page-level composites are report-only,
   never auto-patched.
2. **Class gate**: only style/token-level suggestions (color, font weight,
   spacing constants, fixture values). Semantics, layout, geometry,
   interaction → report-only.
3. **Registry gate**: skip cases whose divergence is declared in
   `platformDivergences.json` (slock repo) — those are attributed, not bugs.
4. **Worst-first** by pair similarity; then EYE-CHECK the side-by-side PNG
   before adopting (analyses can be stale: check whether a fix merged after
   the run's capture time already covers it — never double-patch).
5. **Fixture-vs-mapping classification** for state-dependent diffs: if the two
   fixtures seed DIFFERENT states, fix the fixture (react's seed is truth);
   only if the same state renders different pixels is it a mapping/render fix.

A candidate becomes one PR per blade: single value (or one react rule with its
mechanical consequences), title prefixed `visual-fix:`, sync link to
#wg-nightly-visual-fix on open.

## 3. True-value-chain patch (analysis half)

Every patch value must carry a complete chain from react source, anchored to
file:line in the commit message: component class → token definition → final
composited hex. Rules learned the hard way:

- **CSS group opacity ≠ per-part alpha.** React "disabled" states are usually
  a whole-element `opacity-N` — the browser flattens each part against what's
  beneath it INSIDE the group, then dims. On android use PRECOMPUTED OPAQUE
  composite values (over the fixture's backdrop, normally white), never live
  alpha (only exact over the page backdrop) and never `graphicsLayer` alpha
  (Kuikly drops offset-shadow children under group alpha).
- **`Color.copy(alpha=)` REPLACES alpha** — it does not multiply. Deriving a
  dimmed color from an already-translucent one this way raises it.
- Per-callsite react overrides exist (e.g. `disabled:opacity-50` utilities
  beat the component base rule) — the truth chain must state WHICH rule the
  paired fixture actually renders.
- **Blast radius census**: list every consumer of the changed
  constant/default; each must move TOWARD react under the same react rule, or
  the change is narrowed to the call site.
- **Never pin the old value in tests**; test assertions may only pin
  react-derived values (with the derivation in a comment).

---

## 4. Capture on the PR head (capture/verify half)

Visual capture runs the `Visual Testing` workflow (`.github/workflows/visual-testing.yml`)
against the PR branch head, so the “after” reflects exactly the proposed fix.

```bash
gh workflow run visual-testing.yml --repo botiverse/mobile \
  --ref <PR-head-branch> \
  -f case_filter="components.ui.slug-input.states,components.ui.button.states" \
  [-f capture_locale="zh-CN"] # also accepts en or ja
```

- **`case_filter`** (comma-separated caseIds) drives BOTH the React `--case`
  and the Android instrumentation method filter — a fast targeted round instead
  of the full manifest. The Android build maps each caseId to its `@Test` method
  by finding the method whose body contains the `"<caseId>"` string literal
  (`Derive android method filter` step). Targeted runs use shard 0 only.
- **`capture_locale`** (optional: `en`, `zh-CN`, or `ja`) forces the Android app
  KmmResource locale for the capture — see §6.
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
at process start (CI runs English by default). For a localized capture, the
harness sets the locale **before the page’s first composition** — the KmmResource
global has no auto-recompose semantics — and restores it per case in `finally`,
so cases that don’t set a locale keep the English baseline byte-for-byte:

- driven by the `capture_locale` instrumentation arg (`zh-CN`→SimplifiedChinese,
  `ja`→Japanese, `en`→English, empty→no-op; unsupported tags fail closed);
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

### 7.1 Scripted verification (`scripts/visual_verify.py`)

The mechanical part of §7 is scripted so **clean blades self-verify without a
human agent** (M3 / CI can run it); the judgment stays with the reviewer.

```bash
python3 scripts/visual_verify.py --spec spec.json   # or spec on stdin
```

The spec asserts explicit targets against a case:

```json
{ "case": "components.ui.slug-input.states",
  "after":  {"run": 29143252272},          // run-id auto-downloads the android artifact (or {"img": path})
  "before": "gh-pages", "react": "gh-pages", // pull latest/android + latest/react
  "density": 3.0, "region": "bottom-half",
  "colors": [{"name":"tile","hex":"FFF7DD","tol":6,"min_count":500}, ...],
  "diff": true,
  "glyph_center": {"container":"cream-tile-left"} }
```

It emits per-check `pass/fail`, the diff bbox + top `#before→#after` transitions,
geometry in dp, and glyph-vs-container centering — a `PASS`/`FAIL`/`REVIEW`
verdict. **The agent still owns:** authoring the target spec (which values to
assert), interpreting a `REVIEW`/anomaly, root-cause, and — critically —
**provenance**: the script prints a reminder to confirm the measured build is
the PR's pinned gitlink sha, not an ambient artifact (§9-adjacent; see the #568
double-correction it prevents). Escalate any non-`PASS` to the reviewing agent.

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

After each PR opens (and after EVERY code change to it), an independent
reviewer session with NO shared context — only the PR diff and the two repos —
tries to REFUTE the patch: re-derive the truth chain from react source,
recompute every hex, re-census the blast radius, check for test-pinning and
registry-dodging.

- Verdicts (`REFUTED` / `WEAKENED` / `UNREFUTED` / `CONVERGED`) are posted as
  PR comments — the durable handoff artifact; the loop survives any session.
- Routing: REFUTED → PR to draft, human review. WEAKENED with concrete
  catches → fold each confirmed catch as a follow-up commit → RE-REVIEW
  (code changed = new round). UNREFUTED/CONVERGED → verification round
  (section 4).
- Cap: 2-3 folding rounds; if still finding code-level issues, stop and
  escalate to a human. (Reference sample: PR #568 ran 4 rounds — caught a
  missed edit, refuted the patch's own construction, confirmed convergence,
  then vetted a follow-up fixture commit.)
- Borderline scope (a change straddling `components.ui.*` and screen files)
  is never self-adjudicated: present the evidence and let the owner decide.

**Merge**: only after verification PASS + 3-column evidence embedded in the PR
body + owner (artin) approval. Interactive batches may merge on his explicit
nod; nightly batches stay open for morning review.

### MiniMax preview compatibility

The QA and patrol agents default to `MiniMax-M3.1-Flash-Preview`. The pinned
pi-ai SDK catalogs (0.80.10 / 0.81.1) do not yet list this ID, so
`scripts/qa/pi_model.mjs` supplies it using the same provider transport and
conservative output limit as M3. The request always sends the preview ID; it
does not fall back to an M3 invocation. Existing registered models and explicit
provider/model overrides remain supported.

[MiniMax's invocation contract](https://platform.minimax.io/docs/guides/text-generation)
specifies the same Anthropic endpoint and mandatory thinking for the preview.
The adapter preserves text/image input and avoids disabling thinking. The
preview has no published per-token rate in the catalog: adapter cost fields
are zero placeholders, **not a free-service claim**; token counts remain real.
Account access requires M Plan/Code. A local SDK payload check proves request
construction only, not account entitlement or a successful live model call.

Repository variables `PI_MODEL` / `PI_REVIEW_MODEL` override workflow defaults;
remove or update an old explicit M3 value when enabling the new default in CI.
