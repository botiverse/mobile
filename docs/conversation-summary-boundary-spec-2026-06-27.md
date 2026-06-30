# ConversationSummary Boundary Cleanup Spec

Date: 2026-06-27

Status: proposed cleanup plan. This is a boundary/spec document only; it does not
change product behavior.

## Decision

`ConversationSummary` should remain for now, but only as a conversation
directory/list index. It should not be a UI state snapshot or a profile/status
source of truth.

Allowed responsibilities:

- Conversation identity: `threadId`, `channelId`, `kind`, `channelType`, and
  `thread` anchor identity.
- Directory/list row summary: unread/mention counts, first unread or last
  message anchors, last activity label, draft flag, joined/archived flags, member
  count, and permissions.
- Resolver keys: for example `presenceMemberId`, as a key into the member/profile
  store, not as status or avatar data.
- Joint-channel directory metadata while the directory store has no richer
  typed joint entry.

Disallowed responsibilities:

- Avatar/profile/status fields such as avatar URL, gravatar hash, `presence`, or
  `agentStatus`.
- Full message/thread/task/file/member/composer state.
- Route/pageData fallback display metadata.
- Fields whose only purpose is to patch Thread UI after launch.

## Current Shape

The current model still mixes several layers:

- Directory identity and row summary live in
  `compose/shared/src/commonMain/kotlin/build/raft/app/model/SlockModels.kt`.
- Home API parsing creates channel/DM summaries in
  `compose/shared/src/commonMain/kotlin/build/raft/app/data/HomeParser.kt`.
- Directory persistence stores a subset in
  `SharedConversationDirectoryStore` and SQLDelight `ChannelEntity`.
- Home row rendering already projects `ConversationSummary` into
  `SharedHomeConversationRowSeed`, then resolves directory projection data.
- Thread launch still uses summaries in `SharedThreadStore`,
  `SharedThreadHeaderContextStore`, and route identity helpers.
- Inbox/Search create summaries as a compatibility bridge for opening
  conversations.

That means the next cleanup should not delete `ConversationSummary` wholesale.
It should shrink the fields and force consumers to use narrower DTOs.

## Field-Level Plan

### Keep, But Rename/Move Later

These are still valid for `ConversationSummary` as a list/directory index:

- `threadId`, `channelId`, `kind`, `channelType`
- `thread.parentChannelId`, `thread.parentMessageId`,
  `thread.streamChannelId`
- `name`, `subtitle` as directory title/description only
- `unreadCount`, `unreadCountKnown`, `mentionCount`, `mentionCountKnown`
- `lastMessageId`, `firstUnreadMessageId`, `lastMessagePreview`,
  `lastMessageAt`, `lastMessageSenderName`
- `presenceMemberId` as a resolver key only
- `hasDraft`, `hasDraftKnown`
- `joined`, `archived`, `memberCount`, `permissions`
- `jointChannelId`, `jointRole`, `jointPeerServerId`,
  `jointPeerServerName`, `jointPeerServerSlug`, `jointPeerStatus`

Longer-term name: `ConversationListSummary` or `ConversationDirectoryEntry`.

### Clean Next

These fields should move out of `ConversationSummary` first:

- `originMessagePreview`
- `originMessageSenderId`
- `originMessageSenderKind`
- `originMessageExpected`

Reason: they are thread-origin fallback data. They are currently produced by
Inbox/Search compatibility conversion and read by `SharedThreadHeaderContextStore`.
They are not directory/list identity. They should become either:

- `ThreadLaunchOriginFallback`, scoped to Inbox/Search open requests, or
- store-derived data from the thread/message store after validating
  `parentChannelId + parentMessageId`.

Target end state: Thread launch receives identity only; origin preview is a
candidate snapshot and cannot live in the general conversation directory model.

### Keep Temporarily, Then Split

These fields are valid row summary but should eventually move to a dedicated row
seed/projection type:

- `lastMessagePreview`
- `lastMessageSenderName`
- `lastMessageAt`
- `firstUnreadMessageId`
- `lastMessageId`
- `unreadCount`, `mentionCount`
- `hasDraft`

Reason: Home/Inbox/Search list rows need them, but Thread route/detail should not
consume them. Moving them into `ConversationRowActivitySummary` would make that
boundary explicit.

### Keep as Resolver Keys Only

`presenceMemberId` should stay only as a resolver key for DMs. It must not grow
back into denormalized profile fields. Home rows should resolve avatar,
presence, and agent status through member/profile SSOT:

- `SharedServerMembersStore`
- member/profile stores
- directory projection fields only when they are identity/label seeds

### Guard Against Reintroduction

Do not add these fields back to `ConversationSummary`:

- `avatarUrl`
- `avatar`
- `gravatarUrl`
- `gravatarHash`
- `presence`
- `agentStatus`
- full `MemberProfile`
- task/file/message/composer payloads

If a caller needs any of those, add a selector/resolver at the consuming store or
row projector, not a new summary field.

## Proposed Phases

### Phase 1: Stop New UI State in ConversationSummary

- Add serializer/descriptor or constructor guard tests for banned field names.
- Remove stale Android/mock/test constructors that pass banned fields.
- Keep the rule documented in code near `ConversationSummary`.

Validation:

- Static test for banned fields in `ConversationSummary`.
- Android/shared compile.

### Phase 2: Move Origin Fallback Out

- Introduce a narrow `ThreadLaunchOriginFallback` or equivalent Inbox/Search open
  request field.
- Change `InboxItem.toConversationSummary()` to stop populating origin preview
  fields on the summary.
- Change `SharedThreadHeaderContextStore` to read origin fallback from validated
  launch/context candidate or from store data.
- Remove `originMessagePreview`, `originMessageSenderId`,
  `originMessageSenderKind`, and `originMessageExpected` from
  `ConversationSummary`.

Validation:

- Inbox/Search/Saved/Activity open-thread cases still show the correct origin
  after store load.
- Wrong-thread origin fallback is discarded by
  `parentChannelId + parentMessageId`.

### Phase 3: Split Directory Entry From Row Activity

- Create a typed row summary, for example `ConversationRowActivitySummary`.
- Move unread/mention/last-message/draft fields into that row summary.
- Let Home/Inbox/Search list projections consume the row summary.
- Keep directory identity separate: channel/thread ids, kind, title, subtitle,
  joined/archived, permissions, joint metadata, resolver key.

Validation:

- Home sorting/badges/previews remain stable.
- Thread route/detail no longer accepts row preview fields as fallback display
  state.

### Phase 4: Rename the Remaining Type

After Phase 2/3, rename the remaining model to one of:

- `ConversationDirectoryEntry`
- `ConversationListSummary`
- `ConversationIndexEntry`

Preferred name: `ConversationDirectoryEntry`, if the type is owned by the
directory/cache layer. Use `ConversationListSummary` if Home/Inbox list activity
summary remains bundled.

## Non-Goals

- Do not remove `ConversationSummary` in one pass.
- Do not couple this with Android legacy `ChannelsUiState` cleanup.
- Do not move Home row performance work into Thread route cleanup.
- Do not change route/pageData payloads to pass more state while migrating.

## Risk

The dangerous fields are not the row summary fields themselves; it is their use
as fallback display state in Thread/detail screens. Cleanups should focus first
on consumers that cross from directory/list into route/detail:

- `ThreadRouteIdentity`
- `ThreadSurfaceContract`
- `SharedThreadStore`
- `SharedThreadHeaderContextStore`
- Inbox/Search/Saved open request bridges

Directory and Home row consumers can keep row summaries until dedicated row
projection types are introduced.
