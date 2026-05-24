# Slock DTO Migration Skill

Use this skill when adding, changing, reviewing, or cleaning up Slock server API
contracts. All HTTP request/response shapes must live as strict
kotlinx-serialization DTOs in the shared module.

The goal is fail-fast on contract drift: if the server renames a field, the
parser returns `null` (or the test fixture deserializes to defaults) instead
of silently filling holes with `???` text and stale alias matches.

## When to use

Trigger words: "DTO sweep", "strip field aliases", "remove field guessing",
"strict parser", "migrate to shared parser", "request body DTO", "shared
repository", or any Android-side API/parser/repository code that duplicates a
shared contract.

Do NOT use for pure UI/view-model code, local-only persistence shape changes,
or Android platform plumbing that does not define a server wire contract.

## Current architecture

The DTO sweep is complete. Treat these as the standing rules:

- API response contracts live under
  `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/` as
  `@Serializable` camelCase DTOs and public shared parser/repository APIs.
- API request bodies are also contracts. They must be named shared
  `@Serializable` request DTOs/builders. Do not use `mapOf`, ad hoc
  `JsonObject`, string concatenation, or route-local anonymous shapes.
- Shared repositories own reusable API orchestration and consume the shared
  `SlockNetworkClient` boundary. Android should instantiate shared
  repositories directly.
- Android `app/src/main/java/ai/slock/android/core/data` is for platform
  capabilities only: encrypted auth storage, token refresh/interceptors, Ktor
  transport/factory wiring, diagnostics, SQLDelight local store, realtime/socket
  transport, locale, preferences, storage management, and app/demo fixtures.
- Android must not keep product API facades or shadow repository/typealias
  packages. In particular, do not reintroduce `RealSlockRepositories.kt`,
  `SlockApi.kt`, `ComposeSlockApiAdapter.kt`, `NetworkResponseMapper.kt`,
  Android `AuthRepository`/`KtorAuthRepository` wrappers, or
  `core/data/repository` typealias shims.
- Auth flows use shared `SharedAuthDataSource`, `SlockAuthApi`, and
  `SlockAuthTransport`. Android `KtorAuthApi` is only the platform Ktor
  transport, and `EncryptedAuthSessionStore` implements shared
  `SlockAuthSessionStore` directly.
- Shared repository behavior tests belong in
  `compose/shared/src/commonTest/kotlin/ai/slock/compose/data`. Android tests
  should cover platform adapters, integration, or app/demo fixture behavior.

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
rg -n "import ai\\.slock\\.compose\\.(data|model)\\.InboxItem" app/src/main/java compose/shared/src
```

Same care applies to any other duplicate-named type pair.

### 4. Wire from the app

Wire callers to shared repository/data-source APIs directly. If Android needs a
platform transport, keep it generic and platform-scoped (for example Ktor
engine/factory wiring), then pass it into the shared repository boundary. Do not
add feature-specific Android endpoint facades.

## Build & test loop

The shared module is consumed via `mavenLocal()`; every shared-side change
must be republished before the app sees it.

```bash
# 1. publish shared after every shared/* edit
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:publishAndroidReleasePublicationToMavenLocal

# 2. run targeted tests from repo root
cd /Users/artin/AndroidStudioProjects/Slock
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest \
  --tests "ai.slock.android.core.data.quality.NetworkPriorityCallSiteTest*" \
  --tests "ai.slock.android.core.data.mock.MockRepositoryTest*" \
  --tests "ai.slock.android.core.data.realtime.RealtimeRepositoryTest*"
```

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

Each commit must independently republish shared when needed, compile the app,
and keep the architecture guards green. Do not leave a half-migrated endpoint
with both Android and shared contracts alive.

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
- Reintroducing Android-side product API request wrappers, response parsers,
  repository shadows, or typealias packages after the sweep has been closed.
