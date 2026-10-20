# Markdown Inline Token Registry Spec

Date: 2026-06-21
Owner: KMP / Kuikly shared
Status: Historical design; syntax ownership superseded by `raft-markdown-dialect.md`

> The registry/token-plan and typed-rendering parts of this document remain
> useful context. Its raw `find(content)` rule model is no longer the syntax
> source of truth. Raft reference recognition is parser-aware and specified in
> `docs/raft-markdown-dialect.md`.

## Problem

Thread markdown currently has two rendering paths for inline product tokens:

- the Slock inline fast path in `RaftMarkdownBody`, used for single-line content without block markdown;
- the vendored `kuikly-compose-markdown` renderer, used for block markdown and richer content.

Both paths eventually call `raftMarkdownAnnotator`, but the token rules and UI are embedded inside one Slock-specific annotator. The full markdown path can paint a default text/span frame before `InlineTextContent` replacement is fully applied, so tokens such as `@alice` can briefly appear with the wrong/default style and then flash into the intended Slock style.

This is most visible for mentions because the desired Slock rendering is not just a color span. It may be an inline composable with underline, click handling, label resolution, highlighted mention state, and fixed placeholder width.

## Goals

- Build the complete inline token plan synchronously before either markdown render path enters text layout.
- Make fast-path and full markdown rendering consume the same token registry and token plan.
- Let Slock register product tokens (`@user`, `#channel`, `#channel:thread`, `#task`, short inline-code chip) without hard-coding every rule in the vendored markdown annotator.
- Preserve click behavior and label resolution.
- Avoid first-frame fallback styling for tokens that will be replaced by inline content.
- Keep parser/cache keys stable so preloading and row reuse do not recreate token UI unnecessarily.

## Non-Goals

- Do not redesign markdown syntax.
- Do not move repository/member lookup into the renderer.
- Do not make token rendering depend on async work during composition.
- Do not introduce platform-specific token behavior; Android/OHOS must share the same commonMain plan.

## Current Pipeline

`RaftMarkdownBody` normalizes the body, creates a `MarkdownAnnotator`, then chooses one of three paths:

1. plain text fast path: direct `KuiklyText`;
2. inline text fast path: parse a small markdown tree, build an `AnnotatedString`, then call `KuiklyText` with inline content;
3. full markdown path: call vendored `Markdown(...)`, which parses and renders markdown blocks, then `MarkdownText`/`MarkdownBasicText` calls `TextProvider.RichText`.

The Slock token behavior is concentrated in `raftMarkdownAnnotator`:

- scan content for `ThreadInlineTagMatch` and inline-code matches;
- replace token ranges with `appendInlineContent(id, alternateText)`;
- build `InlineTextContent` map for each token id;
- render mention text/chips/code chips through Slock composables.

The weakness is timing and ownership. The token scan lives inside annotator construction, but the renderer still owns when text nodes are parsed, annotated, measured, and replaced. If a text node reaches Kuikly text layout without the final token placeholder/content map on the first frame, the user sees default text style first.

## Proposed Architecture

Introduce a small registry layer in commonMain:

```kotlin
data class MarkdownInlineTokenPlan(
    val source: String,
    val tokens: List<MarkdownInlineToken>
)

data class MarkdownInlineToken(
    val id: String,
    val range: IntRange,
    val alternateText: String,
    val widthSp: Float,
    val heightSp: Float,
    val kind: MarkdownInlineTokenKind,
    val label: String,
    val metadata: Map<String, String> = emptyMap()
)

interface MarkdownInlineTokenRule {
    val id: String
    fun find(content: String): List<MarkdownInlineTokenMatch>
    fun render(token: MarkdownInlineToken, callbacks: MarkdownInlineTokenCallbacks): @Composable () -> Unit
}
```

Slock provides a default registry:

- `ThreadMentionTokenRule`
- `ThreadChannelTokenRule`
- `ThreadChannelThreadTokenRule`
- `ThreadTaskTokenRule`
- `ThreadInlineCodeChipRule`

The registry is evaluated once per normalized `content` before choosing a render path:

```kotlin
val tokenPlan = remember(content, highlightedMentions, resolveLabelVersion) {
    RaftMarkdownInlineTokenRegistry.plan(
        content = content,
        highlightedMentions = highlightedMentions,
        resolveTagLabel = resolveTagLabel
    )
}
```

Then both render paths consume the same `tokenPlan`:

- inline fast path builds `AnnotatedString` from `content + tokenPlan`;
- full markdown path passes `tokenPlan` into the vendored markdown annotator bridge;
- `InlineTextContent` map is derived from `tokenPlan` and remembered by the plan identity, not rebuilt independently by the renderer.

## Vendored Markdown Hook

Add a generic extension point to `kuikly-compose-markdown` instead of embedding Slock logic in vendor internals:

```kotlin
interface MarkdownInlineReplacementProvider {
    fun replacementsFor(markdownContent: String, node: ASTNode): List<MarkdownInlineReplacement>
    @Composable
    fun InlineContent(id: String)
}
```

The Slock adapter wraps `MarkdownInlineTokenPlan`:

- For each `TEXT` node, return replacements whose absolute ranges fall inside the node.
- For each `CODE_SPAN`, allow the inline-code rule to replace the code span only when the code chip width is under the configured maximum.
- For each replacement, the vendor annotator appends plain text between ranges, then `appendInlineContent(token.id, token.alternateText)`.

The important contract is that `Markdown(...)` receives both the replacement provider and its full `inlineContent` map before the first `MarkdownBasicText` call.

## Rendering Contract

Each token must provide:

- stable `id`, derived from rule id + absolute start offset;
- stable range in normalized content;
- placeholder width/height calculated before text layout;
- alternate text used only for semantics/fallback;
- composable renderer for final inline content;
- click callback that does not capture stale lambdas.

If a label cannot be resolved synchronously, render the raw token label in final Slock styling. Do not render default markdown text and then upgrade it later.

## Cache Keys

Update markdown caches to include token plan identity where needed:

- prepared body cache remains keyed by raw body;
- inline text render cache key becomes `(content, resolvedStyle, tokenPlan.signature)`;
- full markdown parse cache remains keyed by content, because token planning does not change AST;
- inline content map is remembered by `tokenPlan.signature` and current callbacks.

The token plan signature should include token ids, ranges, kind, label, placeholder size, highlighted state, and render variant. It should not include callback object identity.

## Migration Steps

1. Extract current Slock token scan/render logic from `raftMarkdownAnnotator` into `MarkdownInlineTokenRule` implementations.
2. Add `MarkdownInlineTokenPlan` and derive it once in `RaftMarkdownBody`.
3. Change the inline fast path to build annotated text from the token plan instead of rescanning content inside the annotator.
4. Add a vendored markdown hook that accepts a replacement provider and inline content map before rendering text nodes.
5. Adapt `raftMarkdownAnnotator` to be a thin bridge from `MarkdownInlineTokenPlan` to the existing `MarkdownAnnotator` API.
6. Add visual/log proof: first `provider_rich_text` for a message with `@user` must already contain inline content and never paint default/white token text.

## Tests

- Unit: registry consumes the canonical parse snapshot's typed Raft refs and
  inline-code spans; app validation may reject a typed ref, but must not rescan
  Markdown delimiters or rebuild source ranges.
- Unit: token plan ids are stable across repeated calls for the same content.
- Unit: unresolved mention labels still render final Slock mention style using the raw label.
- Unit: inline fast path and full markdown path produce the same inline content ids for mixed text.
- Visual: message with block markdown plus `@user` enters first frame with black/underlined mention, no white/default flash.
- Regression log: repeated render must not rebuild token plan unless normalized content or highlight set changes.

## Risks

- Placeholder width must be deterministic before layout; if label resolution changes after first render, the row may still relayout. That is acceptable only when the label data actually changed.
- The vendor hook should remain generic. It must not reference Slock model types.
- Inline content callbacks must use `rememberUpdatedState` at the Slock adapter boundary to avoid stale click handlers while keeping token plan cacheable.

## Open Questions

- Whether inline-code chips should stay in the same registry as product refs or be a separate markdown style rule.
- Whether token rules should be ordered by priority or reject overlaps through a single deterministic resolver. Initial recommendation: priority order `code span > channel thread > task > channel > mention`, with overlap rejection logged in debug builds.
