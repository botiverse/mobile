# Markdown & inline-code rendering (Android/Kuikly)

> **Obsolete (2026-10-20):** describes the Kuikly RichText render path (`KRRichTextBuilder`, vendored Kuikly sources), which no longer exists on `main`. Kept for history only.

Reference for how markdown — and specifically the inline-code "chip" (`` `like this` ``) —
is rendered on Android/Kuikly, why fixes here have been fiddly, and how to change
the geometry safely. Written after task #54 (inline-code trailing margin).

## 1. Rendering pipeline (who does what)

```
markdown source
  └─ kuikly-compose-markdown  (third_party/kuikly-compose-markdown)
        parses markdown → an AnnotatedString with SpanStyles / custom spans.
        Inline code becomes text tagged with the inline-code span props.
  └─ Kuikly RichText (core render)  (third_party/kuikly-ui/core-render-android)
        KRRichTextBuilder    builds the Android Spanned:
                             - splits each inline-code run into *atoms* at
                               whitespace/separators so it can line-break,
                             - wraps each atom in KRSlockInlineCodeAtomicTextSpan
                               (a ReplacementSpan that reserves per-atom advance),
                             - marks the whole run with KRSlockInlineCodeSpan.
        KRRichTextViewDrawer draws the yellow fill + black border ("chrome")
                             for each KRSlockInlineCodeSpan, per line segment.
```

Two mental anchors:

- **The text/measurement layer** (`KRSlockInlineCodeAtomicTextSpan.getSize`) decides
  how much horizontal *advance* a chip occupies (glyphs + padding + margins).
- **The chrome layer** (`KRRichTextViewDrawer.drawSlockInlineCodeChrome`) decides where
  the fill/border rectangle is drawn. These two MUST agree, or the border and the
  reserved space drift apart (this is the source of most of the churn on this feature).

## 2. The build/verification pitfall (read this before iterating)

`core-render-android` is consumed by the app as a **fixed-version Maven artifact**:

- `compose/buildSrc/.../KotlinBuildVar.kt` hardcodes `KUIKLY_VERSION = "2.21.0"`, so
  `getKuiklyVersion()` is always `2.21.0-2.1.21`.
- `scripts/build_kuikly_fork.sh` republishes the fork to mavenLocal under that **same
  version** every time.

Gradle treats a resolved *release* version as immutable and caches it. So **editing
render source, rebuilding the fork under the same version, and reinstalling can ship a
STALE AAR** — the change silently doesn't reach the app. Meanwhile
`kuikly-compose-markdown` is added as a **source dir** (`kotlin.srcDir(...)` in
`compose/shared/build.gradle.kts`) and recompiles every build.

Consequence: a change in the *markdown* layer takes effect immediately, but a change in
the *render* layer may not — which produces "I made it smaller but it got bigger"
confusion. **Any time you touch `core-render-android`, bust the cache**:

```
# after build_kuikly_fork.sh --android-only
./gradlew --refresh-dependencies ... :androidApp:installDebug
# and if still stale, clear the cached artifact:
#   rm -rf ~/.gradle/caches/modules-2/files-2.1/com.tencent.kuikly-open
```

Confirm the APK actually contains the new render before trusting a screenshot. (A future
improvement: derive the Kuikly version from the fork commit so republishes are
distinct, removing this trap entirely.)

## 3. Inline-code chip geometry (the model)

All ratios are of `textSize`. Each side of a chip has two nested gaps:

```
        │ EDGE_MARGIN │ EDGE_PADDING │  glyphs  │ EDGE_PADDING │ EDGE_MARGIN │
neighbour┘             └── border ───┘          └─── border ───┘             └neighbour
        (outside gap)  (inner room)              (inner room)  (outside gap)
```

- `EDGE_PADDING = 4/15` — glyph → border. The visible inner room of the chip.
- `EDGE_MARGIN  = 2/15` — border → neighbouring content. The outside gap.
- Per-side reserved advance = `EDGE_PADDING + EDGE_MARGIN = 6/15` (this is `edgePadding()`
  / `startPadding` / the symmetric part of `endPadding`).

The atom reserves `edgePadding` (`6/15`) of advance on each padded side (`startPadding`
on the first atom, `endPadding` on the last). `segmentLeft`/`segmentRight` from
`getPrimaryHorizontal` are therefore the atom-box edges — `6/15` outside the glyphs. The
drawer then pulls the border **in** by `horizontalMargin` on each side so it lands exactly
`EDGE_PADDING` outside the glyphs — the two sides must MIRROR each other:

- leading:  `left  = segmentLeft  + horizontalMargin`  → `glyphLeft  - 6/15 + 2/15 = glyphLeft  - 4/15`.
- trailing: `right = segmentRight - horizontalMargin`  → `glyphRight + 6/15 - 2/15 = glyphRight + 4/15`.

Both give `EDGE_PADDING` (`4/15`) of inner room and leave `EDGE_MARGIN` (`2/15`) as outside
gap. Continuation segments of a wrapped chip keep extending to the padding edge
(`segmentLeft - horizontalPadding` / `segmentRight + horizontalPadding`); only the first
and last segments carry the margin.

### Trailing external margin (following body text)

Text that follows a chip (e.g. a CJK `的`) should get a bit more breathing room than the
`2/15` edge gap. This lives in the **markdown layer**, not the chrome: the existing plain
space after the code span is tagged `slockInlineCodeTrailingMargin`, passed through, and
rendered by `KRSlockInlineCodeTrailingMarginSpan` — an invisible `ReplacementSpan`
(`draw` is empty) whose `getSize` is `SLOCK_INLINE_CODE_TRAILING_MARGIN_RATIO × textSize`
(`1/15` — narrowed from an earlier `2/15` by the "narrow/soften inline code trailing
margin" fixes; source of truth is the constant in `KRRichTextBuilder.kt`). No NBSP, no
visible-text change. So the right-hand outside gap is `2/15` (edge) `+ 1/15` (this span)
`= 3/15`. Tune the following gap with this one ratio.

### What went wrong before (the actual bug fixed in `6fa8a02`)

The trailing border was drawn unconditionally at `segmentRight + horizontalPadding` —
pushing the border **out** — while the leading border pulls **in** with
`segmentLeft + horizontalMargin`. Because the final atom already reserves `edgePadding`
(`6/15`) after the glyphs, pushing out another `horizontalPadding` put the border at
`glyphRight + 10/15` — a large empty pocket inside the chip (reported as "trailing padding
too big, margin too small"). The fix is a one-line mirror: for the final segment draw
`segmentRight - horizontalMargin`. Lesson: the trailing edge must MIRROR the leading edge;
reason about the border position **relative to the glyph edge**, and check both sides
match. (An even earlier detour tried to add the outside margin as extra `endPadding`
advance in the render layer and revert it in the drawer — two coupled sources fighting;
keeping the outside margin in the markdown layer and the border symmetric is cleaner.)

## 4. Principles for future markdown chrome fixes

1. **Layout, not content.** Never inject NBSP / real characters to create spacing. Use
   layout advance (span `getSize`) and let the drawer exclude it from the chrome.
2. **Change the right layer.** Spacing around a chip = render layer
   (`core-render-android`). Markdown structure/content = `kuikly-compose-markdown`.
3. **Mirror the two edges.** The leading and trailing borders must be symmetric: if the
   leading side pulls in with `segmentLeft + horizontalMargin`, the trailing side pulls in
   with `segmentRight - horizontalMargin`. Always express the border position relative to
   the glyph edge and verify both sides match.
4. **Bust the AAR cache and verify the APK** before trusting any screenshot (§2).
5. Prefer symmetric geometry as the default; deviate only with an explicit, named knob.

## 5. Key symbols

| Symbol | File | Meaning |
| --- | --- | --- |
| `KRSlockInlineCodeAtomicTextSpan` | KRRichTextBuilder.kt | ReplacementSpan reserving per-atom advance; draws the code glyphs |
| `startPadding` / `endPadding` | KRRichTextBuilder.kt | leading / trailing reserved advance |
| `SLOCK_INLINE_CODE_EDGE_PADDING_RATIO` (4/15) | both | glyph → border |
| `SLOCK_INLINE_CODE_EDGE_MARGIN_RATIO` (2/15) | both | border → neighbour |
| `KRSlockInlineCodeTrailingMarginSpan` / `SLOCK_INLINE_CODE_TRAILING_MARGIN_RATIO` (2/15) | KRRichTextBuilder.kt | invisible span on the trailing space = extra outside gap before following text |
| `drawSlockInlineCodeChrome` | KRRichTextViewDrawer.kt | draws fill + border per line segment (final segment mirrors leading: `segmentRight - horizontalMargin`) |
