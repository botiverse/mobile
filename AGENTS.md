# Slock Android Agent Notes

## Upstream Slock Source

- The upstream TypeScript Slock source is checked out as a git submodule at
  `third_party/slock-source/` (`github.com/botiverse/slock`).
- For any wire-contract / API question (HTTP routes, Socket.IO events, payload
  shapes, field names), read the source directly under
  `third_party/slock-source/packages/server/` (HTTP + Socket handlers) and
  `third_party/slock-source/packages/shared/` (shared TypeScript types) rather
  than guessing field aliases or inferring shapes from Android call sites.
- Do not re-introduce bundled web JS artifacts. There used to be a
  `artifacts/web-current/` directory with a minified bundle; it has been
  removed in favor of reading the source.
- To fetch the submodule for the first time:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
git submodule update --init --recursive third_party/slock-source
```

## Shared DTO API Contract Rule

- Iron rule: every Android API wire contract must go through an explicit shared
  DTO/parser in `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/`.
  Do not add new app-side Gson/JsonObject response mappers for API payloads in
  `app/src/main/java/.../network/`.
- For any new or changed API endpoint, first verify the canonical field names
  in `third_party/slock-source/packages/server/` and
  `third_party/slock-source/packages/shared/`, then model that exact camelCase
  shape as a `@Serializable` DTO in shared. No snake_case aliases, no
  `firstObject(...)` envelope sniffing, and no `string("a", "b", "c")` field
  guessing.
- App repositories should wire responses by calling public shared parser
  functions such as `parseSharedXxx(...)` and should keep only UI composition,
  fallback context, and repository orchestration locally.
- Request bodies that represent server API contracts should likewise use shared
  DTOs/builders when they are more than tiny one-off action payloads. Avoid
  drifting ad hoc maps in app code for reusable API shapes.
- Shared repositories should own reusable API orchestration and consume the
  shared `SlockNetworkClient` request boundary. Do not duplicate real
  repositories per platform, and do not add feature-specific platform transport
  ports such as `SlockXxxApi` when a path/query/body request through the shared
  network client is enough.
- If a shared repository interface/type can be consumed directly from Android,
  do that. Do not leave an Android "thin wrapper" whose only job is to delegate
  to shared; use typealiases or direct shared imports while the old Android
  package names are being retired.
- Platform code (Android, OHOS/Kuikly, future targets) should adapt only the
  single bottom network layer: auth, refresh, priority, headers, and raw HTTP
  request/response. Server DTO parsing and request-body construction remain in
  shared.
- After changing `compose/shared`, publish it locally before app validation:
  `cd /Users/artin/AndroidStudioProjects/Slock/compose && ./gradlew :shared:publishAndroidReleasePublicationToMavenLocal`.

## Kuikly AI Skills

- Workspace-level Kuikly guidance lives under `.agents/`:
  - `.agents/skills/kuikly-*` contains the vendored skills from
    `Tencent-TDS/KuiklyUI-AI`.
  - `.agents/rules/kuiklyComposeDSL.mdc` and `.agents/rules/kuiklyDSL.mdc` contain
    the Kuikly Compose / Kuikly DSL rules.
  - `.agents/kuikly-ui-ai-source.txt` records the upstream source and commit.
- For Kuikly, OHOS, shared shell, navigation, assets, native bridge, or
  recomposition work, read the relevant workspace skill/rule first instead of
  relying on Android-native assumptions.
- To refresh these files from upstream, run:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
scripts/sync_kuikly_ai_skills.sh
```

## Compose Shared Workflow

- The Android app consumes Kuikly Compose shared code as the Maven artifact
  `ai.slock.compose:shared-android:1.0.0` from `mavenLocal()`.
- Root `settings.gradle.kts` only includes `:app`; `compose/shared` is not in the app
  source graph.
- When changing code under package `ai.slock.compose.*`, make the source change in
  `compose/shared/src/commonMain/kotlin/...`, not in `app/src/main/java/...`.
- After changing `compose/shared`, publish it locally before compiling or packaging
  the Android app:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:publishAndroidReleasePublicationToMavenLocal
```

- Then run app validation from the repo root, for example:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
./gradlew :app:compileDebugKotlin
```

- Do not keep shadow copies of shared classes in `app/src/main/java/ai/slock/compose/...`.
  Temporary app-side shims can make one APK build, but they drift from the real shared
  artifact and should be removed before handoff.

## Network Priority Workflow

- Every Android request that reaches `ai.slock.compose.network.SlockApiClient` must be
  started inside `withSlockRequestPriority(...)`; do not rely on an implicit default.
- New product API routes must be implemented in `compose/shared` behind shared
  request/response DTOs and `SlockNetworkClient`; Android must not reintroduce a
  parallel `SlockApi`, endpoint facade, or app-local JSON parser for those
  contracts.
- Shared `SlockNetworkClient` calls must take an explicit `DataRequestPriority`.
  Do not add no-priority overloads or defaults. This is the compile-time
  tripwire: new API methods and fake implementations should fail to compile
  until the caller chooses Foreground, Visible, or Background.
- Use `Foreground` only for user-blocking work: opening the current thread, sending
  messages, uploading attachments for send, opening/downloading the tapped attachment,
  task status changes, membership/channel mutations, and settings save/delete actions.
- Use `Visible` for content the user is currently looking at but that is not an
  immediate mutation: current thread refresh/pagination, current tab files/tasks,
  visible inbox/saved/search refresh, and explicit retry actions.
- Use `Background` for warmup/reconcile/periodic work: Home progressive prefetch,
  unread summary polling, silent home refreshes with cached content, background
  saved/followed/search refreshes, diagnostics, and any future cache preloads.
- If a new repository/API call is added, first decide its priority and put the
  priority key/reason in the call site. Missing priority is a bug because it hides
  work in the wrong queue and makes request-pool logs useless.
- Current OkHttp lane policy is intentionally separate per priority: Foreground
  scheduler/client `12`, Visible `8`, Background `4`. This keeps foreground work well
  above OkHttp's single-dispatcher per-host default while still capping background
  refresh. Do not collapse these lanes back into one client or one dispatcher.
- Background work has its own low-priority lane. It is allowed to run concurrently,
  but a background request waits before starting HTTP whenever Foreground/Visible work
  is waiting or active. Already-started background requests are not preemptively
  cancelled; page/server cancellation is handled by the caller scope and stale-result
  guards.
