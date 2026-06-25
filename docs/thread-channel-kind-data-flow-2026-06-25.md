# Thread channel-kind data flow

## Why this exists

The `raft-mobile` joint-channel icon regression showed that the Thread page currently has too many places that can describe the same conversation chrome. The bug was not that Home opened the wrong route:

```text
home_open_conversation threadId=1fe17699... kind=Joint source=home
home_open_thread_launch ... sourceThreadId=1fe17699... surface=channel
thread_refresh_result threadId=1fe17699... surface=channel
```

The problem was a later refresh path where a generic repository conversation could overwrite the launch route's known concrete channel kind. This document describes the current intended data flow and the precedence rules to keep it stable.

## Terms

- `surfaceKind`: page shape. Values are `channel`, `dm`, and `thread`.
- `channelKind`: channel subtype used only when `surfaceKind == channel`. Values currently include `channel`, `private`, and `joint`.
- `parentSurfaceKind`: page shape of a reply thread's parent conversation.
- `parentChannelKind`: channel subtype of a reply thread's parent conversation, only meaningful when `parentSurfaceKind == channel`.
- route identity: IDs and route shape needed to select data. It may carry `channelKind` because the subtype affects the route chrome and header affordances, but it must not carry messages, members, avatar snapshots, or other product state.
- display projection: title, subtitle, avatar, gravatar, presence, members count, joined state, etc. These must come from stores/repositories, not from route payload.

## Current Source Layers

### 1. Home / Activity / Search / Saved entry

Home rows start from `ConversationSummary` or `InboxItem`.

For top-level channels:

- `ConversationSummary.kind == Joint` means `surfaceKind=channel`, `channelKind=joint`.
- `ConversationSummary.kind == Channel` means `surfaceKind=channel`, and `channelType` may refine `channelKind`.
- Direct messages use `surfaceKind=dm`; `channelKind` is irrelevant.

For reply-thread rows:

- `InboxItem.parentChannelKind` must be converted into `ThreadConversation.parentChannelKind`.
- `ThreadConversation.parentSurfaceKind` describes whether the parent page is `channel` or `dm`.
- `ThreadConversation.parentChannelKind` preserves `joint` / `private` when the parent is a channel.

The entry layer is allowed to provide concrete route identity because it is often the only layer with the user's exact navigation intent at first render.

### 2. Wire launch payload

`WireThreadLaunchPayload` is the standalone page boundary. It should contain only stable launch identity:

- `surfaceKind`
- `channelKind`
- `threadId` / `sourceThreadId` / `streamChannelId`
- `parentChannelId`
- `parentMessageId`
- `parentSurfaceKind`
- `parentChannelKind`
- `originParentMessageId`
- focus / tab / time host context
- composer seed identity

It must not contain message lists, title, avatar, members, files, tasks, or other display state.

### 3. Initial screen payload

`parseThreadScreenPayload()` converts wire launch identity into `KuiklyThreadScreenPayload`.

This payload may contain loading placeholders, but its route fields remain the active route identity until repository/store data proves a more specific value. Important examples:

- A Home `Joint` route should start as `surfaceKind=channel`, `channelKind=joint`.
- A reply thread from a joint parent should start as `surfaceKind=thread`, `parentSurfaceKind=channel`, `parentChannelKind=joint`.
- A DM reply thread should start as `surfaceKind=thread`, `parentSurfaceKind=dm`, and no parent channel subtype is needed.

### 4. Thread repository snapshot

Repository results and cache readbacks provide durable product state:

- messages
- tasks
- parent origin message
- joined / permissions
- members count
- conversation display metadata where available

Repository conversation identity may be complete or sparse. A full channel summary can refine a generic route, but a sparse generic summary must not downgrade a concrete route.

Precedence for top-level channel kind:

```text
if repository kind is concrete (joint/private):
    use repository kind
else if repository kind is generic channel and current route kind is concrete:
    keep current route kind
else:
    use repository kind, then route fallback
```

This is the rule added by `ff5f93e0`.

### 5. Conversation directory projection

`SharedConversationDirectoryStore` is a display projection cache. It can improve title, subtitle, avatar, joined state, and channel kind, but only if it matches the active route identity.

Projection acceptance rules:

- Surface must match.
- For `surfaceKind != channel`, surface match is enough.
- For channel pages, a generic `channel` payload can accept a more specific projection.
- A concrete `joint` / `private` payload must reject a generic `channel` projection.

This prevents the header from flashing from branch/lock back to hash when a weaker projection arrives after launch.

### 6. Header display model

The header should be a pure projection of the selected payload plus accepted directory entry:

- Channel icon uses `channelKind.threadChannelHeaderIcon()`.
- DM header uses peer/avatar display data.
- Reply thread header does not use channel icon; parent-open actions use `parentSurfaceKind` / `parentChannelKind`.

Header rendering should not infer channel kind from title text, route name, or fallback display labels.

## What Went Wrong In #403

The correct Home launch already knew the route was a joint channel:

```text
kind=Joint
surface=channel
threadId=1fe17699...
```

After refresh, `ThreadSnapshot.toKuiklyThreadPayload()` preferred:

```kotlin
conversation.resolveThreadRouteIdentity().channelKind
```

over the current route payload. When the repository conversation came back as generic `Channel/channel`, it replaced `joint`, so the header icon changed from `GitBranch` to `Hash`.

The corrected rule keeps concrete route identity unless the repository provides an equal or more concrete value.

## Current Complexity

The current flow is workable but heavy because the same kind fields are present in several objects:

- `ConversationSummary.kind`
- `ConversationSummary.channelType`
- `ThreadConversation.parentSurfaceKind`
- `ThreadConversation.parentChannelKind`
- `WireThreadLaunchPayload.surfaceKind`
- `WireThreadLaunchPayload.channelKind`
- `KuiklyThreadScreenPayload.surfaceKind`
- `KuiklyThreadScreenPayload.channelKind`
- `SharedConversationDirectoryProjection.surfaceKind`
- `SharedConversationDirectoryProjection.channelKind`

This is why precedence bugs are easy to introduce. The route and repository layers both know a piece of identity, and the weaker value can arrive later.

## Recommended Cleanup Direction

Introduce a typed route identity object as the only place where route shape and channel subtype are merged:

```kotlin
data class ThreadRouteIdentity(
    val surfaceKind: String,
    val channelKind: String?,
    val sourceThreadId: String?,
    val streamChannelId: String?,
    val parentChannelId: String?,
    val parentMessageId: String?,
    val parentSurfaceKind: String?,
    val parentChannelKind: String?
)
```

Then require every conversion to go through one reducer:

```text
launch identity + repository identity + directory projection
    -> resolved route identity
    -> display payload
```

The reducer should own all precedence rules:

- concrete beats generic
- repository concrete can upgrade route generic
- repository generic cannot downgrade route concrete
- directory projections are accepted only after route match
- reply thread parent subtype is separate from reply thread surface

After that, remove direct ad hoc assignments to `payload.channelKind` outside the reducer.

## Practical Rules For Future Changes

- Do not treat `surfaceKind` as a substitute for `channelKind`.
- Do not treat `channelKind` as a substitute for `surfaceKind`.
- Do not let display projections change the active route unless they pass route identity matching.
- Do not let generic repository summaries erase concrete route identity.
- Keep reply thread's own surface as `thread`; store parent channel subtype in `parentChannelKind`.
- Add a regression any time a conversion crosses Home, Activity, Search, Saved, Thread, or cache boundaries.
