---
name: raft-ui-follow
description: Use when a new raft-ui (botiverse/raft-design) release lands, or the Web client bumps @botiverse/raft-ui, and Raft Mobile's compose/designsystem has to catch up — reading the changelog item by item, bumping the generated colour tokens, matching non-colour values by hand, re-recording goldens, and shipping small PRs with before/after images. Not for building a new page (see raft-designsystem-ui).
---

# Follow a raft-ui release

raft-ui is the source of truth; `compose/designsystem` mirrors it. Mobile follows
the raft-ui version the **Web client actually ships** (`slock/packages/web`), not
npm's latest. The version mobile's colours are generated from is pinned in
`tools/design-tokens/manifest.json`.

Agreed in #wg-mobile (2026-10-23, Lagoon, Codex-Android-DevOPS, sindresorhus).

## 0. Know there is one

- A daily CI job compares `manifest.json` with the raft-ui version
  `slock/packages/web` locks and, on a mismatch, opens one GitHub issue per
  version ("raft-ui <version> can be followed"; an open issue with the same
  title is not reopened). Codex-Android-DevOPS forwards it to #wg-mobile. When
  that issue appears, follow this skill and close the issue with the PRs.
- Also watch #proj-raft-ui release announcements and
  `botiverse/raft-design` `packages/ui/CHANGELOG.md`.

## 1. Read the changelog item by item

For every entry between mobile's version and the target, decide one of:

| Verdict | Meaning |
| --- | --- |
| **change** | mobile must follow; note the component, the exact recipe value and whether goldens change |
| **done** | mobile already has it (link the PR) |
| **n.a.** | web-only: hover / `pointer: fine` behaviour, React render internals, ResizeObserver, DOM structure |

Mobile is touch-only (phones and tablets): a `pointer: coarse` / touch branch
applies, a `pointer: fine` / hover branch does not. A layout that switches on
width switches on the **region's** width in raft-ui ≥ 0.23 (a panel, a card), not
the window's — mirror that with the component's own constraints, not
`LocalWindowInfo`, unless raft-ui reads the window (page shell, dialogs,
sheets, toasts).

Write the table into the PR description (or a report under the repo's `reports/`
practice) so reviewers can check nothing was skipped.

## 2. Take values from the source, never from a screenshot

- Recipes: `git show <tag>:packages/ui/src/components/<x>/<x>.recipe.ts` (and the
  component `.tsx`); `git diff <old-tag> <new-tag> -- packages/ui/src/components/<x>`
  shows exactly what moved.
- Tokens: `packages/ui/src/tokens.json` / `foundation.css` at the tag.
- Component docs: `packages/ui/src/components/<x>/<x>.md` (states, rules).

## 3. Colours: bump the generator

Colours are generated; never edit `RaftUiColorTokens.g.kt` by hand. Follow
`tools/design-tokens/README.md` → "Bumping to a newer Web":

1. Read `@botiverse/raft-ui` / `tailwindcss` versions in `slock/packages/web`
   (`package.json`, `pnpm-lock.yaml`) at the target slock commit.
2. Replace `tools/design-tokens/source/` copies, update `manifest.json`
   (paths, origins, `slockCommit`, sha256).
3. `python3 tools/design-tokens/gen_tokens.py`, regenerate the Chromium oracle
   with the **same** Chromium, then `--check`.
4. Every changed colour in the Kotlin diff is a visible change: say so in the PR.

## 4. Everything else: by hand

The generator skips non-colour tokens (spacing, radius, type, sizes). For each
**change** item:

- Update the design-system token or component (`tokens/RaftDimensions.kt`,
  `components/Raft*.kt`), citing the raft-ui PR in a comment where the value
  isn't obvious.
- **Grep page code for the old literal.** Pages sometimes hard-code a
  design-system value (raft-design #722: the 9dp status light was written as
  `9.dp` in five app files). Move them onto the token in the same PR.
- **Check the combinations**, not just the main value: a menu row's
  height + icon + text inset, a status light's ring on an avatar corner, a
  chip's padding around its mark. Changing one number often leaves its
  neighbours misaligned.

## 5. Deliberate differences

When mobile intentionally stays different (an owner decision, a platform
reason):

- Comment the code with the decision and its source, e.g.
  `// stdrc 2026-10-22: popup rows stay 36dp on touch (raft-design #712 thread)`,
  `// raft-design #722: a light on an avatar follows the avatar, 9dp`.
- List it under "Differences from raft-ui" in `compose/designsystem/README.md`.
- For a visual-testing case, declare it in the case manifest's
  `declaredDivergences` (it still counts as a difference; never loosen a
  tolerance to make a case pass).

Without this, the next person to sync "fixes" it back.

## 6. Goldens and images

- Re-record goldens through CI (no local Gradle on agent machines): cherry-pick
  the TEMP dump commit, run `test.yml`, download `designsystem-screenshot-diffs`
  and `shared-home-screenshots`, drop the TEMP commit, copy the actuals.
- **Compare images in RGB.** `ImageChops.difference(a, b).getbbox()` on RGBA
  images only looks at alpha, so every opaque screenshot reads as unchanged.
  Use `a.convert("RGB")` on both sides, and count pixels over a small threshold
  to separate real changes from anti-aliasing noise; record only the real ones.
- Put before/after images in the PR and in the thread for the person who asked;
  reviewers judge UI from images, not prose.

## 7. Ship in small PRs

- One theme per PR (a token bump, one component, a sizing rule), each with its
  goldens and images; Lagoon reviews design-system PRs.
- Before merging a `compose/designsystem` change, run the full suite (PR CI skips
  the iOS compile and emulator jobs):
  `stamp workflow run test.yml --repo botiverse/mobile --ref <branch> -f scope=full`,
  and `visual-testing.yml` with `include_ios=true` to compare iOS and Android with
  Web (goldens only cover Android rendering).
- User-visible changes need a changeset (`raft-release-changeset`) once
  `change-logs/<version>/` exists; until then write "needs changeset" in the PR.
- When done, bump nothing else silently: the PR description lists every
  changelog item with its verdict.
