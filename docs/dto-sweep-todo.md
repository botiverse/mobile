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

### In progress / next

- **20 pre-existing test failures** in `RealSlockRepositoriesTest`. These are
  NOT caused by the sweep — `git stash` shows the same set on baseline. They
  obscure regression signal for further parser surgery, so triage first or do
  the moves under `--tests` filters that skip the broken set.

### Move to shared (one batch at a time, each its own commit)

1. **Channel/Home conversation actions and lists**:
   `createChannel`, `openDirectMessage`, channel join/archive response mapping,
   `homeConversationItems`, `toConversation*`, and the conversation avatar /
   joined / archived helpers in `RealSlockRepositories.kt`.
2. **Member/channel-members profile parsing**:
   `toMemberProfile`, `toChannelMemberProfile`, `memberItems`,
   `agentMemberItems`, and the add/remove member response wrappers.
3. **Agent detail surfaces**:
   agent DMs, reminders, activity log, current activity fallback, skills, and
   workspace/machine lookup helpers.
4. **Settings server API parser cleanup**:
   move `SlockSettingsServerApi.kt`'s typed Gson mappers for machines,
   workspaces, admins, usage, billing, onboarding, profile update, invite, and
   delete into shared strict DTO parsers.
5. **Delete or split `NetworkResponseMapper`** once `execute<T>{}` no longer
   needs to carry legacy `items`, `string`, `int`, `long`, `boolean`,
   `stringList`, and `avatarWireValue` helpers for repository parsing.

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
mapper = { json ->
    ai.slock.compose.data.parseSharedXxx(json.toString(), …)
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
