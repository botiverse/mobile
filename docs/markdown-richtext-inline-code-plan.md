# Markdown Inline Code RichText Internal Plan

> **Obsolete (2026-10-20):** plan for Kuikly RichText internals; Kuikly UI was removed in the Compose Multiplatform switch. Kept for history only.

## Decision

Task #29 should not keep solving long inline code in `RaftMarkdown` with
placeholder segmentation, soft-break chip groups, or Markdown-side layout
workarounds. The current single-line short-code chip is only the safe reverted
state while the real fix moves into Kuikly RichText internals.

Target behavior:

- Markdown emits inline code as text, not `InlineTextContent` chips.
- Kuikly RichText carries an internal Slock inline-code marker for code-span
  ranges.
- Android RichText draws each visual line segment of a marked code span as the
  old short-code-style yellow box with black border.
- The implementation is internal and narrow; it is not a public arbitrary
  RichText decoration API.

## Source Findings

Relevant Kuikly public docs:

- `third_party/kuikly-ui/docs/API/components/rich-text.md`
- `third_party/kuikly-ui/docs/API/components/text.md`

The documented RichText API supports `Span`, `ImageSpan`, and
`PlaceholderSpan`. `Span` inherits Text properties, but there is no documented
background + border + padding + radius contract for inline boxes across wraps.
`SpanStyle.background` also exists in the Compose text model but is explicitly
commented as unsupported in Kuikly.

Relevant code paths:

- `third_party/kuikly-compose-markdown/.../AnnotatedStringKtx.kt`
  handles `MarkdownElementTypes.CODE_SPAN` by pushing `codeSpanStyle`, appending
  text, and popping the style.
- `compose/shared/.../markdown/RaftMarkdown.kt` currently intercepts short
  code spans as inline placeholder chips.
- `third_party/kuikly-ui/compose/.../KuiklyTextExtension.kt`
  converts `AnnotatedString` into RichText `TextSpan` / `PlaceholderSpan`
  values. It collects style, paragraph, link, and inline-content positions, but
  not arbitrary custom string annotations.
- `third_party/kuikly-ui/core/.../RichTextView.kt` serializes each `TextSpan`
  into the RichText `values` prop through `spanPropsMap()`.
- `third_party/kuikly-ui/core-render-android/.../KRRichTextBuilder.kt` parses
  `values`, converts each `TextSpanProps` into Android spans, and records
  `SpanTextRange`.
- `third_party/kuikly-ui/core-render-android/.../KRRichTextViewDrawer.kt`
  owns the final Android `Layout` and currently draws via `textLayout.draw`.

This means the right insertion point is:

1. mark code ranges in `AnnotatedString`;
2. transport that marker into RichText `values`;
3. parse it into an Android metadata span;
4. draw boxes from the final `Layout` before drawing text.

## Proposed Implementation

### 1. Mark Code Spans In Slock Markdown

Add a Slock-only annotation tag, for example
`build.raft.app.markdown.inlineCode`, around every Markdown `CODE_SPAN` range.

Use the existing `raftMarkdownAnnotator` interception path so Slock controls
code-span output instead of relying on the vendored markdown default that
injects surrounding ASCII spaces. For long code, append the real code text and
the internal marker. For short code, after the RichText renderer is ready, stop
using `InlineTextContent` and use the same marker path.

Keep mention/channel/task/thread refs on `InlineTextContent`; only code moves
to RichText text flow.

### 2. Transport The Marker Through Kuikly Compose RichText

Extend `RichTextAttr.applyAnnotatedString(...)` in Kuikly Compose to collect
the Slock inline-code string annotation ranges, add their boundaries to the
segment split positions, and mark generated `TextSpan`s whose segment overlaps
the annotation.

Because `TextAttr` does not expose an arbitrary public prop setter, add a
small internal/fork-only helper or prop in Kuikly rather than exposing generic
decoration fields. Suggested wire shape:

- prop key: `slockInlineCode`;
- value: `1` / `true`;
- applies only to generated RichText `TextSpan` values;
- ignored by non-Android renderers until implemented there.

This stays narrower than generic `backgroundColor`, `borderColor`,
`borderWidth`, `padding`, or `borderRadius` span APIs.

### 3. Parse The Marker In Android RichText

Extend `TextSpanProps` / `KRRichTextBuilder.createTextSpan(...)` to recognize
the internal marker and attach a lightweight metadata span, for example
`KRSlockInlineCodeSpan`, over the exact text range.

The marker span should not affect measurement, glyph paint, selection text, or
accessibility plain text. It only identifies ranges for background drawing.

### 4. Draw Boxes From Final Android Layout

In `KRRichTextViewDrawer.draw(canvas)`:

1. If `textLayout.text` is `Spanned`, collect `KRSlockInlineCodeSpan` ranges.
2. For each marked range, iterate the visual lines intersecting the range.
3. Clip to visible line text and ellipsis:
   - use `getLineStart(line)`, `getLineEnd(line)`;
   - skip fully ellipsized/hidden segments;
   - for truncated final lines, cap the segment at the visible end.
4. Compute horizontal bounds from the final `Layout`:
   - segment start x from `getPrimaryHorizontal(segmentStart)`;
   - segment end x from `getPrimaryHorizontal(segmentEnd)`;
   - normalize for direction and clamp to line left/right.
5. Compute vertical bounds from line top/bottom/baseline and current font
   metrics, then apply the old short-code chrome:
   - yellow fill;
   - black thin stroke;
   - old short-code horizontal padding;
   - old short-code vertical padding / height.
6. Draw the boxes behind text, then call `textLayout.draw(canvas)`.

Do not use `LineBackgroundSpan.drawBackground(...)` for this. The previous
experiments showed that span callbacks encourage guesses about line ends and
padding; the drawer has the final `Layout`, which is the actual wrap geometry.

### 5. Keep Platform Scope Deliberate

Android is first because the reported screenshots and current RichText renderer
are Android. iOS/OHOS may initially ignore `slockInlineCode`, rendering inline
code as plain styled text until product asks for visual parity there.

If another platform needs parity, copy the same internal marker approach into
that renderer. Do not expand the first cut into a public cross-platform RichText
decoration API.

## Validation Plan

- Shared markdown test: every code span gets the Slock inline-code annotation,
  with no injected ASCII padding and with visible text preserved.
- Compose bridge test: annotated inline-code ranges become RichText values with
  `slockInlineCode` on the correct generated `TextSpan` segments.
- Android renderer test: `KRSlockInlineCodeSpan` ranges across one line,
  wrapped lines, mixed normal text, and truncated lines produce expected boxes
  from final `Layout` geometry.
- Existing regression: mention/channel/task/thread inline chips still use
  placeholders and remain clickable.
- Focused Android visual: `captureThreadMessageMarkdownChips`, compared against
  the old short-code frame for both short and long inline code.
- Compile checks after Kuikly submodule changes:
  - Kuikly renderer unit tests;
  - publish pinned `core-render-android` to MavenLocal if needed;
  - Slock `:shared:generateSqlDelightInterface`;
  - focused `RaftMarkdownTest`;
  - `:shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin`.

## Kuikly Fork Versioning

Do not publish Slock-patched Kuikly artifacts with the exact upstream Maven
version after the first local validation pass. Release candidates and shipped
builds should use an explicit Slock patch suffix such as `-raft.1`,
`-raft.2`, etc. Reusing the upstream version makes Gradle/Maven cache state
ambiguous, especially for renderer fixes where a stale AAR can still package
successfully.

For local development before a suffixed artifact is cut, put `mavenLocal()`
before remote repositories, publish the patched Kuikly artifact with the app's
expected version, and verify the packaged APK by bytecode/decompile proof before
trusting screenshots.

## Non-Goals

- Do not split long inline code into multiple placeholder chips.
- Do not keep short and long code on separate Markdown rendering paths after
  the RichText marker lands.
- Do not expose generic RichText inline decoration API surface in this task.
- Do not reintroduce `LineBackgroundSpan` background/border drawing.
- Do not push remote main directly. Mobile changes must go through a GitHub PR
  to protected `main`.
