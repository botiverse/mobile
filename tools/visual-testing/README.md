# Slock Compose Visual Testing

Cross-platform visual testing CLI for Slock Compose / KMP UI components.

This tool lives in the Slock Compose Android repository so the upstream Slock
web repo can stay clean and track upstream. It owns the portable
visual-testing boundary:

- case manifest loading and validation
- provider output contract
- state variant schema
- raw image diff metrics
- side-by-side compositor
- HTML report generation

The user-facing providers are `react`, `android`, and `harmony`. Providers render real components from the manifest and fixtures, then write raw screenshots and metadata under `visual-testing-results/<provider>/`. If Android or Harmony renders KMP/shared UI, record that as the provider `source`; do not expose `kmp-shared` as a fourth platform.

Default runs compare only canonical real-provider captures. The CLI no longer
generates built-in demo provider images for missing Android/Harmony captures;
non-React providers must be produced by a real capture command and stale
`providerType: "builtin-demo"` artifacts are ignored by diff.

The CLI defaults to:

- tool / artifacts repo: the current Slock Compose Android checkout
- React source repo: `third_party/slock-source` inside the current Slock
  Compose Android checkout

Initialize the pinned React source before running commands that inspect React
components:

```bash
git submodule update --init third_party/slock-source
```

Override the React source repo only for one-off debugging:

```bash
node tools/visual-testing/src/cli.mjs scan --react-repo-dir ~/0Workspace/github.com/botiverse/slock
SLOCK_REACT_REPO_DIR=~/0Workspace/github.com/botiverse/slock node tools/visual-testing/src/cli.mjs capture --providers react
```

If the default `third_party/slock-source` checkout is missing or does not
contain the React visual provider harness, commands fail with a clear error
instead of silently falling back to another local web checkout. Canonical
visual alignment should use the pinned `third_party/slock-source` revision.

## CLI

```bash
node tools/visual-testing/src/cli.mjs scan
node tools/visual-testing/src/cli.mjs validate
node tools/visual-testing/src/cli.mjs token-audit
node tools/visual-testing/src/cli.mjs capture --providers react
node tools/visual-testing/src/cli.mjs capture --providers android --command './scripts/capture-android-visuals.sh'
node tools/visual-testing/src/cli.mjs diff --baseline react --current android
node tools/visual-testing/src/cli.mjs report --baseline react --current android
node tools/visual-testing/src/cli.mjs site --baseline react --current android
node tools/visual-testing/src/cli.mjs publish-gh-pages --baseline react --current android --dry-run
node tools/visual-testing/src/cli.mjs publish-gh-pages --baseline react --current android --repo-dir ~/0Workspace/github.com/bytemain/slock-android
```

The Android repo keeps `scripts/slock-visual-testing.mjs` as a compatibility shim.

## Token Audit

`tokens.palette` is useful only when it is backed by a machine gate. Run:

```bash
node tools/visual-testing/src/cli.mjs token-audit
```

The audit compares React `sharedTokens.json` against KMP `SlockColor` constants by exact hex value and fails when the token source-of-truth drifts. It also writes usage reports to:

```text
artifacts/visual-testing/token-audit.json
artifacts/visual-testing/token-audit.md
```

Usage findings distinguish real-surface hard-coded colors that match known tokens from unknown colors that are outside the shared token set. Existing debt is reported instead of silently folded into the palette screenshot; cleanups should be split by component or surface.

## State Variants

Diff units are designed to become:

```text
caseId + variantId + provider
```

Each case may declare variants:

```json
{
  "id": "thread.message.row",
  "variants": [
    {
      "id": "own-right",
      "name": "Own / Right Aligned",
      "props": { "message": "own", "alignOwnMessagesRight": true }
    }
  ]
}
```

The component owner is responsible for exposing fixture props or test-only adapters that can drive real components into deterministic states. The visual-testing package reads `caseId` and `variantId`; it should not guess component parameters.

## Presentation Boundary

Providers output raw component content only. The package compositor owns review presentation: outer frame, shadow, case title, provider badge, side-by-side layout, and similarity labels. Similarity metrics are computed from raw screenshots, not the presentation frame.

## GitHub Pages

`slock-visual site` copies the latest report, provider screenshots, diff JSON, and side-by-side images into `artifacts/visual-testing-site`. It writes both `latest/` and `runs/<commit>/`, plus `index.json` and `llms.txt` for machine-readable summaries.

`slock-visual publish-gh-pages` publishes that static site into the `visual-testing/` subdirectory of the `gh-pages` branch through a temporary git worktree. Use `--repo-dir` when the Pages repository is different from the visual-testing source repository. Existing runs are preserved and the homepage is rebuilt as a commit-index.

Run `publish-gh-pages --dry-run` before pushing. The command only publishes generated report artifacts; it does not commit the app branch or turn pending/fake provider cases into a baseline.
