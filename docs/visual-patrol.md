# Visual Patrol — daily drift inspection → GitHub issues

A daily, autonomous **inspection (巡检)** of cross-platform visual parity. It runs after
the nightly capture + analysis, turns each **new** drift into a GitHub issue with
evidence, and leaves a triaged queue for the morning. Verification is mechanical and
needs no human; the fix decision stays with a person (or the semi-auto M3 loop).

## Why

Visual drifts are found nightly but only surfaced as a report a human must read. At the
volume this repo moves, per-PR / per-morning manual pixel checks don't scale. The patrol
files the drift as trackable work automatically, so the morning starts from a queue, not a
scan.

## What runs

`scripts/visual_patrol.mjs`, driven by `.github/workflows/visual-patrol.yml`:

1. **Read** the nightly analysis `visual-testing/latest/analysis/<comparison>.json` from
   `origin/gh-pages` (the same AI analysis the site uses).
2. **Filter** to real regressions:
   - `status == ready`, no analysis `error`
   - `severity >= threshold` (default `medium`)
   - **excludes declared/intentional divergences** (`intentional` non-empty) — known
     cross-platform differences are never treated as regressions
   - has an actual divergence `summary`
3. **Dedupe** against already-open patrol issues by `caseId + comparison + perceptual
   fingerprint` (the analysis `imageHash`, Hamming distance ≤ 4 — the same tolerance the
   analysis cache uses). An open issue for the same drift is left alone, not re-filed.
4. **Rank** worst-first (severity, then metric mismatch) and **cap** per run (default 10).
   Overflow is **logged, never silently dropped** — a first run with a backlog files the
   10 worst and reports the rest; steady-state daily runs file only the day's new drifts.
5. **File** one issue per surviving drift: title `[visual-drift] <caseId>: <summary>`,
   body with severity / metrics / likely-causes / suggested-fixes / owner + side-by-side
   and metrics links, labels `visual-drift`, `auto-triage`, `severity:<level>`. A hidden
   `<!-- patrol:caseId=…;comparison=…;hash=… -->` marker carries the dedup fingerprint.

## Safety (why merging this is harmless)

Dry-run is the **default**. A run files real issues only when **LIVE**, which requires an
explicit opt-in:

- manual dispatch with `dry_run=false`, **or**
- repo variable `VISUAL_PATROL_LIVE == "true"` for the scheduled run.

So merging the workflow does **not** start creating issues — enabling is a deliberate,
separate switch. Per-run cap + skip logging prevent flooding even when live.

## Verification (adversarial triage)

The nightly AI analysis can over-report, so a filed issue is not yet trusted. Right after
filing, the `verify` job re-judges each open, not-yet-`verified` drift issue with an
**independent 3× MiniMax-M3 adversarial panel**: *is this a real cross-platform problem
worth fixing, or a false positive / capture artifact / fixture-data / intentional
difference?* Consensus decides:

- majority (≥ 2 of 3) say **real** → label **`verified`** — it enters the fix queue.
- otherwise → the issue is **closed** as an auto-triaged false positive (with a comment;
  reopen if wrong).

`scripts/patrol/verify_issues.mjs` (dry-run default). Shared core lives in
`scripts/patrol/lib.mjs` so future lenses (i18n, code-smell) reuse the same
dedupe/label/file machinery.

## Boundaries

- **Style/token drifts** (color/font/spacing): a follow-up (phase 2) attaches an M3 draft
  patch + 3× adversarial panel verdict to the issue, so triage starts with a proposed fix.
- **Semantic / layout / geometry drifts**: evidence-only. The patrol never proposes a
  structural change — a human owns that. (Same hard boundary as the visual-fix bot.)
- The patrol only reports; it never edits app code or opens PRs.

## Try it (dry run, creates nothing)

```bash
node scripts/visual_patrol.mjs --analysis gh-pages --comparison react__android --threshold medium --cap 10
```

## Roadmap

- Phase 1 (this change): detect → dedupe → file issues (dry-run-safe).
- Phase 2: attach M3 draft patch + panel verdict to style/token issues.
- Later: auto-close issues whose drift no longer reproduces; multi-comparison sweep in one run.

## token-audit lens

`scripts/patrol/token_audit.mjs` scans commonMain for semantic/brand colors weakened with `.copy(alpha = …)` (the mobile#996 brown-destructive class) and files per-file `code-smell` cluster issues through the shared lib (cap 5/run in the nightly job). Deliberate react `bg-*/N` tint mirrors are false positives — comment them or promote to a named token, then close. M3 auto-triage for this lens is a follow-up; issue bodies carry manual triage guidance.

## renderer-coverage lens

`scripts/patrol/renderer_coverage.mjs` statically diffs the markdown library type universe (org.jetbrains:markdown 0.7.5 — refresh the embedded list on upgrade) against the types the fork actually dispatches, minus a justified allowlist. Unhandled types are silent-drop candidates (the task #493 EMAIL_AUTOLINK class). Dynamic sibling: `MarkdownContentIntegrityTest` in commonTest asserts content survives rendering for known shapes.

## font-audit lens

`scripts/patrol/font_audit.mjs` flags typography-contract suspects: naked `fontWeight = FontWeight.X` with no family dispatch on the line (Kuikly encodes weight in the family NAME — naked weight renders the original family, see SlockDesignTokens.kt), and raw `FontFamily(...)` constructed outside the tokens file. The markdown pipeline auto-repairs via `withResolvedSlockFontFamily`; plain `Text()` does not. Cap 5/run nightly.
