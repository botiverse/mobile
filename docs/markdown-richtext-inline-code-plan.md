# Markdown Inline Code RichText Plan

## Context

Slock's old short inline-code renderer used `InlineTextContent` and a fixed `Placeholder`.
That made the code chip visually stable because the frame was a real `Box`:

- `background(BrandYellow.copy(alpha = 0.4f))`
- `border(SlockStroke.Thin, SlockColor.BorderBlack)`
- fixed placeholder width/height
- `KuiklyText(maxLines = 1)`

The weakness is structural: a placeholder is one atomic inline object. It cannot wrap inside the
code token. Splitting long code into multiple placeholders creates visible gaps, stacked boxes, or
measurement drift.

The reverted experiments tried to move inline code into RichText text flow, then patched Android
`LineBackgroundSpan` to paint background and borders. That made wrapping possible, but the frame was
computed from partial span callbacks rather than the final text layout. The result was asymmetric
left/right padding and line-end overpaint.

## Current Revert State

The revert intentionally returns task #29 inline code to the pre-RichText-decoration behavior:

- short inline code may render as the old placeholder chip when it fits the existing short-code gate;
- long inline code is no longer forced through Slock placeholder segmentation or custom RichText
  background/border drawing;
- Kuikly submodule is reset to the pre-inline-code-background pointer for this work chain.

This keeps the app out of the visually wrong in-between state while the proper fix is designed.

## Relevant Kuikly Contract

Kuikly `RichText` supports `Span` and inherits `Text` properties, per:

- `third_party/kuikly-ui/docs/API/components/rich-text.md`
- `third_party/kuikly-ui/docs/API/components/text.md`

The documented `Span` API covers text styling, but it does not define a complete inline decoration
contract for background + border + padding + radius that behaves like CSS inline boxes across wraps.

Therefore the correct fix is not another Markdown-side placeholder workaround. It is a RichText
engine capability.

## Proposed Core Fix

Yes, Slock can maintain an internal RichText fork through `third_party/kuikly-ui`. The fork should be
small and explicit: add a span-decoration contract to Kuikly RichText, then consume it from
SlockMarkdown. Avoid encoding Slock-specific inline-code assumptions in the generic text renderer.

### 1. Add A Span Decoration Contract

Extend the Compose-to-Kuikly RichText bridge so `SpanStyle.background` and future inline-code chrome
can travel as structured metadata instead of being lost or guessed later.

Minimum fields:

- `backgroundColor`
- `borderColor`
- `borderWidth`
- `cornerRadius`
- `paddingHorizontal`
- `paddingVertical`

The contract should be generic enough for inline code, highlights, and future span decorations.
SlockMarkdown can then mark code spans as "inline decoration" without creating placeholder chips.

### 2. Render Decorations From Final Text Layout

Android should not rely on `LineBackgroundSpan.drawBackground(...)` alone for this use case. That
callback receives limited line information and encourages left/right guesses.

Instead, the RichText renderer should draw span decorations using the final text `Layout`:

1. Build the normal `SpannableStringBuilder`.
2. Preserve decoration ranges as metadata spans.
3. In `KRRichTextViewDrawer`, before drawing text, iterate decorated ranges.
4. For each range and each visual line it intersects:
   - compute `lineStart`, `lineEnd`, `lineTop`, `lineBottom`, `lineBaseline` from `Layout`;
   - compute the actual start/end x from the range offsets on that line;
   - inflate by the declared padding;
   - draw background and border behind text.
5. Then draw the normal text layout.

This uses the same layout that actually wraps the text, so the first-line left edge, wrapped-line
left edge, and final-line right edge are derived from real glyph/run positions rather than from
manual `measureText` deltas.

### 3. Keep Markdown Simple

After RichText decoration works, SlockMarkdown should:

- emit all inline code as normal text spans, short and long;
- add soft wrap opportunities only where needed for long code;
- apply the inline-code decoration style to the code span;
- stop using `InlineTextContent` for code;
- keep `InlineTextContent` only for true chips such as mentions, channels, tasks, and thread refs.

That gives one code path for short and long inline code.

### 4. Platform Strategy

Implement Android first because the current visible bug is Android-specific and the owner feedback is
from Android screenshots.

Then either:

- add equivalent decoration rendering to iOS/OHOS renderers, or
- gate the new decoration metadata so unsupported platforms fall back to text-only background until
  their renderer has the same layout-backed drawing.

Do not regress existing line-height fixes while doing this. The current pre-decoration Kuikly
submodule pointer already includes the accepted line-height baseline work.

## Acceptance Criteria

Functional:

- short and long inline code use the same Markdown path;
- long inline code wraps inside the code token;
- every wrapped visual line has one continuous yellow frame;
- no visible gaps between internal soft-break pieces;
- no left-side over-padding or right-side overpaint at line ends;
- no placeholder stacking or clipping.

Renderer:

- Android RichText span decorations are computed from final `Layout` line/range geometry;
- decorations are drawn behind text and do not affect text content, selection text, or accessibility
  plain text;
- the feature is not hardcoded to Slock inline code names.

Validation:

- focused `SlockMarkdownTest` for short/long code token planning and text preservation;
- Kuikly renderer unit test for decorated spans across one line, wrapped lines, and mixed normal text;
- Android connected visual `captureThreadMessageMarkdownChips`;
- visual screenshot inspection against the old short-code frame and the linked reference renderer.

## Non-Goals

- Do not keep tuning `LineBackgroundSpan` padding constants to match screenshots.
- Do not split long inline code into multiple placeholder chips.
- Do not add Markdown-specific code to platform renderers.
- Do not push remote main without explicit owner approval.
