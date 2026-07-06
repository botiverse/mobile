# Markdown & inline-code rendering (Android/Kuikly)

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

The drawer keeps the border exactly `EDGE_PADDING` outside the glyphs on both sides:

- leading:  `left  = segmentLeft  + horizontalMargin`  (segmentLeft is the atom-box left,
  which is `glyphLeft - 6/15`, so border = `glyphLeft - 4/15`).
- trailing: `right = segmentRight - horizontalMargin - trailingExternalMargin`
  (mirror of the leading side; see below).

### Trailing external margin

Body text that follows a chip (e.g. a CJK `的`) should not be glued to it. That extra
gap is reserved ONLY after the final atom and kept **outside** the border:

- `SLOCK_INLINE_CODE_TRAILING_EXTERNAL_MARGIN_RATIO = 2/15` (one knob, tune to taste).
- final atom `endPadding = edgePadding + trailingExternalMargin` (`6/15 + 2/15 = 8/15`).
- drawer subtracts both `horizontalMargin` and `trailingExternalMargin` for the final
  segment, so the border still stops at `glyphRight + EDGE_PADDING` and the extra
  `2/15` becomes pure outside gap before the following text.

Net: border is symmetric (padding `4/15` both sides), outside gap is `2/15` on the left
and `2/15 + 2/15 = 4/15` on the right (a touch more, since real text follows on the
right). Increase/decrease only `TRAILING_EXTERNAL_MARGIN_RATIO`.

### What went wrong before (anti-pattern)

An earlier attempt set the trailing advance to `edge + (padding+margin) = 12/15` and drew
the border at `segmentRight + horizontalPadding - (padding+margin)`, which lands the
border ~`10/15` PAST the glyphs — a big empty area baked *inside* the chip. That reads as
"the space is too big" even though the constant looked like a margin. Lesson: keep the
two layers' arithmetic explicitly paired and reason about the border position **relative
to the glyph edge**, not as an opaque offset.

## 4. Principles for future markdown chrome fixes

1. **Layout, not content.** Never inject NBSP / real characters to create spacing. Use
   layout advance (span `getSize`) and let the drawer exclude it from the chrome.
2. **Change the right layer.** Spacing around a chip = render layer
   (`core-render-android`). Markdown structure/content = `kuikly-compose-markdown`.
3. **Keep measurement and drawer in lockstep.** If you add advance in `getSize`, adjust
   the matching rectangle in `drawSlockInlineCodeChrome`, and express both in terms of the
   glyph edge so the relationship is auditable.
4. **Bust the AAR cache and verify the APK** before trusting any screenshot (§2).
5. Prefer symmetric geometry as the default; deviate only with an explicit, named knob.

## 5. Key symbols

| Symbol | File | Meaning |
| --- | --- | --- |
| `KRSlockInlineCodeAtomicTextSpan` | KRRichTextBuilder.kt | ReplacementSpan reserving per-atom advance; draws the code glyphs |
| `startPadding` / `endPadding` | KRRichTextBuilder.kt | leading / trailing reserved advance |
| `SLOCK_INLINE_CODE_EDGE_PADDING_RATIO` (4/15) | both | glyph → border |
| `SLOCK_INLINE_CODE_EDGE_MARGIN_RATIO` (2/15) | both | border → neighbour |
| `SLOCK_INLINE_CODE_TRAILING_EXTERNAL_MARGIN_RATIO` (2/15) | both | extra outside gap after the final atom |
| `drawSlockInlineCodeChrome` | KRRichTextViewDrawer.kt | draws fill + border per line segment |
