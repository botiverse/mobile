# Slock DTO Sweep — Cross-Commit Tracking

Goal: every API request/response contract goes through a strict
`@Serializable` camelCase DTO living in `compose/shared/.../data/`. Android is
only allowed to provide platform capabilities such as auth storage, token
refresh, and the Android Ktor/OkHttp engine wiring.
Request bodies are part of the same contract: no `mapOf`/ad hoc `JsonObject`
body construction for API calls.

Canonical contract is **camelCase only** — verified against the upstream
TypeScript source at `third_party/slock-source/` (submodule of
`github.com/botiverse/slock`). Use `packages/server/` for HTTP/Socket payload
shapes and `packages/shared/` for shared types. No snake_case aliases, no
`firstObject(... metadata, meta, payload ...)` style field guessing.

## Status

### Done
- ThreadPage.kt parsers → shared (`ThreadMessagesParser.kt`).
- Unread/Summary parsers → shared (`UnreadParser.kt`).
- Attachments upload + channel files → shared (`AttachmentsParser.kt`).
- Workspace file tree + preview → shared (`WorkspaceFilesParser.kt`); deleted
  `toWorkspaceFileNodes`, `toWorkspaceFileNode`, `toWorkspaceFilePreview`,
  `workspaceFileKind`, `isImagePath` from `RealSlockRepositories.kt`.
- Auth repos: strict `parseAuthUser` / `parseAuthServers`, removed
  `objectValue`/`arrayValue` helpers.
- `SocketIoRealtimeClient.serverIdOrNull` collapsed to strict `serverId` only.
- Settings parsing audit (12 helpers cataloged).
- Snake_case aliases fully stripped from `RealSlockRepositories.kt` (commits
  `53c2f48`, `9dbdd24`, `b812677`). Remaining snake_case strings in the file
  are intentionally preserved as enum values or internal source tags
  (`in_review`, `in_progress`, `to_do`, `tool_start`, `thread_*` events,
  `home_*`, `server_*`, `channel_*`, `messages_*`, `tasks_*`, `read_*`,
  `not_connected`, `compaction_started/finished`, `dm_open_*`, etc.).
- Helpers covered so far: `toMessage`, member/avatar/agent/computer/workspace
  helpers, `toAttachment`, `systemEventType`, thread pagination + cursors,
  `toTaskSummary`/`taskDisplayTitle`/`taskTextValue`, conversation `toDomain`
  agent status + drafts + parent/thread channel ids + avatars + membership,
  `toInboxItem` + kind/timestamps/hasMention, `replyCountFromAliases`,
  joined/archived channel ids, `messageIdFrom`/`messageTimestampFrom`, skill
  helpers, agent activity + reminder + skill detail, settings computer/machine
  identity, workspace path lookup, `threadActionThreadChannelId`.
- Test fixtures updated to camelCase: `thread_id`, `reply_count`×2,
  `avatar_url`×2, `latest_activity_at`×2, `has_more_before`.

- Snake_case aliases stripped from `RealSettingsRepository.kt`
  (`belongsToMachine` machine/computer/daemon `_id`, `toSettingsMemberProfile`
  gravatar aliases) and `NetworkResponseMapper.toAvatarWireValue`
  (`avatar_url`, `image_url`, `profile_image_url`, `picture_url`, `photo_url`,
  `icon_url`, `gravatar_hash`) — commit `462703d`.
- Settings account + machine-agent/member enrichment parsing moved to shared
  strict DTOs in `SettingsParser.kt`; deleted the app-side
  `toAccountSettingsSnapshot`, `toConnectedAccount`,
  `toSettingsComputerAgents`, `toSettingsMemberProfiles`,
  `objectValue`, connected-account-status, and role-label helpers.
- Thin attachment URL and thread action result parsing moved to shared
  (`parseSharedAttachmentOpenTarget`, `parseSharedThreadActionResult`).
- `NetworkResponseMapper.execute` now passes through the raw response body
  string. App repositories no longer receive `JsonElement`/`JsonObject`, so any
  endpoint that returns a bare array, such as `GET /api/agents`, must parse that
  array directly in a shared DTO parser.
- Settings server API typed parsing moved out of `SlockSettingsServerApi.kt`
  into shared strict DTO parsers for machines, machine workspaces, server
  admins, usage, billing subscription, onboarding, server profile update,
  invite creation, and delete.
- Channel/home actions and lists moved to shared strict parsers in
  `HomeParser.kt`: create channel, open DM, channel join/leave/archive/delete,
  bare channel arrays, and bare DM arrays.
- Member, channel-member, and agent detail surfaces moved to
  `MemberAgentParser.kt`: server members, `GET /api/agents` bare array,
  member profile, agent profile update, agent DMs, reminders, activity log,
  current-activity fallback, agent skills, channel members, and member
  add/remove action results.
- Member repository orchestration moved to shared
  `MemberRepositoryDataSource.kt`. Android now instantiates shared
  `RealMemberRepository` directly; the Android `MemberRepository`,
  `AgentProfileUpdateRequest`, `AddChannelMemberRequest`,
  `RemoveChannelMemberRequest`, and `ChannelMemberActionResult` names are
  typealiases to shared types instead of shadow definitions. Member/agent/
  channel-member requests use shared DTO parsers/builders and the single shared
  `SlockNetworkClient` path/query/body boundary. Android member wiring now
  creates shared `SlockApiClient` + `KtorSlockNetworkClient` directly instead
  of routing through the Android `SlockApi` endpoint facade. Kuikly bridge
  network also adapts that same client shape instead of growing a parallel
  member-specific API port.
- Channel/home repository orchestration moved to shared
  `ChannelRepositoryDataSource.kt`. Android now instantiates shared
  `RealChannelRepository` directly; the Android `ChannelRepository`,
  `CreateChannelRequest`, and `ChannelLeaveResult` names are typealiases to
  shared types instead of shadow definitions. Channel/home/DM/create/join/leave/
  archive/delete requests consume the shared `SlockNetworkClient` with strict
  DTO request bodies and real response roots. The old Android
  `AndroidSlockHomeApi` adapter and `RealChannelRepository` wrapper have been
  removed.
- Workspace file response validation now stays in `WorkspaceFilesParser.kt`;
  app code asks shared for a canonical `{files:[...]}` payload or file-preview
  snapshot instead of checking `JsonObject` locally.
- Read-receipt repository orchestration moved to shared
  `ReadReceiptRepositoryDataSource.kt`. Android now instantiates shared
  `RealReadReceiptRepository` directly with the shared network client; the old
  Android `RealReadReceiptRepository` wrapper and its `SlockApi`/`RequestBody`
  request construction have been removed. Unread counts and read/unread
  mutations consume strict shared DTO request/response shapes.
- Server-unread summary orchestration moved to shared
  `ServerUnreadRepositoryDataSource.kt`. Android now instantiates shared
  `RealServerUnreadRepository` directly; the old Android wrapper and legacy
  current-server-only unread fixture shape were removed in favor of the real
  `{servers:[...]}` response contract.
- Inbox, saved, and search orchestration moved to shared
  `InboxSavedSearchRepositoryDataSource.kt`. Android now instantiates shared
  `RealInboxRepository`, `RealSavedRepository`, and `RealSearchRepository`
  directly; the Android `InboxRepository`, `SavedRepository`,
  `SearchRepository`, `InboxFilter`, `SearchQueryOptions`, and `SearchSort`
  names are typealiases to shared. Saved-message request bodies and search
  query construction now live at the shared `SlockNetworkClient` boundary.
- Inbox/saved/search repository contract tests moved to shared commonTest in
  `InboxSavedSearchRepositoryDataSourceTest.kt`; Android no longer owns those
  real-repository fixtures.
- Tasks orchestration moved to shared `TasksDataSource.kt`. Android now
  instantiates shared `RealTaskRepository` directly, and `TaskRepository` /
  `TaskFilter` are shared typealiases. Focused Tasks repository contract tests
  moved to shared commonTest in `TaskRepositoryDataSourceTest.kt`.
- Settings orchestration moved to shared `SettingsRepositoryDataSource.kt`.
  Android now instantiates shared `RealSettingsRepository` directly, and
  `SettingsRepository`, account/server Settings request DTOs, and
  `SettingsServerActionResult` are shared typealiases. The old Android
  `RealSettingsRepository.kt` and `SlockSettingsServerApi.kt` were deleted;
  focused Settings repository contract tests moved to shared commonTest in
  `SettingsRepositoryDataSourceTest.kt`.
- Conversation/thread/files/messages orchestration moved to shared
  `ConversationRepositoryDataSource.kt`. Android now instantiates shared
  `RealConversationRepository` directly, and `ConversationRepository` is a
  shared typealias. Channel message paging, message context/sync, send message,
  thread create/follow/done actions, attachment URL, attachment upload, and
  channel files now consume shared DTO parsers/builders and the shared
  `SlockNetworkClient` boundary.
- Focused conversation repository contract tests moved to shared commonTest in
  `ConversationRepositoryDataSourceTest.kt`.
- Android wrapper-era network facade files were deleted:
  `RealSlockRepositories.kt`, `SlockApi.kt`, `ComposeSlockApiAdapter.kt`, and
  `NetworkResponseMapper.kt`. Live Android tests now create shared
  `RealConversationRepository` with `RealSlockNetworkClientFactory`.
- Auth API request/response parsing moved to shared `AuthDataSource.kt` typed
  DTOs. Register, login, forgot-password, refresh, logout, current user,
  server list, and server creation now flow through shared `SlockAuthApi` /
  `SlockAuthTransport`. Android deleted `ApiResponse.kt`, `AuthApi.kt`,
  `AndroidSlockAuthApi.kt`, `AuthRepository.kt`, `KtorAuthRepository.kt`,
  `AuthModels.kt`, `AuthSessionStore.kt`, and the
  `ComposeAuthSessionStoreAdapter`; `KtorAuthApi` is now only the platform Ktor
  transport, while encrypted storage implements shared
  `SlockAuthSessionStore` directly and app/live-test auth flows call
  `SharedAuthDataSource`.
- Request body builders now encode named shared DTOs instead of dynamic maps or
  private anonymous shapes for auth, channel/member/settings mutations,
  read-receipts, message reactions, thread sends/actions, saved-message
  mutation, and task status/create/convert.
- Android repository interfaces for auth-session, server, and agent access now
  typealias the shared contracts instead of declaring parallel shadows. The
  duplicate Android read-receipt coordinator/gate alias files and tests were
  deleted; their behavior is covered by shared commonTest.
- Android no longer keeps a `core/data/repository` typealias package. App/UI,
  mock, and local-store code import shared repository contracts/snapshots
  directly from `ai.slock.compose.data`; the remaining Android realtime socket
  contract lives under `core/data/realtime`.

### Ordered Todo

1. Platform-only Android data cleanup
   - Keep Android `app/src/main/java/ai/slock/android/core/data` focused on
     platform capabilities: encrypted session storage, token refresh adapter,
     diagnostics, local SQLDelight store, realtime/socket transport, locale,
     preferences, and storage management.
   - New non-auth HTTP routes must not be added under Android data/network.
   - Remaining candidates to inspect: Android auth model/session adapters and
     mock/sample fixtures. Keep them only where they are platform persistence,
     live-test, or demo-preview concerns.

2. Tests / Fixtures
   - Shared repository behavior belongs in
     `compose/shared/src/commonTest/kotlin/ai/slock/compose/data`.
   - Android tests should cover platform adapters/integration only.
   - Remaining Android mock fixtures are app/demo fixtures; migrate only if they
     start asserting shared repository behavior rather than Android wiring.

## Canonical parser pattern

```kotlin
// compose/shared/src/commonMain/kotlin/ai/slock/compose/data/XxxParser.kt
private val xxxJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireXxxDto(/* camelCase fields only */)

fun parseSharedXxx(bodyText: String, /* …context… */): T {
    val wire = runCatching {
        xxxJson.decodeFromString(WireXxxDto.serializer(), bodyText)
    }.getOrElse { return /* safe default */ }
    return wire.toDomain(/* … */)
}
```

App side:

```kotlin
val repository = RealXxxRepository(sessionStore = sharedStore, networkClient = sharedNetworkClient)
```

Verify:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose && \
  ./gradlew :shared:publishAndroidReleasePublicationToMavenLocal
cd /Users/artin/AndroidStudioProjects/Slock && \
  ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest --tests \
  "ai.slock.android.core.data.quality.NetworkPriorityCallSiteTest*" \
  --tests "ai.slock.android.core.data.mock.MockRepositoryTest*"
```

## Rules

- If a same-package private `Wire*` name collides, suffix with `Dto`.
- Shared parsers MUST be public top-level — `internal` does not cross the Maven
  artifact boundary.
- Do not collapse the 3 OkHttp priority lanes (Foreground 12 / Visible 8 /
  Background 4) when touching network code (see `AGENTS.md`).
- Don't bypass safety checks (`--no-verify`, force-push) when committing.
