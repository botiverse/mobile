# Slock DTO Sweep — Cross-Commit Tracking

Goal: every JSON parsing call site goes through a strict `@Serializable`
camelCase DTO living in `compose/shared/.../data/`. App-side helpers in
`RealSlockRepositories.kt` / `RealSettingsRepository.kt` should disappear, and
`NetworkResponseMapper` should ultimately be deleted.

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
- `RealSlockRepositories.kt` no longer contains app-side `JsonObject` response
  helpers (`firstObject`, `unwrap`, `items`, `string`, `toMemberProfile`,
  `toConversation*`, agent skill/activity/workspace mappers, unread/read
  mappers, etc.).

### Ordered Todo

1. Conversation / Thread / Files / Messages
   - Move `RealConversationRepository` orchestration into shared.
   - Reuse `ThreadMessagesParser.kt`, `ThreadActionParser.kt`,
     `AttachmentsParser.kt`, `WorkspaceFilesParser.kt`, and shared request-body
     builders.
   - Multipart upload should expose a shared file model and keep only the
     platform byte-source adaptation at the bottom layer.

2. Android Network Facade Retirement
   - Delete `SlockApi`, `ComposeSlockApiAdapter`, `ApiResponse`, and
     `NetworkResponseMapper` once all repositories consume `SlockNetworkClient`
     directly.
   - Remove app-level `okhttp3.RequestBody`/`MultipartBody` usage from API
     request construction. Ktor may still use OkHttp as the Android engine via
     `PriorityHttpClient.android.kt`; that is the platform engine, not a second
     API facade.

3. Tests / Fixtures
   - Update or delete alias-only and wrapper-era fixtures in
     `RealSlockRepositoriesTest`.
   - Keep focused contract tests for each shared repository and run full class
     only after stale fixture cleanup.
   - As each repository moves to shared, move its focused contract tests into
     `compose/shared/src/commonTest` and leave Android tests for platform
     adapter/integration behavior only.

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
mapper = { bodyText ->
    ai.slock.compose.data.parseSharedXxx(bodyText, …)
}
```

Verify:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose && \
  ./gradlew :shared:publishAndroidReleasePublicationToMavenLocal
cd /Users/artin/AndroidStudioProjects/Slock && \
  ./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests \
  "ai.slock.android.core.data.network.RealSlockRepositoriesTest*"
# diff failing-test list against baseline; only the pre-existing 20 are allowed
```

## Rules

- If a same-package private `Wire*` name collides, suffix with `Dto`.
- Shared parsers MUST be public top-level — `internal` does not cross the Maven
  artifact boundary.
- Do not collapse the 3 OkHttp priority lanes (Foreground 12 / Visible 8 /
  Background 4) when touching network code (see `AGENTS.md`).
- Don't bypass safety checks (`--no-verify`, force-push) when committing.
