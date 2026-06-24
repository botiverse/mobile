# Markdown Inline Code Revert

## Decision

Task #29 is intentionally back to the old single-line inline-code behavior.

Only short inline code renders as the yellow framed chip. The chip is an
`InlineTextContent` placeholder backed by a real `Box`, with the same visual
contract as before:

- yellow background;
- black thin border;
- fixed placeholder width and height;
- single-line text.

Long inline code must not render as a wrapped chip, segmented chip group, or
RichText-drawn multi-line box. If it exceeds the short-code gate, it falls back
to the normal markdown code-span text path.

## Current Implementation

`SlockMarkdownInlineTokenRegistry` only creates `InlineCode` tokens when
`shouldRenderThreadInlineCodeAsChip()` returns true. `slockMarkdownAnnotator`
only replaces those tokens with `InlineTextContent`; code spans that do not pass
the short-code gate remain in normal markdown text.

This means:

- short inline code uses the old single-line placeholder chip;
- long inline code has no Slock inline-content token;
- mention, channel, task, and thread chips still use inline placeholders.

## Non-Goals

- Do not split long inline code into multiple placeholder chips.
- Do not draw long inline code as per-line RichText boxes.
- Do not add a public RichText decoration API for inline code.
- Do not reintroduce Android `LineBackgroundSpan` background/border patches for
  this task.
- Do not push remote main without explicit owner approval.

## Relevant Kuikly References

This decision deliberately avoids new Kuikly RichText surface area. If future
work revisits RichText rendering, re-read:

- `third_party/kuikly-ui/docs/API/components/rich-text.md`
- `third_party/kuikly-ui/docs/API/components/text.md`
