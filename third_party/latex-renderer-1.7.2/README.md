# latex-renderer 1.7.2-raft.1 (patched LaTeX renderer for iOS)

See [`third_party/compose-ui-1.12.2/README.md`](../compose-ui-1.12.2/README.md) for what
`third_party/` is and how these patched builds are published. This entry uses the same helpers
(`reversion_artifact.py`, `compare_with_official.py`, `upload_maven_dir.sh`) from that directory.

## This entry

| | |
|---|---|
| Upstream | [huarangmeng/latex](https://github.com/huarangmeng/latex) tag `release-1.7.2` = `7cfc3a7a4a98cf7888feedc3c18441313fbef022` (the source of `io.github.huarangmeng:latex-renderer:1.7.2`) |
| Rebuilt artifacts | `io.github.huarangmeng:latex-renderer-iosarm64` and `:latex-renderer-iossimulatorarm64`, version `1.7.2-raft.1` |
| Not rebuilt | everything else: the root `latex-renderer` module, `latex-parser`, `latex-base` and all other targets stay on the official 1.7.2 |
| Patches | `0001-apple-glyph-width-from-glyph-ids.patch` (the fix, `appleMain` only); `0002-ios-simulator-glyph-regression-tests.patch` (tests only: runs the Skia glyph tests on `iosSimulatorArm64` and adds the regression tests; nothing it touches is published) |
| Workflow | `.github/workflows/latex-renderer-patch.yml` (`workflow_dispatch` only; `publish` input, default `false`) |
| Repository secrets | the Compose UI patch's `COMPOSE_PATCH_MAVEN_URL` / `_USER` / `_TOKEN` (same repository); read only by the publish step |

### Why

iOS SIGABRT while laying out a formula (Hands `4048b989`, `f67238e4`, `09e9da2a` on 1.30.0; earlier
iPad `01d87c01`: `SkTypeface_Mac::onCharsToGlyphs` <- `Font.measureTextWidth`).

`measureGlyphBounds` in `GlyphBoundsProvider.apple.kt` gets the glyph ids with
`font.getStringGlyphs(text)` (code points, correct) and the width with
`font.measureTextWidth(text)`. On Kotlin/Native, Skiko 0.150.2 turns the `String` into a
zero-terminated **UTF-8** byte array (`InteropScope.toInterop(String)` ->
`convertToZeroTerminatedString`), but `Font.cc`'s `_nMeasureTextWidth` calls
`SkFont::measureText(str, len * sizeof(KChar), SkTextEncoding::kUTF16, ...)` with `len` =
`text.length`. Skia therefore reads `2 * length` bytes of a `length + 1` byte UTF-8 buffer as
UTF-16:

- multi-character ASCII (named operators, `\text{…}` runs) reads past the end of the buffer into
  whatever the heap holds there; text of 9+ ASCII characters already leaves the 8-byte-rounded
  allocation;
- when those bytes form an unpaired surrogate (or the UTF-8 of a U+0600–U+07FF character lands a
  `D8`–`DF` byte in a high half), `SkUTF::CountUTF16` returns -1, `SkAutoToGlyphs` turns it into a
  `SIZE_MAX` glyph count, and `SkTypeface_Mac::onCharsToGlyphs` aborts on the impossible
  allocation.

The JVM passes real UTF-16 (`GetStringCritical`), so desktop is not affected; Android measures with
`Paint`. Upstream is unchanged up to 1.8.0 (its #65 only skips unpaired surrogates in the input).
The app's earlier guard (formulas with any surrogate show as source, `RaftMarkdownMath.kt`) does not
cover this: plain ASCII reaches the same abort.

### The fix

`inkWidth = font.getWidths(glyphIds).sum()`: the advance sum of the glyph ids already computed
for the bounds. `SkFont::measureText` without a paint returns the same sum (same canonicalized
strike, no kerning), so the value is what the old call returned whenever it read the text
correctly. Besides that, in 1.7.2 nothing in the renderer reads `GlyphBounds.inkWidth` (layout
uses only `ascentPx`/`descentPx`/`inkHeight`), so formula layout cannot change.

### Checks the workflow runs (iOS simulator)

1. **Reproduction, unpatched source** (only `0002` applied), one Gradle run per case because an
   abort kills the test process: `layoutFingerprint` (must pass; it is the baseline),
   `bmpTextWhoseUtf8ReadsAsUnpairedSurrogateDoesNotAbort` (deterministic trigger),
   `multiCharAsciiDoesNotAbort` (ASCII of length 2–200; whether it aborts depends on the heap),
   `symbolsAndNonBmpDoNotAbort`. Exit codes and abort markers go to the job summary and the
   `latex-renderer-patch-test-reports` artifact.
2. **Patched**: `GlyphBoundsTextEncodingTest` (multi-character ASCII, BMP symbols, `ℝ`/`\mathbb{R}`,
   non-BMP, unpaired surrogates in every KaTeX font; width equals the old call for single ASCII
   characters and is additive) and the upstream `SkiaPlatformRenderingTest` on
   `iosSimulatorArm64`; `GlyphBoundsWidthJvmTest` on the JVM, where the old call is correct, checks
   the advance sum equals `measureTextWidth` for multi-character ASCII, BMP and non-BMP text.
3. **Layout**: the SVG fingerprints (size, length, FNV hash) of 10 formulas at 2 sizes from the
   patched build must equal those of the unpatched build.
4. Build with `-PVERSION=1.7.2` (as upstream's publish workflow), publish the two target
   publications to a local Maven repository (signing uses a throwaway key; signatures are dropped),
   re-version to `1.7.2-raft.1`, then the same metadata/manifest comparison and `klib dump-abi`
   check against the official 1.7.2 klibs as the Compose UI patch.

### Device check before shipping

The simulator proves the abort and the fix; check on a real iPhone/iPad that formulas with named
operators (`\lim`, `\max`, `\operatorname{…}`), `\text{…}` runs and `\mathbb{R}` render as
before.

### When to remove

Remove this when upstream releases a version whose Apple actual no longer calls
`Font.measureTextWidth(String)` (or Skiko fixes `_nMeasureTextWidth` on native) and the app
upgrades to it. Then delete the app's pin (`raft.latexRendererPatch.*`), this directory and the
workflow, and stop publishing `1.7.2-raft.N`. To make a new revision, change the patches and bump
`PATCHED_VERSION` in the workflow.
