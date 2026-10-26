---
name: raft-designsystem-ui
description: Use when adding or changing a Raft Mobile page, panel, dialog or UI control in Compose Multiplatform — picking design-system components, adding a missing component/variant to compose/designsystem, keeping colours dark-mode safe, and recording screenshot goldens. Not for pure copy changes (see raft-i18n-strings).
---

# Raft design-system UI

The source of truth is raft-ui (raft-design docs + web). Mobile mirrors it in
`compose/designsystem` (package `build.raft.designsystem`). Page code composes
those components; it does not restyle primitives.

Detail: `compose/designsystem/README.md` (tokens, theme, component table,
documented differences from raft-ui) and AGENTS.md "Shared UI Component
Discipline".

## 1. Find the web reference first

1. Locate the web component/page in an external `botiverse/slock` checkout
   (`$SLOCK_SOURCE_REPO/packages/web/src/...`); record its commit SHA.
2. Check the raft-ui spec (https://raft-design.botiverse.build) for sizes,
   radii, strokes, states and type role.
3. Check `compose/designsystem/README.md` → "Components" for an existing
   `Raft*` component (`RaftButton`, `RaftIconButton`, `RaftBackButton`,
   `RaftTextField`, `RaftField`, `RaftSelectTrigger`, `RaftDropdownMenu`,
   `RaftTabs`, `RaftListRow`, `RaftCard`, `RaftBadge`, `RaftDialog`,
   `RaftBanner`, `RaftTooltip`, `RaftSkeleton`, `RaftSwitch`, ...).

## 2. Build the page from components

- Product code lives in `compose/shared/src/commonMain/kotlin/build/raft/app/`
  (never in `compose/androidApp/src/main/java/build/raft/app/`).
- Wrap content in the app theme; read colours/type/shapes through
  `RaftTheme.colors`, `RaftTheme.typography`, `RaftTheme.shapes`
  (or app `RaftColor` tokens). Text uses `RaftText` + a `RaftTypography` role
  (`body`, `callout`, `footnote`, `caption`, `headline`, ...); change weight
  with `.copy(fontWeight = ...)`.
- Metrics (control heights, 44dp icon-button target, radii, strokes) come from
  `tokens/RaftDimensions.kt`, not page literals. raft-ui controls cast no
  shadow (stickers keep the hard offset).
- Back/close controls: `RaftBackButton`.
- Do NOT add new usages of `RaftBrutal*` (`RaftBrutalActionButton`,
  `RaftBrutalIconButton`, `RaftBrutalTextField`, `RaftBrutalSearchField`, ...)
  or helpers under `compose/shared/src/commonMain/kotlin/build/raft/app/components/`.
  When you touch UI that uses them, replace them with the design-system
  component.
- New page class? Annotate it with `@Page("name")` and regenerate the
  registry: `python3 scripts/cmp_generate_page_registry.py` (CI runs `--check`).
- User-visible strings go through `Res.string` (see `raft-i18n-strings`).

## 3. Missing component or variant

1. Add it to `compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/components/`
   following the raft-ui recipe (sizes, states, type). Do not fix the visual
   primitive inside one page.
2. Add a screenshot test in `compose/designsystem/src/desktopTest/...`
   covering the full state matrix in light AND dark.
3. Record goldens (Linux; macOS text rasterises differently):
   ```bash
   cd compose
   ./gradlew --no-daemon :designsystem:desktopTest -Praft.screenshot.record=true
   ./gradlew --no-daemon :designsystem:desktopTest   # verify
   ```
   Goldens: `compose/designsystem/screenshots/<component>_<light|dark>.png`.
   Review the PNG diff in the PR. Do not loosen the tolerance.
4. Add a row to the README "Components" table; list any deliberate deviation
   under "Differences from raft-ui and its docs".

## 4. Dark mode (every screen supports it)

- No `Color(0x...)`, `Color.White`, `Color.Black` or
  `RaftUiThemeColors.RaftLight` in `compose/shared/src/commonMain/kotlin/build/raft/app/`.
  `RaftAppColorLiteralGuardTest` (androidUnitTest) fails on them.
- A literal that must not follow the theme (photo viewer, scrim, sticker art)
  carries `// raft-color: <why>` on the same line.
- Ink on bright sticker fills uses `StickerInk` / `StickerPaper`, not
  foreground.
- Colour tokens are generated; never edit `RaftUiColorTokens.g.kt` by hand:
  `python3 tools/design-tokens/gen_tokens.py` (`--check` in CI).

## 5. Validate

- Prefer hosted CI (push + `Test` workflow; ordinary PRs skip the iOS
  compile); see `raft-ci-validation`. The designsystem golden check runs in
  `:designsystem:desktopTest`.
- Cheap local checks: `git diff --check`,
  `python3 scripts/cmp_generate_page_registry.py --check`.
- For web parity of the finished page, run a targeted visual round
  (`raft-visual-parity`).
