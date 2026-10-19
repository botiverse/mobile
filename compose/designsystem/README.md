# Raft design system (Compose Multiplatform)

`:designsystem` is the Raft design system (raft-ui 0.8.0 docs and components), written on official
JetBrains Compose Multiplatform (1.8.2, Kotlin 2.1.21). It is the base for screens that
move off Kuikly (see the CMP migration assessment, §3.2 `:ui-cmp`).

- Depends only on `compose.runtime`, `compose.foundation`, `compose.ui` and
  `compose.components.resources`. No Kuikly, no Material 3, no `:shared`.
- Targets: `androidTarget`, `iosX64` / `iosArm64` / `iosSimulatorArm64`, and
  `jvm("desktop")`. The desktop target exists only to render the screenshot tests
  offscreen; nothing ships on it.
- Package: `build.raft.designsystem` (`.tokens`, `.theme`, `.text`, `.components`).

Sources of truth:

- Colour tokens: generated from the one vendored raft-ui source pinned in
  `tools/design-tokens/manifest.json` (currently 0.7.1), the same source as the Kuikly
  tokens in compose/shared. Bumping raft-ui is a source + manifest change that
  regenerates both outputs together; 0.7.1 and 0.8.0 ship byte-identical CSS.
- Component specs and state rules: the raft-design docs site
  (https://raft-design.botiverse.build, raft-ui 0.8.0; `design/` in the raft-design repo),
  cross-checked with the component source
  (`git -C <raft-design> show v0.8.0:packages/ui/src/components/...`). States follow the
  States page: navigation selection is a yellow sticker with an ink edge, content
  selection `info-soft`, value selection ink plus a check mark, focus the `focus-ring`
  (none of the components in this PR is selectable or focusable yet).

## Tokens

| Kind | Where | How |
| --- | --- | --- |
| Colours | `tokens/RaftUiColorTokens.g.kt` | Generated. `RaftUiPalette` (mode-invariant) and `RaftUiThemeColors.RaftLight` / `.RaftDark` |
| Radii, strokes, spacing, sizes, elevation | `tokens/RaftDimensions.kt` | Hand-written, ported from compose/shared `SlockDesignTokens.kt` |
| Type scale | `theme/RaftTypography.kt` | raft-ui `--text-*` plus the mobile roles from `SlockType` |

The colours come from the same generator and vendored CSS as the Kuikly tokens in
compose/shared, so both stay in lockstep:

```sh
python3 tools/design-tokens/gen_tokens.py            # regenerate both Kotlin files
python3 tools/design-tokens/gen_tokens.py --check    # CI: sources, both files current, Chrome oracle
python3 tools/design-tokens/gen_tokens.py --self-test
```

See `tools/design-tokens/README.md` for bumping raft-ui.

## Theme

```kotlin
// App root: follows RaftThemeState.App (Light by default; the app pins light until
// every screen supports dark).
RaftTheme {
    RaftText("Loading messages", style = RaftTheme.typography.body)
}

// Settings > Appearance
RaftThemeState.App.apply(RaftAppearance.Dark)   // true when the resolved theme changed

// Fixtures and previews pin one appearance.
RaftTheme(appearance = RaftAppearance.Light) { ... }
```

- `RaftThemeState` is an observable Compose state holder modelled on `SlockThemeState`
  (`Light` / `Dark` / `System`, default `Light`). As there, `apply()` and
  `onSystemDarkChanged()` return whether the resolved theme changed, not whether the
  stored choice did. Unlike Kuikly, hosts don't report the OS setting: `RaftTheme` reads
  `isSystemInDarkTheme()` and records it on the state.
- `RaftTheme { }` provides `LocalRaftColors`, `LocalRaftTypography`, `LocalRaftShapes`,
  `LocalRaftIsDark` and `LocalRaftContentColor` (CSS `currentColor`, `--foreground` at the
  root). Read them through `RaftTheme.colors` / `.typography` / `.shapes` / `.isDark`.
- Fonts: Space Grotesk 300–700 and Space Mono 400/700 from `composeResources/font`
  (copies of `androidApp/src/main/res/font`, SIL OFL 1.1, see `THIRD_PARTY_NOTICES.md`),
  loaded with `Font(Res.font.…)`, so every platform measures the same TTFs.
  **iOS:** `compose.ios.resources.sync=false` in compose/gradle.properties applies to this
  module too, so nothing copies `composeResources` (the fonts) into an iOS app bundle yet.
  No iOS host links this module today; wiring the iOS shell to it must also set up the
  resource copy.
- `RaftText` is built on `BasicText`, not Material `Text`, so no Material
  `LocalTextStyle` / `LocalContentColor` can shift colour or line height. Every style
  centres the line box like CSS half-leading (`LineHeightStyle.Alignment.Center`, no trim).

## Screenshot tests

Each component has one golden per appearance holding its full state matrix, rendered
offscreen with skiko (`ImageComposeScene`, no window or GPU) at density 2 and sized to
the content. Goldens: `compose/designsystem/screenshots/<component>_<light|dark>.png`.

```sh
cd compose
./gradlew --no-daemon :designsystem:desktopTest                                # verify
./gradlew --no-daemon :designsystem:desktopTest -Praft.screenshot.record=true  # record
```

- Verify fails when more than 0.1% of the pixels differ by more than 8/255 in any
  channel, or the size changed. The actual image and a diff (mismatches in red) go to
  `compose/designsystem/build/reports/screenshots/`.
- Record after an intended visual change, then review the PNG diff in the PR.
- Goldens are recorded on Linux (the CI runner). Skia rasterises text slightly
  differently on macOS, so a macOS run may need a Linux re-record rather than a looser
  tolerance.
- The same task runs the unit tests (`src/commonTest`).

Other checks:

```sh
./gradlew --no-daemon :designsystem:compileDebugKotlinAndroid :designsystem:compileCommonMainKotlinMetadata
./gradlew --no-daemon :designsystem:compileKotlinIosSimulatorArm64   # macOS only
```

## Components

| Component | raft-ui component | Status | Notes |
| --- | --- | --- | --- |
| `RaftSeparator` | Separator | Done | Horizontal, vertical, with a word |
| `RaftBadge` | Badge | Done (display) | Solid / Soft / Outline x 8 variants, disabled look, icon slot. Not yet clickable (no hover/press overlay) |
| `RaftSpinner` | Spinner | Done | Pixel spinner, xs–lg, inverse, `stillStep` for stills and reduced motion |
| `RaftText` | — | Done | Text primitive |
| Everything else | | Not started | Later PRs |

## raft-ui 0.7.1 vs 0.8.0

Checked with `git diff v0.7.1 v0.8.0` in raft-design:

- No change to `foundation.css`, `tokens.json`, `styles.css`, `fonts.css`, or to the
  Badge, Separator and Spinner sources. The generated colours and the three components
  are the same in both versions.
- Docs only: Typography adds "Why message text is 16px" (see Body size below); Alignment
  adds "A control and its label" (centre a checkbox, radio or switch on the first line of
  its label; relevant to later PRs); Spacing gives the hover toolbar 28px buttons; the
  rest renames "workspace" to "server" in copy.

## Differences from raft-ui and its docs

- **Body size.** Mobile content text is 14/20 (`SlockTextSize.Content`, raft `text-callout`,
  shipped in 1.14.1 per artin 2026-10-18), so `RaftTypography.body` is 14/20; raft-ui
  `text-body` (16/24) is `bodyLarge`. The 0.8.0 Typography doc argues for 16px message
  text and calls 14/20 the Small setting; this is a product decision for the mobile DRI.
- **Disabled solid badge keeps its die-cut edge in dark.** badge.tsx (0.7.1 and 0.8.0) removes only
  the inset ink edge when disabled and keeps `ring-sticker-edge`; the Dark mode doc says
  a disabled button is no longer a sticker. The module follows the component source.
- **No hover.** Touch has no hover; pressed overlays arrive with the clickable components.
