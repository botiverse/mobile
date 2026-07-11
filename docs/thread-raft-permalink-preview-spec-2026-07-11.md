# Thread Raft permalink chip and message preview spec

Date: 2026-07-11  
Status: Proposed  
Scope: shared/KMP Thread message rendering on Android, iOS, and OHOS  
Reference baselines: React `origin/staging@b48a2f7b4`; mobile `origin/main@e9a8a2cf9`

## 1. Outcome

When a user pastes a Raft message permalink into a message, mobile must provide two related affordances:

1. The URL inside the Markdown body renders as a first-class Raft message-reference chip instead of an ordinary blue external link.
2. The first eligible same-server bare permalink renders a quoted-message preview card below the body. The card loads canonical message context, shows useful source metadata, and opens the exact channel, DM, parent thread, or reply when tapped.

These are separate surfaces backed by one parser and one typed identity:

- every recognized Raft permalink anchor can become an inline chip;
- at most one eligible permalink per source message becomes a preview card;
- neither the chip nor the card passes target message content through route or Kuikly `pageData` payloads.

The msg chip is also the first new consumer of a generic Kuikly RichText inline box-span primitive. The renderer must understand box geometry/style, not Slock concepts such as message, channel, or task.

## 2. React contract to preserve

Current React behavior is defined by:

- `packages/shared/src/slockPermalinks.ts`;
- `packages/web/src/components/message/MessageItem.tsx`;
- `packages/web/src/components/message/ReferenceChip.tsx`;
- `packages/web/src/components/message/quotedMessagePermalink.ts`;
- `packages/web/src/components/message/QuotedMessagePermalinkPreview.tsx`;
- `packages/web/src/components/ui/cards/QuotedMessageCard.tsx`.

React currently:

- parses `/s/{serverSlug}/{channel|dm}/{channelId}` links with `msg=` and optional `thread={channelId}:{parentMessageId}`;
- accepts legacy Slock hosts plus the current configured app hostname;
- renders a recognized Markdown anchor as a link-icon reference chip labelled with `#channel`, `@peer`, or `linked message`, plus a small `msg` suffix;
- opens same-server links inside the app and cross-server links as external navigation;
- scans message source outside inline/fenced code, ignores named Markdown-link destinations, trims CJK/ASCII trailing punctuation, and previews only the first same-server bare permalink;
- loads `GET /messages/context/:messageId?channelId=:channelId`;
- shows loading, loaded, and unavailable states;
- shows channel/DM identity, Thread and Archived markers, author/avatar/subtitle, timestamp, a two-line content preview, and image/file/attachment count;
- degrades inaccessible or missing targets to a neutral `Message unavailable` card without leaking target data.

Mobile should preserve these user-visible semantics while using shared stores and existing mobile navigation contracts instead of copying React component-local fetching.

## 3. Current mobile state and gap

Mobile already has most of the lower-level pieces:

- `String.toSlockThreadWebLinkTarget()` recognizes `app.slock.ai`, `staging.slock.ai`, and `app.raft.build` channel/DM/thread links.
- `openMarkdownUrl` converts a recognized link to an identity-only loading payload and otherwise calls the host external URL bridge.
- `ConversationRepository.getMessageContext(messageId, channelId, priority)` already calls `GET messages/context/:id` and parses canonical target, history window, archived state, message domain models, and conversation detail.
- `SharedThreadStore` already uses message-context loading for focused navigation, parent context, and comment-host opens.
- `SlockMarkdownBody` already routes every Markdown URL through `onOpenUrl` and has a shared inline-token/chrome pipeline for mentions, channels, threads, tasks, and inline code.

The remaining product gaps on current `main` are:

- recognized Raft links still render with the generic Markdown link style rather than a Raft reference chip;
- Thread rows do not derive a preview candidate from message source;
- there is no current store-backed preview state/cache and no quoted-message card in the live Thread row;
- link parsing is duplicated and does not carry `serverSlug` in `SlockThreadWebLinkTarget`, so cross-server internal navigation cannot be decided from typed identity alone.

Historical local commit `400aa9bc7` implemented an earlier preview experiment but is not in current mobile `main` and must not be cherry-picked wholesale. It predates the current package names, route assembly, store ownership, Markdown rich-text/chrome pipeline, request-priority contract, and launch-payload cleanup. It is evidence for desired states only.

## 4. Canonical permalink identity

Introduce one commonMain parser and typed result, used by Markdown annotation, preview extraction, click handling, and navigation:

```kotlin
data class SlockMessagePermalinkTarget(
    val rawUrl: String,
    val serverSlug: String,
    val routeKind: ConversationKind, // Channel or DirectMessage
    val channelId: ChannelId,
    val messageId: MessageId,
    val threadParentMessageId: MessageId? = null,
)
```

Parser contract:

- schemes: `https` and `http`; production-generated links remain `https`;
- hosts: legacy `app.slock.ai`, `staging.slock.ai`, production `app.raft.build`, and the normalized host from the selected API environment's `webUrl`;
- canonical path: exactly `/s/{serverSlug}/{channel|dm}/{channelId}`;
- `thread`, when present, must decode to `{same channelId}:{nonblank parentMessageId}`;
- target message is `msg` when present, otherwise the thread parent for a thread-only URL;
- a message-reference target without either `msg` or a valid thread parent is not eligible for chip/preview behavior and remains an ordinary URL;
- malformed paths, unsupported hosts, mismatched thread channel ids, blank ids, and unsupported route kinds fail open as ordinary links;
- query decoding must support UTF-8 percent encoding and `+`, without decoding path separators into new route segments.

`toSlockThreadWebLinkTarget()` should become a compatibility wrapper over this parser and then be removed once all callers consume the typed target.

## 5. Inline Raft message-reference chip

### 5.1 Recognition

Any Markdown anchor whose destination parses as `SlockMessagePermalinkTarget` renders as a Raft message-reference chip. This includes:

- a pasted bare URL auto-linked by the Markdown parser;
- `<https://...>` autolinks;
- `[label](https://...)` named Markdown links.

Code spans and fenced code remain literal and never become chips.

### 5.2 Label and chrome

The chip uses the existing Slock reference-chip height, black 1dp frame, font weight, collision/padding, and native rich-text chrome path. It must not introduce a separately measured Compose child that changes line wrapping.

This does **not** mean implementing three independent feature UIs. The parser, token plan, label resolution, actions, preview store, preview projection, quoted-card Compose UI, and navigation intent all live once in commonMain. The quoted preview card itself remains one shared Compose component.

Label resolution order:

1. current loaded conversation directory row matching `(serverId, channelId)`;
2. current Thread/conversation snapshot detail;
3. deterministic fallback `linked message`.

Visible label:

- channel: link icon + `#channelName` + trailing `msg`;
- DM: link icon + `@peerDisplayName` + trailing `msg`;
- unresolved/cross-server: link icon + `linked message` + trailing `msg`.

The chip's accessibility label must include `Raft message link`, the resolved label, and `thread reply` when `threadParentMessageId != null`.

#### Generic inline box decoration on the existing span model

Kuikly already has the span abstraction this feature needs: `RichTextView` owns `TextSpan`/`ImageSpan` children through `ISpan`, and the Compose `BasicText` path lowers `AnnotatedString` ranges into those existing `TextSpan` nodes. Span click and long-press also already use that carrier. Do not introduce a parallel span hierarchy or a Slock-specific message span.

Before adding msg-specific chrome, extend the existing `TextSpan` plus Compose span-lowering bridge with a semantic-agnostic inline box style value, tentatively:

```kotlin
data class InlineBoxSpanStyle(
    val backgroundColor: Color? = null,
    val borderColor: Color? = null,
    val borderWidth: Dp = 0.dp,
    val paddingStart: Dp = 0.dp,
    val paddingEnd: Dp = 0.dp,
    val paddingTop: Dp = 0.dp,
    val paddingBottom: Dp = 0.dp,
    val marginStart: Dp = 0.dp,
    val marginEnd: Dp = 0.dp,
    val cornerRadius: Dp = 0.dp,
)
```

Exact public names/types belong to the Kuikly fork API review, but the behavior contract is fixed:

- `InlineBoxSpanStyle` is a decoration/style value attached to the existing `TextSpan`, not a replacement for `TextSpan`/`ISpan`;
- Compose `AnnotatedString`/`SpanStyle` lowering attaches the same box decoration to the generated `TextSpan`, rather than creating a second rendering path;

- layout reserves padding, border, and margins before line breaking; paint-only expansion is forbidden;
- wrapped spans create one box fragment per visual line;
- side borders/padding appear only at true semantic span edges, not at internal wrap boundaries;
- fill/border use final laid-out fragment geometry, avoiding glyph collision and black-block brush-state bugs;
- semantic text/copy excludes collision sentinels and layout-only break helpers;
- hit testing inside background/border/padding resolves to the original span action;
- spans without a box style retain current text behavior and cost;
- renderer input is style geometry plus action identity, never Slock semantic kinds such as `MessagePermalink`, `Channel`, or `Task`.

Current `slockRefChrome` is a specialized predecessor of this decoration: its shared style token exposes `chipFill` and `horizontalChromeSp`, while some border/padding behavior remains renderer-hardcoded. Add a compatibility mapper from current Slock chrome tokens to the new `TextSpan` box style; do not break existing channel/thread/task/mention/inline-code visuals. The msg chip uses the generic decoration directly as the first new consumer. Existing chips may migrate incrementally after parity tests, rather than forcing a risky all-chip rewrite in this feature.

The generic primitive lands in KuiklyUI `staging2` first, followed by the mobile gitlink/artifact update. Platform render backends may require internal adapter code because their text APIs differ, but they all consume one Kuikly API/serialized style contract; there are no platform-specific Slock msg-chip components. Add a truthful capability such as `inlineBoxSpanChrome`, and promote it per platform only after that renderer passes layout, draw, copy, and hit-test gates. The reference baseline's existing specialized capability is Android/iOS `slockRefChrome=true`, OHOS `slockRefChrome=false`; it must not be reused to overclaim the new generic primitive.

### 5.3 Click behavior

- Same selected server slug: dispatch typed internal-open intent using the exact parsed identity.
- Different server slug: open the absolute URL externally in phase 1. A future cross-server switch flow may replace this only after it can select/authenticate the target server before consuming the intent.
- Invalid/unrecognized URL: existing external URL behavior.

No click path may infer thread identity from `channelId` alone.

## 6. Preview candidate extraction

Preview extraction operates on original message Markdown source, not rendered text.

To match React phase 1:

- scan URLs outside fenced code and one-/two-backtick inline code;
- stop bare URLs at whitespace, angle/quote delimiters, and CJK URL-boundary punctuation;
- trim trailing `),.!?;:` and Chinese equivalents;
- ignore a URL used only as the destination of a named Markdown link (`[label](url)`);
- require a parsed target whose `serverSlug` equals the currently selected server slug;
- return the first eligible URL only;
- return no candidate for system messages, failed local sends, or a message body currently replaced by a translation placeholder;
- the preview candidate must remain based on the canonical original body even when long-message collapse or translated display text changes.

The named-link exclusion is intentional: named links still receive the inline chip, but do not add a second large card. This matches React and avoids surprising cards for prose citations.

## 7. Store and repository boundary

### 7.1 Ownership

Add an app-scope `SharedMessagePermalinkPreviewStore`, or an equivalent app-scope slice owned by the existing Thread store registry. Do not keep row-local request maps inside `ThreadPage`.

Cache key:

```text
serverId + routeKind + channelId + threadParentMessageId? + messageId
```

State:

```kotlin
sealed interface MessagePermalinkPreviewState {
    data object Idle
    data object Loading
    data class Content(val preview: MessagePermalinkPreview)
    data class Unavailable(val reason: UnavailableReason)
}
```

The UI-facing `MessagePermalinkPreview` is a projection derived from canonical repository data. It may contain display fields needed by the card, but it is not a route payload and must not be serialized into `KuiklyThreadScreenPayload`/`pageData`.

### 7.2 Loading

- visible rows dispatch `EnsurePreview(target, priority = Visible)`;
- one in-flight request per cache key; duplicate rows join the same Deferred;
- request through `ConversationRepository.getMessageContext(messageId, channelId, priority)` so joint-channel projection and short-id resolution use the existing server contract;
- use the response's canonical `targetMessageId`/canonical target to select the target message rather than assuming the requested id is the final full id;
- use the returned conversation detail and archived state for source metadata;
- never write preview display data into durable Thread message tables merely to render the card;
- retain a bounded in-memory LRU (suggested 100 targets) scoped by authenticated server; clear the server partition on matched auth loss/server removal;
- retry only on explicit user retry or a later visibility ensure after a retryable error backoff; do not refetch on every recomposition.

### 7.3 Unavailable semantics

Map all of these to the same neutral UI, while preserving diagnostic reason internally:

- 403/404 or `DataResult.NoPermission`;
- empty context/target not found;
- target channel no longer visible;
- contract mismatch;
- non-retryable parse error.

Network/offline errors may use the neutral unavailable card in phase 1. A later retry affordance can distinguish transient failure, but must not expose permission state or target metadata.

## 8. Preview card UI

Render the card below the Markdown body and above attachments/footer metadata, at the same message content width.

### Loading

- full-width black-framed white shell;
- compact header skeleton and two content skeleton lines;
- fixed/bounded height to avoid large timeline reflow.

### Loaded

- source: `#channel` or `@peer`;
- compact author avatar, author display name, optional role/description;
- timestamp using the current locale formatter;
- `Thread` marker when the target is a parent/reply-thread link;
- `Archived` tag when server context reports archived;
- message body rendered as safe inline Markdown, clamped to two lines; no nested cards, code blocks, tables, WebViews, or recursive permalink preview;
- attachment summary: `1 image`, `1 file`, `N images`, or `N attachments`;
- whole card is clickable only in `Content` state.

### Unavailable

- muted black-framed surface;
- localized `Message unavailable` only;
- no author/channel/time/content leakage;
- non-clickable in phase 1. This is stricter than current React's unavailable click and follows the existing mobile proof-gated navigation rule.

All user-facing copy and attachment plurals must use moko resources; do not land English literals in `ThreadPage.kt`.

## 9. Navigation and launch contract

Card/chip navigation passes only:

- selected target server identity already matched by slug;
- route kind;
- channel id;
- message id;
- optional thread parent message id.

The destination store loads canonical content via the existing open assembly:

- top-level channel/DM message: identity-only focused-message open;
- thread parent or reply: reply-thread identity with `parentChannelId`, `parentMessageId`, and `focusedMessageId`;
- server-provided canonical target wins over the URL's candidate identity after context resolution.

Do not pass preview title, author, body, attachment labels, archived state, `ConversationSummary`, or fetched `ThreadSnapshot` through route/pageData. A warm preview cache is only a candidate snapshot: if its key matches the destination identity it may reduce placeholder time, but it cannot override the destination store's canonical state.

## 10. Privacy, security, and correctness

- Host allowlisting is environment-aware and exact; suffix matching such as `*.raft.build` is forbidden.
- Never fetch arbitrary external URLs for unfurling.
- The preview request uses authenticated app networking and the caller's selected server scope.
- Same-server slug check happens before network load.
- Inaccessible targets collapse to neutral unavailable UI.
- Diagnostics record only ids shortened/redacted under existing policy, result category, duration, cache hit, and route kind; never log target message body or raw auth responses.
- A permalink pasted into code remains literal and generates no network request.
- A source message with several links generates at most one preview request/card.

## 11. Delivery phases

### Phase A0 — Kuikly generic inline box span

- add `InlineBoxSpanStyle` (final API name subject to fork review) as a style value on existing `TextSpan`; do not add a parallel span class/hierarchy;
- carry background, border, four-direction padding, edge margin, and optional radius through both the existing RichText `TextSpan` contract and Compose `AnnotatedString`/`SpanStyle` lowering bridge;
- implement final-layout fragment measurement/draw/hit-test/copy behavior in each renderer backend behind the same API;
- map existing `slockRefChrome` style tokens through the primitive without visual changes;
- land on KuiklyUI `staging2`, publish the fork artifacts, then update the mobile gitlink/HAR.

### Phase A — parser and inline msg chip

- canonical typed parser with environment host input;
- Markdown link annotation/action for message permalinks;
- msg chip style expressed only as the existing `TextSpan` carrying `InlineBoxSpanStyle` + text/action tokens;
- click routing;
- parser/chip/host/cross-server tests.

### Phase B — store-backed preview

- candidate extraction;
- app-scope preview store with singleflight/bounded cache;
- repository projection from message context;
- loading/content/unavailable card;
- identity-only card navigation.

### Phase C — visual and device acceptance

- deterministic visual fixtures for channel, DM, thread reply, archived, loading, unavailable, attachment counts, long/CJK-adjacent URLs, and narrow wrapping;
- one shared Compose quoted-card implementation; msg chip platform diffs are restricted to the generic Kuikly inline-box renderer backend/capability layer;
- Android connected-device tap/open gate;
- OHOS and iOS screenshot/tap gates using the real rich-text renderer;
- no parity promotion until renderer capability markers and device evidence agree.

Phase A0, A, and B may be separate PRs. Phase A depends on the published A0 primitive. Phase B must reuse Phase A's parser; no second regex/parser is allowed.

## 12. Required tests

### Generic inline box span

- zero-style span preserves existing measurement and draw output;
- padding/border/margin affect wrapping before layout, including narrow-line cases;
- single-line box geometry and wrapped multi-fragment geometry;
- no internal side border/padding at wrap boundaries;
- background and border do not cover glyphs;
- hit tests in text, padding, and border map to the same action;
- copied text contains only semantic text;
- nested/adjacent spans do not merge styles or steal actions;
- existing channel/thread/task/mention/inline-code fixtures remain pixel-stable through the compatibility mapper;
- capability remains false/unknown on a platform until its actual renderer passes these gates.

### Parser/extraction

- production `app.raft.build`, legacy hosts, configured environment host;
- channel, DM, thread with focused reply, thread-only parent;
- mismatched thread channel, missing target id, invalid route, untrusted host;
- short/full message id passes through for server resolution;
- CJK punctuation and ASCII trailing punctuation;
- fenced/inline code excluded;
- named Markdown link: chip yes, card no;
- multiple bare links: one card for first eligible same-server link;
- cross-server link: chip yes, preview no, external open.

### Store/repository

- `channelId` query is sent with message-context request;
- canonical target id selects the correct message;
- joint-channel local projection resolves;
- concurrent duplicate ensures produce one request;
- server partition/cache isolation;
- auth loss clears matching partition;
- 403/404/empty/contract mismatch become unavailable without data leakage;
- cancellation does not leave a permanent Loading entry;
- retryable failure can retry after backoff/explicit retry.

### UI/navigation

- localized loading/content/unavailable states;
- channel/DM/Thread/Archived markers;
- two-line clamp and attachment plural labels;
- unavailable is not clickable;
- loaded card opens exact channel/DM/thread identity;
- preview data absent from route/pageData serializer descriptors;
- row reuse does not show another message's preview;
- translation and long-message toggles do not change preview identity;
- chip/card accessibility semantics.

## 13. Non-goals

- generic OpenGraph/external website unfurling;
- fetching unauthenticated HTML from `raft.build` marketing/docs pages;
- cross-server automatic switching/authentication;
- multiple preview cards in one message;
- recursive quoted-card rendering;
- changing message send payloads or server-side stored content;
- reintroducing full product content into Thread launch payloads.

## 14. Acceptance checklist

- Kuikly exposes one generic inline box-span contract; renderer code contains no msg/channel/task semantic branches for the new primitive.
- Existing Slock reference and inline-code visuals remain unchanged after compatibility mapping.
- Pasting a same-server Raft channel/DM/thread message URL shows an inline Raft chip and one preview card.
- The card content matches the authorized `/messages/context` target and opens the exact message/thread.
- Code and named-link exclusions match the stated contract.
- Cross-server/untrusted/external links do not trigger authenticated preview fetches.
- Permission denial leaks no target metadata.
- Recomposition, duplicate visible rows, and row reuse do not duplicate requests or swap cards.
- Android, iOS, and OHOS render/tap evidence is attached before the feature is declared cross-platform complete.
