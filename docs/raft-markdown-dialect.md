# Raft Markdown dialect

Date: 2026-07-25 (math: 2026-10-22)
Owner: KMP / Kuikly shared
Status: Implemented

## Purpose

Raft messages use GFM plus product references:

- `@member`
- `#channel`
- `#channel:threadShortId`
- `dm:@peer:threadShortId`
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
  -> RaftGFMFlavourDescriptor lexer emits GFM + Raft ref tokens
  -> one JetBrains Markdown parse / one cached AST result
  -> canonical inline syntax snapshot (Raft refs + inline-code spans)
       RaftMarkdownDialect collects ref nodes outside Markdown-owned ancestry
  -> app validation (known task/channel/member)
  -> MarkdownInlineTokenPlan
  -> RaftRichTextDocument (tokens + chrome + actions)
  -> Kuikly Markdown renderer
```

The planner and renderer consume the same cached parse result produced by
`RaftGFMFlavourDescriptor`. A planner cache miss publishes that canonical
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
3. math (`$…$`, `$$…$$`, `$$` blocks; see "Math" below);
4. images and Markdown links (inline, short-reference, full-reference);
5. link definitions, autolinks, and inline/block HTML;
6. Raft inline references;
7. ordinary text.

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
`_`, `.`, or `-`. Dots are legal inside a handle, but trailing `.` characters
belong to surrounding sentence punctuation and are not part of the mention.

### Direct-message thread

Case-insensitive `dm:@`, followed by an ASCII peer name using `A-Z`, `a-z`,
`0-9`, `_`, `.`, or `-`, then `:` and a 6-8 character hexadecimal short id. The complete
`dm:@peer:shortId` is one typed reference and resolves only against a direct
message conversation. A same-name channel cannot satisfy it, and ambiguous or
unknown DM peers fail closed. Bare `@peer:shortId` intentionally remains an
ordinary member mention followed by literal text; it does not gain message or
thread navigation semantics.

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

## Math

Task #775. The rules are the web's, botiverse/slock #10813 (pinned head
`cdfb107ecda6e49cf3c7eecfb7857f9eff98e3a2`), `raftMath()` in
`packages/shared/src/markdownMath.ts`: Obsidian's (Pandoc's) dollar rules on
micromark-extension-math.

- `$$…$$` is math wherever it appears. On its own lines it is a block
  (`BLOCK_MATH`), otherwise inline math (`INLINE_MATH`).
- `$…$` is inline math only when the opening `$` is not followed by
  whitespace and the closing `$` is not preceded by whitespace and not
  followed by an ASCII digit. So `$x^2$` is math, while `$5 到 $10`, `$5,$10`
  and `$5-$10` stay text.
- Single-dollar math stays on one line. A dollar run of another length inside
  a formula is part of it, and a closing candidate that fails the rules does
  not end the search.
- `\$` is a literal dollar.
- A block opens with two or more `$` (indented at most three spaces, then any
  text without a `$`), closes with at least as many `$` and nothing else on
  the line, may interrupt a paragraph, and runs to the end of its container
  when unclosed. Its lines are TeX, never Markdown. `$$x$$` alone on a line
  is not a fence: it is inline math in a paragraph.

These are parser rules in the vendored JetBrains parser, at the same layer as
the CJK-friendly emphasis rule: `MathParser` (inline; the first sequential
parser, so a formula owns any emphasis, link or autolink syntax inside it) and
`MathFenceProvider` / `MathFenceMarkerBlock` (blocks). There is no regex or
source pre-pass. The formula text comes from the AST
(`raftInlineMathSource` / `raftBlockMathSource`, which give mdast-util-math's
`value`: one space of padding trimmed from each side of inline math, block
fence indent and container markers removed).

Math is Markdown-owned, like code: an `@name` or `#12` inside a formula is TeX,
not a reference, so `RaftMarkdownDialect` collects no ref under `INLINE_MATH`
or `BLOCK_MATH` (web: `markdownStructure` treats `math`/`inlineMath` as code
when the server decides who a message notifies). Mobile has no other place
that derives mentions from Markdown; notifications use the server's
`mentions`.

Known difference: code spans are shielded at the lexer, so a backtick pair
always wins over math. On the web whichever opens first wins, so
``$a `b$` c`` is math there and a code span here.

Known difference: inside a formula, `\$` is an escaped dollar here and the
formula goes on, so `$a\$b$` is the formula `a\$b`. On the web micromark does
not read escapes inside math, so the formula ends at that `$` (`a\`) and `b$`
is text. Mobile keeps its reading: `\$` is how TeX writes a dollar.

Rendering (`RaftMarkdownMath.kt`): KaTeX layout with the KaTeX 0.19.0 fonts,
1.21× the surrounding text as KaTeX's CSS, in the text colour; inline math on
the text baseline, blocks centred and scrolling sideways. A formula the web's
KaTeX settings reject (`throwOnError: false`, `trust: false`: unknown
commands, `\href`, `\url`, `\includegraphics`, `\html*`) shows its source in
KaTeX's errorColor.

## Typed rich-text contract

Recognized and context-valid candidates become `RaftRef` tokens in
`RaftRichTextDocument`. Each action has one source range and one click
identity. A Markdown-owned range must contain zero nested `RaftRef` tokens and
zero nested Raft actions.

Presentation labels may be resolved (`@id` -> `@display-name`), but action
identity remains the original source tag. Active/highlighted chrome is derived
after validation and never changes parsing.

## Conformance tests

`MarkdownMathTest` is the web's `markdownMath.test.ts` ported case for case,
plus the block shapes; `MarkdownMathRenderTest` owns the placeholder, price
and error behaviour; `ThreadMathMessageScreenshotTest` holds the goldens.

`RaftMarkdownDialectTest` owns syntax/precedence coverage:

- all five Raft reference shapes in ordinary text;
- production-shaped GitHub link label and URL fragment;
- image, inline code, fenced code, autolink, and HTML ownership;
- escaping and word/CJK adjacency.

`RaftMarkdownTest` owns renderer/action behavior, including standalone refs
remaining clickable and the production link remaining one external link.

`RaftRichTextContractTest` owns IR serialization and asserts that a Markdown
link produces no nested `RaftRef` action.

When adding syntax, update all three layers. A screenshot alone is not a parser
contract, and a parser-only test is not proof that click/chrome behavior stayed
correct.

## Migration rule

The removed functions `threadInlineTagRanges`, `scanThreadHashRange`, the
Markdown protection-range helpers, `ensureThreadMarkdownTableSeparation`, and
`threadMarkdownFenceRanges` must not be reintroduced. Product-reference grammar
belongs in `RaftMarkdownDialect`; Markdown nesting belongs to the GFM AST; data
validation belongs to the app adapter. Inline-code ownership and delimiter
semantics also belong to the canonical AST. Raft's chat-friendly tight-table
continuation is a block-parser rule in `RaftGFMFlavourDescriptor`, so parsing
never rewrites the source and all AST offsets remain bound to the original body.
