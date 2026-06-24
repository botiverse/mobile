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

## Minimal Core Fix

Yes, Slock can maintain an internal RichText fork through `third_party/kuikly-ui`, but the first fix
should be deliberately small. Do not implement a full RichText decoration system yet. The minimal
target is:

> Long inline code remains normal RichText text, but the Android RichText renderer draws each visual
> wrapped line as an old short-code-style box.

That gives the product effect the owner asked for without spreading a generic CSS-like span API
across all platforms.

### 1. Add One Internal Inline-Code Marker

Extend the Compose-to-Kuikly RichText bridge with one internal marker for Slock inline code ranges.
Examples of acceptable shapes:

- a boolean span prop such as `slockInlineCode=true`;
- or an internal marker span class around the code range after the RichText builder parses props.

Do not add public arbitrary fields such as border color, radius, or padding in this first pass. The
renderer can hardcode the old Slock short-code chrome:

- yellow background: `BrandYellow.copy(alpha = 0.4f)` equivalent;
- black thin border;
- old short-code horizontal chrome;
- old short-code height / vertical chrome.

This keeps the fork small and avoids pretending Kuikly has a complete generic inline decoration API.

### 2. Render Inline-Code Boxes From Final Text Layout

Android should not rely on `LineBackgroundSpan.drawBackground(...)` for this use case. That callback
receives limited line information and encourages left/right guesses.

Instead, the Android RichText renderer should draw only the marked inline-code ranges using the final
text `Layout`:

1. Build the normal `SpannableStringBuilder`.
2. Preserve inline-code ranges as metadata spans.
3. In `KRRichTextViewDrawer`, before drawing text, iterate those ranges.
4. For each range and each visual line it intersects:
   - compute `lineStart`, `lineEnd`, `lineTop`, `lineBottom`, `lineBaseline` from `Layout`;
   - compute the actual start/end x from the range offsets on that line;
   - inflate by the fixed old short-code padding;
   - draw one yellow fill + black border box behind that visual line.
5. Then draw the normal text layout.

This uses the same layout that actually wraps the text, so the first-line left edge, wrapped-line
left edge, and final-line right edge are derived from real glyph/run positions rather than from
manual `measureText` deltas.

### 3. Keep Markdown Simple

After the minimal RichText marker works, SlockMarkdown should:

- emit all inline code as normal text spans, short and long;
- add soft wrap opportunities only where needed for long code;
- apply the internal inline-code marker to the code span;
- stop using `InlineTextContent` for code;
- keep `InlineTextContent` only for true chips such as mentions, channels, tasks, and thread refs.

That gives one code path for short and long inline code.

### 4. Platform Strategy

Implement Android first because the current visible bug is Android-specific and the owner feedback is
from Android screenshots.

For iOS/OHOS, do not block Android on a broad cross-platform abstraction. The first acceptable
fallback is plain inline code text/background until those renderers need the same per-line box
behavior. If this becomes product-critical on another platform, copy the same minimal internal marker
there instead of expanding to a public generic decoration API.

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

- Android RichText inline-code boxes are computed from final `Layout` line/range geometry;
- boxes are drawn behind text and do not affect text content, selection text, or accessibility plain
  text;
- the feature is an internal Slock inline-code marker, not a public arbitrary RichText decoration API.

Validation:

- focused `SlockMarkdownTest` for short/long code token planning and text preservation;
- Kuikly renderer unit test for marked inline-code ranges across one line, wrapped lines, and mixed
  normal text;
- Android connected visual `captureThreadMessageMarkdownChips`;
- visual screenshot inspection against the old short-code frame and the linked reference renderer.

## Non-Goals

- Do not keep tuning `LineBackgroundSpan` padding constants to match screenshots.
- Do not split long inline code into multiple placeholder chips.
- Do not build a full public RichText decoration API in the first pass.
- Do not expose Slock-specific inline-code styling as a general Kuikly API before another use case
  needs it.
- Do not push remote main without explicit owner approval.
