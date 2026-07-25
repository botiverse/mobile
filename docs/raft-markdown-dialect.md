# Raft Markdown dialect

Date: 2026-07-25
Owner: KMP / Kuikly shared
Status: Implemented

## Purpose

Raft messages use GFM plus product references:

- `@member`
- `#channel`
- `#channel:threadShortId`
- numeric `#task`

These forms are syntax, not a paint-time text search. Markdown constructs own
their descendants. A reference-shaped substring inside a link, image, code
span/fence, autolink, or HTML node must retain the semantics and presentation
of that outer Markdown node.

The motivating regression was:

```markdown
[#1487 feat(search): pin entities](https://github.com/botiverse/mobile/pull/1487)
```

The old raw-string scanner promoted `#1487` in the label to a yellow task chip,
splitting one blue external link into two competing actions. The Raft dialect
keeps the whole label under the outer link and creates no nested task action.

## Pipeline and ownership

```text
normalized source
  -> SlockGFMFlavourDescriptor lexer emits GFM + Raft ref tokens
  -> one JetBrains Markdown parse / one cached AST result
  -> canonical inline syntax snapshot (Raft refs + inline-code spans)
       RaftMarkdownDialect collects ref nodes outside Markdown-owned ancestry
  -> app validation (known task/channel/member)
  -> MarkdownInlineTokenPlan
  -> SlockRichTextDocument (tokens + chrome + actions)
  -> Kuikly Markdown renderer
```

The planner and renderer consume the same cached parse result produced by
`SlockGFMFlavourDescriptor`. A planner cache miss publishes that canonical
result for `MarkdownState`; the renderer does not parse the content again.
Raft candidates are first-class `RAFT_REFERENCE` lexer/AST tokens, not an
AST-external overlay pass. There is no second implementation of Markdown
link/fence/delimiter matching and no app-side `MarkdownParser`. The app token
planner consumes `RaftMarkdownInlineRef` and `MarkdownInlineCodeSpan` directly;
it does not convert AST refs back to ranges and rescan/subslice the source, and
it does not run a second backtick matcher for typed rich-text inline code.
`MarkdownInlineCodeSpan.codeText` follows the same CommonMark text rules as the
renderer: line endings become spaces, then one leading/trailing space is
trimmed when the payload is not all spaces.

`RaftMarkdownDialect` returns source-accurate candidates only. Repository data
and viewer state remain outside the parser:

- syntax decides that `#1487` is a numeric task candidate;
- `isTagValid` decides whether task 1487 is known in the current context;
- the app adapter rejects a bare 6-8 character hexadecimal short-id-like
  candidate as a channel, because that rule depends on Raft entity identity;
- typed rich-text IR decides chrome and click action;
- the renderer never upgrades unresolved text asynchronously.

## Precedence

Higher entries own lower entries:

1. code blocks and fenced code;
2. inline code;
3. images and Markdown links (inline, short-reference, full-reference);
4. link definitions, autolinks, and inline/block HTML;
5. Raft inline references;
6. ordinary text.

Ownership is derived from AST ancestry. A Raft token below a Markdown-owned
node is rendered as that parent's literal child and is not collected as a
product reference. Adding a new Markdown link shape is therefore handled by
the GFM parser and its ownership type, not by adding delimiter matching to the
Raft reference recognizer.

## Reference grammar

### Task

`#` followed by one or more ASCII/Unicode digits. A following letter, digit, or
underscore invalidates the task token, so `#25abc` and `#123_foo` remain text.

### Channel

`#` followed by a letter, then up to 63 additional letters, digits, `_`, or
`-`.

### Channel thread

A channel followed by `:` and a 6-8 character hexadecimal short id. A colon
followed by whitespace belongs to prose (`#design: next` recognizes
`#design`). A malformed non-empty short id invalidates the combined candidate.

### Mention

`@` followed by a letter or digit, then up to 63 additional letters, digits,
`_`, `.`, or `-`.

## Boundaries, escaping, and CJK

A candidate opener must be at the start of the source or follow a character
that is not a letter, digit, or `_`. This preserves the pre-dialect contract:

- `word#12`, `snake_#12`, and `汉字#12` stay text;
- `(#12)`, `；#12`, and ` #12` are candidates.

An odd run of backslashes escapes the opener (`\#12` stays text); an even run
does not. This is evaluated on the original source offsets, before visible-text
unescaping.

This CJK rule is intentionally compatibility-preserving. If Web changes to
recognize refs directly adjacent to Han/Kana/Hangul letters, change the dialect
spec and cross-platform conformance tests together rather than silently
changing Android-only behavior.

## Typed rich-text contract

Recognized and context-valid candidates become `SlockRef` tokens in
`SlockRichTextDocument`. Each action has one source range and one click
identity. A Markdown-owned range must contain zero nested `SlockRef` tokens and
zero nested Raft actions.

Presentation labels may be resolved (`@id` -> `@display-name`), but action
identity remains the original source tag. Active/highlighted chrome is derived
after validation and never changes parsing.

## Conformance tests

`RaftMarkdownDialectTest` owns syntax/precedence coverage:

- all four Raft reference shapes in ordinary text;
- production-shaped GitHub link label and URL fragment;
- image, inline code, fenced code, autolink, and HTML ownership;
- escaping and word/CJK adjacency.

`SlockMarkdownTest` owns renderer/action behavior, including standalone refs
remaining clickable and the production link remaining one external link.

`SlockRichTextContractTest` owns IR serialization and asserts that a Markdown
link produces no nested `SlockRef` action.

When adding syntax, update all three layers. A screenshot alone is not a parser
contract, and a parser-only test is not proof that click/chrome behavior stayed
correct.

## Migration rule

The removed functions `threadInlineTagRanges`, `scanThreadHashRange`, and the
Markdown protection-range helpers must not be reintroduced. Product-reference
grammar belongs in `RaftMarkdownDialect`; Markdown nesting belongs to the GFM
AST; data validation belongs to the app adapter. Inline-code ownership and
delimiter semantics also belong to the canonical AST. The table-separation
normalizer's fence-range check is intentionally pre-parse source repair and
must not be reused for inline token/action recognition.
