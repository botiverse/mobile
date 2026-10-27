# Design tokens from the Web sources

The mobile colour tokens are generated from the same CSS the Web client ships, so a
Web palette change becomes a reviewable diff here instead of silent drift (task #165).

| File | What it is |
| --- | --- |
| `manifest.json` | Pinned sources: raft-ui (`styles.css`, `foundation.css`, `themes.css`) and Tailwind versions that `slock/packages/web` locks, the Web `index.css` at a slock commit, and the sha256 of each vendored copy |
| `source/` | Vendored copies of those files (raft-ui and Tailwind are MIT) |
| `gen_tokens.py` | Resolves the CSS cascade per theme and writes the same tokens twice: `compose/shared/src/commonMain/kotlin/build/raft/app/designtokens/RaftUiColorTokens.g.kt` (Kuikly Compose `Color`, `internal`) and `compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/tokens/RaftUiColorTokens.g.kt` (official Compose Multiplatform `androidx.compose.ui.graphics.Color`, public). The targets are listed in `TARGETS` |
| `chrome-oracle.json` | The same tokens as painted by Chromium; `--check` requires every generated colour to match within 1/255 per channel |
| `capture_chrome_oracle.mjs` | Regenerates the oracle (local, needs Playwright) |

Generated Kotlin:

- `RaftUiPalette` — theme-invariant colours: raw ramps (`brutalYellow400`), brand colours from Web `index.css` (`brutalYellow` = `#FFD440`), state solids (`warning`) and the Tailwind palette (`gray400`).
- `RaftUiThemeColors` — colours that change with the mode or the theme (`foreground`, `line`, `layerCanvas`, `brand`...), including tokens derived from themed ones through `var()` or `color-mix()`. `sealed class Light` / `Dark` carry the default (Raft) theme's values; `data object RaftLight` / `RaftDark` are exactly those, and every other theme (`BoringLight`, `BotsDark`...) extends the same mode and overrides only the tokens whose colour differs. `RaftUiThemeColors.of(theme, dark)` picks one.
- `RaftThemeId` — the theme ids (`wire` as on the API and in Web `data-theme`) and `isPro`.

## Themes

raft-ui `themes.css` (0.25+) re-skins the frame per server with `data-theme="<id>"` next to the mode class: `boring` (Pro), `bots`, `critters`, `space`, `desk`, `food`, `quest`, `weather`, `plants`, `spooky`; no attribute is `raft`, the foundation itself. The generator resolves the whole cascade once per (theme, mode), so a token that reads an overridden one follows it (Boring's `--brand-400` grays `--primary-400`, `--color-brand` and `--brand-hover`). It reads only `.light[data-theme="x"], :root:not(.light):not(.dark)[data-theme="x"]` and `.dark[data-theme="x"]`; the `@media (prefers-color-scheme: dark)` copy (a root with no mode class) and the `:is(:root, .light, .dark)[data-theme="x"]` decor images are skipped. A theme id in `themes.css` that is not in `THEME_IDS` fails the run.

The theme list and the Pro flag are not in the raft-ui package (its `themes.json` is not shipped): `THEME_IDS` / `PRO_THEME_IDS` in `gen_tokens.py` follow slock `packages/shared/src/raftThemes.ts` (`RAFT_THEME_IDS`, `PRO_RAFT_THEME_IDS`). Check them on a bump.

Any token a theme overrides becomes theme-dependent and leaves `RaftUiPalette`: read it from the current theme colours (`RaftTheme.colors`, `RaftAppThemeState.colors`). Raft yellow that must not follow the theme has its own unthemed tokens (`selected`, `you`).

Only colours are emitted. Names in Tailwind's non-colour namespaces (`--spacing-*`, `--radius-*`, `--text-*`, `--font-*`, `--shadow-*`... see `NON_COLOUR_NAMESPACES`) are skipped without being resolved, and any other value that is not a colour (a length, a shadow) is dropped. A colour that cannot be resolved (undefined `var()`, unsupported syntax) still fails the run.

Names drop the leading `--` and `color-` and are camel-cased (`--foreground-muted` → `foregroundMuted`).

## Commands

```sh
python3 tools/design-tokens/gen_tokens.py              # regenerate both Kotlin files
python3 tools/design-tokens/gen_tokens.py --check      # CI: sources match manifest, both Kotlin files current, oracle agrees
python3 tools/design-tokens/gen_tokens.py --self-test  # CI: colour maths, cascade, fail-closed parsing
```

## Bumping to a newer Web

1. Read the versions in `slock/packages/web/package.json` and `pnpm-lock.yaml` (`@botiverse/raft-ui`, `tailwindcss`) at the target slock commit.
2. Replace the files under `source/` (`npm pack @botiverse/raft-ui@<v>` (the package was `raft-ui` up to 0.6), `npm pack tailwindcss@<v>`, `git show <commit>:packages/web/src/index.css`), rename the version directories, and update `manifest.json` (paths, origins, `slockCommit`, sha256).
3. `python3 tools/design-tokens/gen_tokens.py`, then regenerate the oracle (it paints every token in every theme and mode) (see the header of `capture_chrome_oracle.mjs`), then `--check`. Capture with the same Chromium as the current oracle (its `chromium` field; pass it via `CHROMIUM_PATH`), so the oracle diff shows only token changes.
4. Review the Kotlin diff: every changed colour is a visible change on mobile.

## How the cascade is resolved

Matches the Web build: every `@theme` block (Tailwind, then raft-ui `styles.css`, then Web `index.css`) lands in Tailwind's `theme` layer and the last declaration wins; raft-ui `foundation.css`, raft-ui `themes.css` and Web `:root.dark` are unlayered and beat any `@theme` value of the same name. Inside them the more specific selector wins (`:where(:root)` < `.light` / `.dark` < `.light[data-theme]` / `.dark[data-theme]` = Web `:root.dark`, which comes later in the Web's imports and so wins the tie). A custom property registered with `@property` and declared nowhere takes its `initial-value` (raft-ui 0.18 registers `--icon-inset: 0px`). Colours use CSS Color 4 maths and are clipped per channel, as Chrome paints them. The oracle checks all of this against Chromium itself.

## Aligned with Web

All mobile colour constants that have a Web token now read from the generated file. The earlier
drift (brutal text/border pure black instead of `#141110`; elegant cool zinc neutrals instead of
raft-ui's warm hue-106 neutrals) was aligned in the follow-up to task #165 at artin's request.
Remaining `Color(0x…)` literals in `RaftColor` (`AppBgLightBlue`, `EmptyStateGray`) have no Web
token.
