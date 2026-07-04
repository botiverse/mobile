# Markdown Tag Style Contract Spec

Date: 2026-07-05
Owner: KMP / Kuikly shared
Status: Draft

## Summary

The current markdown tag active-feedback fix is directionally useful for Android, but the styling contract is wrong for long-term three-platform parity.

`slockMarkdownTagChrome` encodes Slock business kinds (`channel`, `thread`, `task`, `selfMention`, `active`) and asks the Android RichText renderer to draw those specific chip backgrounds/borders itself. iOS and OHOS do not consume that annotation, so they only receive the generic `SpanStyle.background` fallback. That creates exactly the mismatch reported in task #541: Android can show full chip chrome, while iOS/OHOS only show text-background chrome.

The target contract should be css-like at the RichText span layer:

- common markdown decides semantic token state: kind, highlighted, active, pressed;
- common style resolver maps state to a platform-neutral span box style;
- Kuikly RichText transports box style as generic renderer data, not Slock product enums;
- Android/iOS/OHOS render the same subset of box style from the same contract;
- Slock-specific tag names disappear from renderer code except during migration.

This keeps route/pageData clean: active feedback stays local render state and never becomes launch payload or product state.

## Sources Checked

- Slock markdown annotation and tag style:
  - `compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt`
  - `compose/shared/src/commonTest/kotlin/build/raft/app/markdown/SlockMarkdownTest.kt`
- Thread active state propagation:
  - `compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt`
- Kuikly Compose RichText span transport:
  - `third_party/kuikly-ui/compose/src/commonMain/kotlin/com/tencent/kuikly/compose/foundation/text/KuiklyTextExtension.kt`
  - `third_party/kuikly-ui/compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/text/SpanStyle.kt`
- Platform renderers:
  - `third_party/kuikly-ui/core-render-android/src/main/java/com/tencent/kuikly/core/render/android/expand/component/text/KRRichTextViewDrawer.kt`
  - `third_party/kuikly-ui/core-render-ios/Extension/AdvancedComps/KRRichTextView.m`
  - `third_party/kuikly-ui/core-render-ohos/src/main/cpp/libohos_render/expand/components/richtext/KRRichTextShadow.cpp`
- Kuikly docs:
  - `references/KuiklyUI/docs/API/components/basic-attr-event.md`
  - `references/KuiklyUI/docs/API/components/text.md`

## Current Behavior

### Common Markdown

`SlockMarkdown.kt` currently builds an `AnnotatedString` for inline Slock tags. For each token it:

- pushes `SlockMarkdownTagAnnotationTag = "raft.build.markdown.tag"` for click identity;
- computes active state from `activeMarkdownTagKey`;
- pushes `SlockMarkdownTagChromeAnnotationTag = "raft.build.markdown.tagChrome"` with a `SlockMarkdownTagChromeKind`;
- attaches `LinkAnnotation.Clickable` with `TextLinkStyles`;
- applies `SpanStyle` with font, color, underline, and `background`.

The important split is:

- `SpanStyle.background` is a general text span style.
- `slockMarkdownTagChrome` is a Slock-specific annotation consumed only by Android native RichText.

### Android

`KuiklyTextExtension.kt` collects `raft.build.markdown.tagChrome` annotations and emits them into a RichText `TextSpan` prop via `slockMarkdownTagChrome(kind)`.

`KRRichTextViewDrawer.kt` then searches for `KRSlockMarkdownTagSpan` and draws Slock chip chrome directly in Android native layout:

- fill before `textLayout.draw(canvas)`;
- border after `textLayout.draw(canvas)`;
- padding/margins/min-height derived from text size;
- color selected from the Slock-specific kind.

That is why Android can show full chip feedback, including border and horizontal padding.

### iOS

`KRRichTextView.m` parses span `backgroundColor` into `NSBackgroundColorAttributeName`. It does not consume `slockMarkdownTagChrome`.

Result: iOS can render active text background through `SpanStyle.background`, but not Android's chip padding/border/full-height rectangle.

### OHOS

`KRRichTextShadow.cpp` parses span `backgroundColor` and applies it through `OH_Drawing_SetTextStyleBackgroundBrush`. It does not consume `slockMarkdownTagChrome`.

Result: OHOS can render active text background through `SpanStyle.background`, but not Android's chip padding/border/full-height rectangle.

## Problem

The current contract mixes three concerns:

1. **Product semantics**: user mention, channel, thread, task, highlighted, active.
2. **Design style**: background color, border color, border width, padding, radius, underline.
3. **Renderer implementation**: Android `ReplacementSpan` / layout draw pass.

`slockMarkdownTagChrome` carries product semantics into the renderer. Once renderer code sees `"task"` or `"active"`, the renderer must know Slock visual rules. That is the wrong dependency direction.

The renderer should know how to draw a text span box. It should not know what a Slock task tag is.

## Why We Got Here

The original need was practical: `SpanStyle.background` alone could not express the chip shape Android needed. Kuikly's current `SpanStyle` supports text-level attributes such as color, font, background, underline, shadow, and decoration offsets, but it does not expose a generic inline span box model for border/padding/radius.

So the Android path introduced a native extension:

```text
Slock token -> "raft.build.markdown.tagChrome" annotation -> Android KRSlockMarkdownTagSpan -> draw chip
```

This solved an Android visual gap quickly, but it bypassed common style semantics. The active-feedback follow-up then extended the Slock enum with `Active`, which made Android correct while making iOS/OHOS only partially correct.

That is the signal that the abstraction is too specific.

## Desired Contract

Introduce a platform-neutral RichText span box style:

```kotlin
@Serializable
data class RichTextSpanBoxStyle(
    val backgroundColor: String? = null,
    val borderColor: String? = null,
    val borderWidthSp: Float = 0f,
    val borderRadiusSp: Float = 0f,
    val paddingLeftSp: Float = 0f,
    val paddingRightSp: Float = 0f,
    val paddingTopSp: Float = 0f,
    val paddingBottomSp: Float = 0f,
    val marginLeftSp: Float = 0f,
    val marginRightSp: Float = 0f,
    val minHeightSp: Float = 0f
)
```

This is intentionally closer to a small CSS box model than to Slock product names. It should be renderer-generic and usable by inline code, markdown tags, future badges, and visual fixtures.

Slock then owns product mapping in commonMain:

```kotlin
data class SlockMarkdownTagVisualState(
    val kind: ThreadMarkdownTagKind,
    val highlighted: Boolean,
    val active: Boolean
)

fun slockMarkdownTagTextStyle(state: SlockMarkdownTagVisualState): SpanStyle

fun slockMarkdownTagBoxStyle(state: SlockMarkdownTagVisualState): RichTextSpanBoxStyle?
```

Renderer input becomes:

```text
TextSpan {
  value = "#channel"
  fontSize = 14sp
  color = "#141111"
  backgroundColor = "#..."
  spanBoxStyle = {
    backgroundColor = "#..."
    borderColor = "#141111"
    borderWidthSp = 1
    paddingLeftSp = 6
    paddingRightSp = 6
    borderRadiusSp = 0
  }
}
```

No renderer sees `"channel"`, `"task"`, or `"active"`.

## Contract Rules

1. `SpanStyle.background` remains valid for plain text background and as a fallback.
2. `RichTextSpanBoxStyle` is the only way to request chip-like border/padding/radius in RichText.
3. `RichTextSpanBoxStyle` must be optional. If a renderer cannot draw it, it must still render legible text using `SpanStyle`.
4. Business/product enums must not cross into platform renderers.
5. Active/pressed/focused/hovered are visual states resolved in commonMain before rendering.
6. The annotation payload must be generic. Acceptable names:
   - `raft.richtext.spanBoxStyle`
   - `kuikly.richtext.spanBoxStyle`
   - `richTextSpanBoxStyle`
7. The Slock-specific `raft.build.markdown.tagChrome` annotation becomes a migration shim only.

## Rendering Semantics

The initial supported subset should be deliberately small:

- background color;
- border color;
- border width;
- left/right/top/bottom padding;
- optional min-height;
- optional radius, but radius `0` is acceptable for Slock's square visual language.

The renderer should draw the style around the shaped text segment for each line fragment:

- multi-line spans draw one box per line fragment;
- start fragment applies left margin/padding;
- end fragment applies right margin/padding;
- middle fragments fill to line segment bounds;
- border draws after text if design requires crisp edge, or before text if platform limitations demand it, but final appearance must match visual fixtures.

This is essentially what Android already does for `KRSlockMarkdownTagSpan`; it needs to be renamed and generalized.

## Platform Plan

### Android

Replace `KRSlockMarkdownTagSpan` with a generic `KRRichTextSpanBoxSpan` or equivalent. The existing Android draw algorithm can be reused:

- parse generic box style from span props;
- draw fill before text;
- draw border after text;
- remove Slock-specific fill-color mapping from renderer.

During migration, `slockMarkdownTagChrome` can be translated into generic box style in `KuiklyTextExtension.kt`, but new Slock code should emit only generic box style.

### iOS

Current iOS `NSBackgroundColorAttributeName` is not enough for border/padding. iOS needs a renderer-side custom span box path.

Likely implementation options:

- custom layout/draw pass around attributed-string glyph ranges;
- TextKit range rect enumeration for line fragments;
- attachment-backed text run only if it can preserve selection/link behavior, which is risky.

Spec requirement: do not call iOS parity complete until border/padding/min-height are drawn from the same generic style contract.

### OHOS

Current OHOS background brush is not enough for border/padding. OHOS needs a renderer-side custom span box path around typography glyph ranges or line metrics.

Spec requirement: do not call OHOS parity complete until border/padding/min-height are drawn from the same generic style contract.

## Migration Plan

### Phase 0: Freeze The Current Enum

Do not add more Slock product kinds to `SlockMarkdownTagChromeKind`.

`Active` should be treated as the last emergency extension, not the pattern to repeat.

### Phase 1: Add Generic Span Box Data Model

In Kuikly Compose RichText transport:

- add a generic span-box annotation or TextSpan prop;
- serialize the box style to existing RichText span values;
- add tests proving the generated span payload contains only generic style keys.

In Slock:

- add `slockMarkdownTagBoxStyle(state)` in commonMain;
- keep `slockMarkdownTagSpanStyle(state)` for text-only attributes;
- make active state select a different generic box style, not a renderer enum.

### Phase 2: Android Renderer Generalization

Refactor Android:

- `KRSlockMarkdownTagSpan` -> generic span box span;
- `drawSlockMarkdownTagChrome` -> `drawRichTextSpanBoxes`;
- move Slock color mapping out of renderer;
- keep legacy annotation adapter until all callers migrate.

Expected result: Android should look the same as the current fixed behavior.

### Phase 3: iOS/OHOS Renderer Support

Implement the same generic span box subset on iOS/OHOS.

Minimum acceptance:

- normal channel/task/thread tag chip background matches Android;
- active tag chip uses the same active background/border;
- ordinary mention remains underline-only unless highlighted/active;
- multi-line wrapping does not crash and remains legible.

### Phase 4: Remove Slock-Specific Renderer Contract

After three-platform parity:

- remove `SlockMarkdownTagChromeKind` from renderer transport;
- remove tests that assert Slock product enum transport;
- replace them with tests that assert generic style payload;
- leave Slock markdown tests at the common resolver layer.

## Test Plan

### Common Unit Tests

Add tests for:

- `slockMarkdownTagBoxStyle(Channel, active=false)` returns channel background + border/padding;
- `slockMarkdownTagBoxStyle(Channel, active=true)` returns active background + same box metrics;
- ordinary mention returns no box style unless active, depending final design;
- renderer payload contains generic keys and no Slock product enum strings;
- active key rebuild still remounts RichText or invalidates span payload.

### Android Tests

Add or update:

- RichText span payload test for generic box style;
- Android renderer compile test;
- visual fixture for normal and active tags.

### iOS/OHOS Tests

At minimum:

- compile gates for renderer changes;
- screenshot/visual fixture where possible;
- small native renderer unit/probe if screenshot automation is not ready.

## Open Decisions

1. Should ordinary `@mention` active feedback become a chip box, or remain underline plus text background?
2. Do we require square corners for markdown tag boxes, or should radius be configurable for future design tokens?
3. Should inline code and markdown tags share the same generic box style path immediately?
4. Should the generic annotation live under a Kuikly namespace or a Slock namespace while still avoiding product enums?
5. What is the minimum iOS/OHOS proof before closing task #541: compile plus renderer code review, or screenshot parity?

## Recommendation

Do not implement iOS/OHOS parity by adding `slockMarkdownTagChrome` handling to their renderers. That would copy the wrong abstraction.

Implement a generic RichText span box style contract first, then port Android's existing drawing logic to that contract and add iOS/OHOS support against the same data model.

For task #541 specifically:

- keep the current Android active fix as a temporary stopgap;
- document iOS/OHOS as only partially covered by `SpanStyle.background`;
- open/continue implementation work under this spec if owner wants full three-platform parity now.
