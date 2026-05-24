# Slock DTO Migration Skill

Use this skill when migrating Slock server endpoints off the legacy
"firstObject / string(...alias, alias, alias)" wire-guessing helpers in
[app/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt](app/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt)
and onto strict kotlinx-serialization DTOs in the shared module.

The goal is fail-fast on contract drift: if the server renames a field, the
parser returns `null` (or the test fixture deserializes to defaults) instead
of silently filling holes with `???` text and stale alias matches.

## When to use

Trigger words: "DTO sweep", "strip field aliases", "remove field guessing",
"strict parser", "migrate to shared parser", any `toXxxSummary()` /
`toXxxSnapshot()` extension on `JsonObject` in `RealSlockRepositories.kt`
that takes multiple alias names.

Do NOT use for:
- New endpoints that already have a shared parser.
- Pure UI / view-model code.
- Settings save/delete actions that have no JSON payload to map.

## Canonical wire contract

The single source of truth is the upstream TypeScript source mounted as a
submodule at [third_party/slock-source/](third_party/slock-source/).

- HTTP routes & response shapes:
  `third_party/slock-source/packages/server/src/routes/*.ts`
- Enrichment & serializers (the "real" field names emitted on the wire):
  `third_party/slock-source/packages/server/src/services/*.ts`
  (e.g. `channelService.ts` for inbox / home, `taskService.ts` for tasks,
  `savedService.ts` for saved, `messageService.ts` for messages).
- Shared types:
  `third_party/slock-source/packages/shared/src/index.ts`.

Never infer a field name from an Android call site. Read the TS source.
If the submodule is missing:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
git submodule update --init --recursive third_party/slock-source
```

Do NOT reintroduce `artifacts/web-current/` — the minified JS bundle is
gone on purpose; reading source is faster and accurate.

## The pattern (DTO in shared, consume from app)

Every migration follows the same four-step shape. The reference
implementations are
[compose/shared/src/commonMain/kotlin/ai/slock/compose/data/InboxParser.kt](compose/shared/src/commonMain/kotlin/ai/slock/compose/data/InboxParser.kt)
and [compose/shared/src/commonMain/kotlin/ai/slock/compose/data/HomeParser.kt](compose/shared/src/commonMain/kotlin/ai/slock/compose/data/HomeParser.kt).

### 1. Read the upstream emitter

Open the TS service that builds the response. Note:

- Exact camelCase field names emitted.
- Discriminator field, if any (e.g. inbox `kind: "channel" | "dm" | "thread"`).
- Optional vs required (TS `field?:` → Kotlin nullable + default).
- Nested object shapes (some are emitted as Type/Id pairs only, NOT a
  populated object — e.g. `latestActivitySenderType` / `latestActivitySenderId`
  with no `latestActivitySenderName`).

### 2. Write the strict DTO in `compose/shared`

Create or extend a `XxxParser.kt` under
`compose/shared/src/commonMain/kotlin/ai/slock/compose/data/`. Skeleton:

```kotlin
package ai.slock.compose.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val xxxJson = Json {
    ignoreUnknownKeys = true   // tolerate server adding fields
    coerceInputValues = true   // null on a non-null primitive -> default
}

@Serializable
private data class WireXxx(
    val id: String,                    // required → non-nullable
    val name: String? = null,          // TS optional → nullable + default
    val count: Int = 0,                // TS number with default
)

@Serializable
private data class WireXxxResponse(
    val items: List<WireXxx> = emptyList(),
    val hasMore: Boolean = false,
)

data class SharedXxxSnapshot(val items: List<DomainXxx>, val hasMore: Boolean)

fun parseSharedXxxSnapshot(bodyText: String): SharedXxxSnapshot? = runCatching {
    val decoded = xxxJson.decodeFromString(WireXxxResponse.serializer(), bodyText)
    SharedXxxSnapshot(
        items = decoded.items.mapNotNull { it.toDomain() },
        hasMore = decoded.hasMore,
    )
}.getOrNull()

private fun WireXxx.toDomain(): DomainXxx? { ... }
```

Rules:
- One `Json {}` instance per file, `private`.
- `WireXxx` DTOs are `private` — they never leak out of the parser file.
- `runCatching { ... }.getOrNull()` at the public entry point: a parse
  failure returns `null`, which the app side interprets as Empty.
- `mapNotNull { it.toDomain() }` for items where individual rows may be
  unusable (missing required id, unknown discriminator).
- No alias lists. If TS emits `lastActivityAt`, that's the only name.
- Map enums via the existing helpers (e.g. `TaskStatus.fromWireValue`,
  `String.toConversationKind()`); add new ones in the parser file rather
  than in the model.

### 3. Pick the right domain target

There are TWO `InboxItem` data classes — a recurring trap:
- `ai.slock.compose.model.InboxItem` in
  `compose/shared/.../model/SlockModels.kt`
- `ai.slock.compose.data.InboxItem` in
  `compose/shared/.../data/DataSnapshots.kt` — **this** is what
  `RealInboxRepository` and the rest of the app consume.

When in doubt, grep the call site:

```text
grep -n "import ai.slock.compose.(data|model).InboxItem" app/src/main/java/ai/slock/android/core/data/network/RealSlockRepositories.kt
```

Same care applies to any other duplicate-named type pair.

### 4. Wire from the app

In `RealSlockRepositories.kt`, replace the `mapper = { json -> json.toXxx() }`
body with:

```kotlin
mapper = { json -> ai.slock.compose.data.parseSharedXxxSnapshot(json.toString()) },
```

Then delete the now-unused `JsonObject.toXxx()` extension and any
`firstObject(...)` / multi-alias `string(...)` it dragged along.

If multiple endpoints (e.g. `/inbox`, `/saved`, `/search`) share an
extension, do them in a single batch and only delete the helper after the
last caller is gone — otherwise `compileDebugKotlin` will fail on the
dangling references.

## Build & test loop

The shared module is consumed via `mavenLocal()`; every shared-side change
must be republished before the app sees it.

```bash
# 1. publish shared after every shared/* edit
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:publishAndroidReleasePublicationToMavenLocal

# 2. run targeted tests from repo root
cd /Users/artin/AndroidStudioProjects/Slock
./gradlew :app:testDebugUnitTest \
  --tests "ai.slock.android.core.data.network.RealSlockRepositoriesTest*" \
  2>&1 | grep -E "FAILED$|tests completed" | sort > /tmp/cur.txt
diff /tmp/base.txt /tmp/cur.txt
```

`/tmp/base.txt` holds the pre-change baseline of expected failures. The
acceptance bar is **empty diff vs baseline** (or a strictly smaller
failure set, never larger).

If you forget step 1, the app sees the previous shared snapshot and your
new parser appears to "not exist" or "have the wrong signature". This is
the single most common waste-of-time loop.

## Test fixture realignment

When a strict parser replaces an alias-tolerant helper, several test
fixtures stop matching. The standard treatments:

| Old behaviour                                  | New treatment                                     |
| ---------------------------------------------- | ------------------------------------------------- |
| Test asserts `timestamp` alias resolves        | **Delete the test** — alias-only coverage.        |
| Test embeds a fake nested object the server never emits (e.g. `latestActivitySender: {...}`) | Remove the assertion AND the object. |
| Test JSON omits the discriminator `kind`       | Add `"kind":"channel"` (or `"dm"`/`"thread"`).    |
| Test uses `threadChannelId` for a task         | Rename to `threadId` (messages.thread_id schema). |
| Test wraps in `{metadata:{...}}` envelope      | Inline the canonical shape; envelope sniffing is gone. |

Document the deletion in the commit body — it is a deliberate coverage
change, not a regression.

## Commit hygiene

One endpoint family per commit. Conventional prefix `dto-sweep:` so the
history is greppable:

```
dto-sweep: strict InboxParser DTO in shared; wire RealInboxRepository
dto-sweep: strip task wire aliases to upstream canonical shape
```

Each commit must independently:
1. Republish + recompile the app clean.
2. Leave `diff /tmp/base.txt /tmp/cur.txt` empty.
3. Not leave a partially-migrated helper alive — if `toXxx()` still has
   callers after your commit, the commit should not delete it yet.

## Migration progress checklist

Track open work in [docs/dto-sweep-todo.md](docs/dto-sweep-todo.md).
Rough remaining order (low-risk → high-risk):

1. **Inbox** — `parseSharedInboxSnapshot` (in flight, see InboxParser.kt).
2. **Saved + Search** — strict DTOs (`/api/saved`, `/api/search/*`),
   delete the last two `toInboxItem()` callers, then delete `toInboxItem`.
3. **Conversations** — `toConversation*` family in
   `RealSlockRepositories.kt` (~L2160-2330). Largest alias surface; do
   after saved/search to reuse pattern.
4. **Messages / send result** — extend `ThreadMessagesParser.WireMessage`
   to back `parseSharedSendMessageResult` for POST `/api/messages`.
5. **Settings** — `toAccountSettingsSnapshot`, `toConnectedAccount`, etc.
6. **Final cleanup** — delete
   `app/src/main/java/ai/slock/android/core/data/network/NetworkResponseMapper.kt`
   (the home of `execute<T>{}`, `firstObject`, `items`, `string`, `int`,
   `long`, `boolean`, `avatarWireValue`). It can only go away once every
   `RealSlockRepositories.kt` and `RealSettingsRepository.kt` call site
   has been migrated.

## Anti-patterns to refuse

- Adding a new alias to `string("a","b","c", ...)` instead of reading
  source. If the field name is unclear, open the TS emitter.
- Re-introducing `firstObject("metadata","meta","payload","data")`
  envelope sniffing. Routes that wrap responses do so consistently; mirror
  the wrapper in the DTO (`WireXxxResponse`) — don't sniff at parse time.
- Catching exceptions from `decodeFromString` anywhere other than the
  one `runCatching { ... }.getOrNull()` at the public entry. Inner
  helpers should return `null` explicitly when a required field is
  missing.
- Bundling unrelated parser migrations into one commit. Keep each
  endpoint family isolated so test deltas are explainable.
- Editing shared code under `app/src/main/java/ai/slock/compose/...`
  (it's a stale shadow tree). The real source is
  `compose/shared/src/commonMain/kotlin/ai/slock/compose/...`.
