# Visual Patrol — daily drift inspection

> **Report only since 2026-10-15.** The repository is public, so the workflow no longer files
> GitHub issues (artin; Sentinel, task #172). Every lens runs in its default dry-run mode and the
> findings land in the job summary and the `visual-patrol-report` artifact; the 3x M3 verify stage
> was removed with the issues it triaged. The scripts still support `--live` for a private fork.
> The sections below describe the original issue-filing design.

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
3. **Dedupe** against already-open patrol issues by stable `caseId + comparison` identity.
   The analysis `imageHash` remains evidence metadata, not identity: a nightly pixel/hash
   change must not create a second open issue for the same case and comparison. Other patrol
   lenses likewise use stable `lens + key` identity (normally the source path), while their
   fingerprint records the current finding set.
4. **Rank** worst-first (severity, then metric mismatch) and **cap** per run (default 10).
   Overflow is **logged, never silently dropped** — a first run with a backlog files the
   10 worst and reports the rest; steady-state daily runs file only the day's new drifts.
5. **File** one issue per surviving drift: title `[visual-drift] <caseId>: <summary>`,
   body with severity / metrics / likely-causes / suggested-fixes / owner + side-by-side
   and metrics links, labels `visual-drift`, `auto-triage`, `severity:<level>`. A hidden
   `<!-- patrol:caseId=…;comparison=…;hash=… -->` marker carries stable identity plus the
   current evidence fingerprint.

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
**independent 3× MiniMax-M3.1-Flash-Preview adversarial panel**: *is this a real cross-platform problem
worth fixing, or a false positive / capture artifact / fixture-data / intentional
difference?* Consensus decides:

- majority (≥ 2 of 3) say **real** → label **`verified`** — it enters the fix queue.
- otherwise → the issue is **closed** as an auto-triaged false positive (with a comment;
  reopen if wrong).

`scripts/patrol/verify_issues.mjs` (dry-run default). Shared core lives in
`scripts/patrol/lib.mjs` so future lenses (i18n, code-smell) reuse the same
dedupe/label/file machinery.

## Verified-backlog fix queue

`.github/workflows/visual-fix-queue.yml` is an independent two-hourly consumer. It
does not depend on patrol filing a new issue that day: it selects the oldest
1–3 issues that are still open, carry both `visual-drift` and `verified`, have
no assignee, and are not already processing/refuted/agent-ready.

For each claimed issue, `scripts/patrol/fix_queue.mjs` runs a bounded pipeline:

1. requires the case in both the canonical manifest and exact published
   comparison analysis, plus non-empty pinned React/RUI and Android/KMP source
   authority, before spending any M3 call;
2. derives React truth from the pinned Slock staging checkout plus the pinned
   `botiverse/rui` checkout, enumerating theme/state variants;
3. asks M3 for a minimal, source-authoritative Android/KMP patch without
   applying it;
4. validates every proposed patch path and unified-diff header against the
   located Android product-source set, excluding tests, baselines, and shared
   registries;
5. gives the patch result (including a fail-closed no-patch result) to three
   independent M3 reviewers, so every admitted case has a full 3× panel record;
6. records `REFUTED` with the precise flaw, or labels `UNREFUTED` as
   `fix-queue:agent-ready` with the exact agent handoff: targeted Visual Testing,
   `visual_verify.py`, three-column evidence, then a draft PR.

Safety boundaries:

- scheduled mutation is disabled until `VISUAL_FIX_QUEUE_LIVE=true`; manual
  dispatch is dry-run by default, and pull requests always run a real-backlog
  dry-run regardless of the live variable;
- one global workflow concurrency group plus `fix-queue:processing` prevents
  duplicate consumption; terminal result labels prevent later-run reprocessing;
- per-run case cap is clamped to 1–3 (up to 36 cases/day at the two-hour cadence);
  source prompt input is bounded and the
  token cap defaults to 1T (repo variable `VISUAL_FIX_QUEUE_TOKEN_CAP`) while
  conservatively reserving 75k per selected issue before any M3 call; the large
  budget never raises the 1–3 case cap or weakens any mutation/review boundary;
- all three fresh panel replies must be valid strict JSON before a terminal
  verdict; each model stage gets one bounded strict-JSON retry, and exhausted
  retries remain `REVIEW` with a bounded redacted audit record;
- full mobile/Slock/RUI/model/workflow/script provenance plus the gh-pages
  analysis commit/path/hash is uploaded and summarized on the issue;
- the job never applies product source, changes a baseline, opens or merges a
  PR. An implementation agent owns the targeted CI/evidence/draft-PR step, and
  product merge still requires the normal independent review and owner approval.

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
- Phase 2: implemented by the independent verified-backlog fix queue above.
- Later: auto-close issues whose drift no longer reproduces; multi-comparison sweep in one run.

## token-audit lens

`scripts/patrol/token_audit.mjs` scans commonMain for semantic/brand colors weakened with `.copy(alpha = …)` (the mobile#996 brown-destructive class) and files per-file `code-smell` cluster issues through the shared lib (cap 5/run in the nightly job). Deliberate react `bg-*/N` tint mirrors are false positives — comment them or promote to a named token, then close. M3 auto-triage for this lens is a follow-up; issue bodies carry manual triage guidance.

## renderer-coverage lens

`scripts/patrol/renderer_coverage.mjs` statically diffs the markdown library type universe (org.jetbrains:markdown 0.7.5 — refresh the embedded list on upgrade) against the types the fork actually dispatches, minus a justified allowlist. Unhandled types are silent-drop candidates (the task #493 EMAIL_AUTOLINK class). Dynamic sibling: `MarkdownContentIntegrityTest` in commonTest asserts content survives rendering for known shapes.

## font-audit lens

`scripts/patrol/font_audit.mjs` flags typography-contract suspects: naked `fontWeight = FontWeight.X` with no family dispatch on the line (Kuikly encodes weight in the family NAME — naked weight renders the original family, see SlockDesignTokens.kt), and raw `FontFamily(...)` constructed outside the tokens file. The markdown pipeline auto-repairs via `withResolvedSlockFontFamily`; plain `Text()` does not. Cap 5/run nightly.

## glyph-audit lens

`scripts/patrol/glyph_audit.mjs` flags unicode glyph characters (✓ × arrows …) used inside UI string literals — affordances must be Lucide icons (task #494). Typographic uses (masking dots, the → upgrade hint, fixture bodies, comments) are excluded; new legit patterns extend the exclusion list. Cap 5/run in the nightly job.
