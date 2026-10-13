# Design tokens from the Web sources

The mobile colour tokens are generated from the same CSS the Web client ships, so a
Web palette change becomes a reviewable diff here instead of silent drift (task #165).

| File | What it is |
| --- | --- |
| `manifest.json` | Pinned sources: raft-ui and Tailwind versions that `slock/packages/web` locks, the Web `index.css` at a slock commit, and the sha256 of each vendored copy |
| `source/` | Vendored copies of those files (raft-ui and Tailwind are MIT) |
| `gen_tokens.py` | Resolves the CSS cascade per theme and writes `compose/shared/src/commonMain/kotlin/build/raft/app/designtokens/RaftUiColorTokens.g.kt` |
| `chrome-oracle.json` | The same tokens as painted by Chromium; `--check` requires every generated colour to match within 1/255 per channel |
| `capture_chrome_oracle.mjs` | Regenerates the oracle (local, needs Playwright) |

Generated Kotlin:

- `RaftUiPalette` — theme-invariant colours: raw ramps (`brutalYellow400`), brand colours from Web `index.css` (`brutalYellow` = `#FFD440`), state solids (`warning`) and the Tailwind palette (`gray400`).
- `RaftUiThemeColors.Brutal` / `.ElegantLight` / `.ElegantDark` — colours that change with the Web theme (`foreground`, `line`, `layerCanvas`, `primaryHover`...), including tokens derived from themed ones through `var()` or `color-mix()`.

Names drop the leading `--` and `color-` and are camel-cased (`--foreground-muted` → `foregroundMuted`).

## Commands

```sh
python3 tools/design-tokens/gen_tokens.py              # regenerate the Kotlin file
python3 tools/design-tokens/gen_tokens.py --check      # CI: sources match manifest, Kotlin file current, oracle agrees
python3 tools/design-tokens/gen_tokens.py --self-test  # CI: colour maths, cascade, fail-closed parsing
```

## Bumping to a newer Web

1. Read the versions in `slock/packages/web/package.json` and `pnpm-lock.yaml` (`raft-ui`, `tailwindcss`) at the target slock commit.
2. Replace the files under `source/` (`npm pack raft-ui@<v>`, `npm pack tailwindcss@<v>`, `git show <commit>:packages/web/src/index.css`), rename the version directories, and update `manifest.json` (paths, origins, `slockCommit`, sha256).
3. `python3 tools/design-tokens/gen_tokens.py`, then regenerate the oracle (see the header of `capture_chrome_oracle.mjs`), then `--check`.
4. Review the Kotlin diff: every changed colour is a visible change on mobile.

## How the cascade is resolved

Matches the Web build: every `@theme` block (Tailwind, then raft-ui `styles.css`, then Web `index.css`) lands in Tailwind's `theme` layer and the last declaration wins; raft-ui `foundation.css` and Web `:root.dark` are unlayered and beat any `@theme` value of the same name. Inside them the more specific theme selector wins (default < `[data-theme="elegant"]` < `[data-theme="elegant"].dark`). Colours use CSS Color 4 maths and are clipped per channel, as Chrome paints them. The oracle checks all of this against Chromium itself.

## Known drift (mobile ≠ Web today)

Hand-written mobile values that differ from what Web paints with raft-ui 0.5.52. They are left unchanged in the first PR because changing them is a visible change; each needs a decision.

| Mobile constant | Mobile | Web token (theme) | Web |
| --- | --- | --- | --- |
| `SlockColor.TextBlack` | `#000000` | `--foreground` (brutal; Web `body` colour) | `#141110` |
| `SlockColor.BorderBlack` | `#000000` | `--line` (brutal) | `#141110` |
| `elegantTheme.inkMuted` | `#71717A` | `--foreground-muted` (elegant light) | `#3D3D3A` |
| `elegantTheme.surfaceMuted` | `#F4F4F5` | `--fill-muted` (elegant light) | `#F4F4F3` |
| `elegantTheme.line` | `#CACACE` | `--line` (elegant light) | `#CBCBC6` |
| `elegantTheme.lineSubtle` | `#E4E4E7` | `--line-muted` (elegant light) | `#E5E5E2` |

The elegant rows come from raft-ui moving its elegant neutrals from cool zinc (hue ≈ 286) to warm neutrals (hue 106.42). `BorderBlack` is less clear-cut than `TextBlack`: many Web brutal components draw `border-black` (Tailwind `#000000`) rather than `--line`, so it needs a per-component check.

Matching constants already read from the generated file: brand yellow, the seven brutal accents, `warning`, white, cream, Tailwind `gray-400` / `neutral-500`, brutal black and elegant `foreground-strong`.
