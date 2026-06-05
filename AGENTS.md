# Slock Agent Notes

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
  `compose/androidApp/src/main/java/.../network/`.
- For any new or changed API endpoint, first verify the canonical field names
  in `third_party/slock-source/packages/server/` and
  `third_party/slock-source/packages/shared/`, then model that exact camelCase
  shape as a `@Serializable` DTO in shared. No snake_case aliases, no
  `firstObject(...)` envelope sniffing, and no `string("a", "b", "c")` field
  guessing.
- App repositories should wire responses by calling public shared parser
  functions such as `parseSharedXxx(...)` and should keep only UI composition,
  fallback context, and repository orchestration locally.
- Request bodies that represent server API contracts must likewise use named
  shared DTOs/builders. Avoid drifting ad hoc maps in app code for API shapes.
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
- After changing `compose/shared`, validate from the compose Gradle root. The Android
  app consumes `:shared` as a direct project dependency, so no MavenLocal publish step
  is needed for normal app validation.

## Kuikly References And Skills

- Workspace-level Kuikly guidance lives under `.agents/`:
  - `.agents/skills/kuikly-*` contains the vendored skills from
    `Tencent-TDS/KuiklyUI-AI`.
  - `.agents/skills/slock-dto-migration/SKILL.md` contains Slock-specific DTO
    migration workflow guidance.
  - `.agents/rules/kuiklyComposeDSL.mdc` and `.agents/rules/kuiklyDSL.mdc` contain
    the Kuikly Compose / Kuikly DSL rules.
  - `.agents/kuikly-ui-ai-source.txt` records the upstream source and commit.
- For Kuikly, OHOS, shared shell, navigation, assets, native bridge, or
  recomposition work, read the relevant workspace skill/rule first instead of
  relying on Android-native assumptions.
- The local Tencent KuiklyUI upstream reference lives at
  `.agents/skills/kuikly-ui-framework/references/KuiklyUI/`. Keep KuiklyUI
  reference/demo/source lookup there instead of adding another copy under
  `third_party/`.
- Useful KuiklyUI reference paths:
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/demo/src/commonMain/kotlin/com/tencent/kuikly/demo/pages/compose/`
    for Compose DSL demos such as popups, gestures, Canvas, dialogs, lazy lists,
    and text/input behavior.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/ohosApp/` for the
    HarmonyOS host shell.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/androidApp/` for the
    Android host shell.
  - `.agents/skills/kuikly-ui-framework/references/KuiklyUI/core-render-ohos/`
    and `.agents/skills/kuikly-ui-framework/references/KuiklyUI/compose/` for
    render/runtime implementation details.
- Treat the KuiklyUI reference tree as vendor/reference source. Do not modify it
  unless explicitly asked; for Slock product fixes, change Slock shared/app code.
- To refresh the `.agents/` Kuikly AI skill/rule bundle from upstream, run:

```bash
cd /Users/artin/AndroidStudioProjects/Slock
scripts/sync_kuikly_ai_skills.sh
```

## KuiklyUI Fork

- We maintain a fork at `https://github.com/bytemain/KuiklyUI` checked out as a
  git submodule at `third_party/kuikly-ui/` (branch `staging`).
- Patches that fix Kuikly upstream bugs land on `staging` first, then a PR is
  submitted to `Tencent-TDS/KuiklyUI`.
- Current patches on staging:
  - `toKuiklyFontFamily()` weight serialization: includes FontWeight in the
    serialized font family string (`"fontName:weight"`) so multi-weight
    FontFamily entries resolve correctly on the platform side.
- When a fix is accepted upstream and released as a new Kuikly version, update
  `KUIKLY_VERSION` in `compose/buildSrc/src/main/java/KotlinBuildVar.kt` and
  simplify any workaround code that the fix obsoletes (e.g. per-weight
  FontFamily dispatch in `SlockDesignTokens.kt`).
- To publish the fork locally for testing:
  ```bash
  cd /Users/artin/AndroidStudioProjects/Slock
  sh scripts/build_kuikly_fork.sh
  ```
- After publishing, the Slock Android build picks up the forked artifacts
  from mavenLocal automatically (`mavenLocal()` is already in the repository
  config). Android Studio Run works without extra steps — only re-run the
  script after pulling fork changes (e.g. `git submodule update`).
- Do not use `includeBuild` for the fork. The KuiklyUI project includes
  androidApp/demo/ohosApp/h5App/iosApp in its settings, causing composite
  builds to configure the entire project tree on every Gradle sync and
  timing out.""

## OHOS Kuikly Workflow

- The Slock OHOS app bundle/module/ability is `ai.slock.compose` / `entry` /
  `EntryAbility`.
- `hdc` may not be on `PATH`; the default DevEco path in this workspace is
  `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc`.
- To build, install, and launch the OHOS app, run the wrapper from the Compose
  project. The script is not guaranteed to have its executable bit set, so prefer
  invoking it through `sh`:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
sh ohosApp/runOhosApp.sh
```

- `scripts/capture_ohos_screenshot.sh` uses `snapshot_display`; on some current
  OHOS emulator images, `snapshot_display` rejects `.png` output names and only
  accepts `.jpeg`. If screenshot capture fails with a suffix error, use a remote
  `.jpeg` path and pull that file with `hdc file recv`.

## OHOS Kuikly SharedPreferences

- Kuikly OHOS has two SharedPreferences implementations:
  `KRSharedPreferencesModule` uses XML files and is compatible with all API
  versions; `KROhSharedPreferencesModule` uses the native OpenHarmony
  `oh_preferences` C API / GSKV format and requires OpenHarmony API >= 13.
- Prefer `KROhSharedPreferencesModule` on API 13+ by enabling it through the
  Kuikly entry config, for example `Kuikly({ useOhSharedPreferences: true, ... })`.
- When business code builds a platform Preference instance on OHOS, never set
  `fileName` to `KROhSharedPreferencesModule`; that name is reserved by the
  module implementation and will conflict with its cache path.
- In shared code, `KuiklySharedPreferencesKeyValueStore` namespaces are only
  key prefixes such as `tasks:viewMode`; do not reinterpret those namespaces as
  platform Preference file names.
- Local product preferences must be represented as shared repositories over
  `SharedPreferenceStore` in `compose/shared`. Business code must not create
  platform `SharedPreferences` / OHOS `Preference` instances, choose file names,
  or hand-roll preference keys in feature pages.
- Platform hosts only adapt the bottom store: Android provides
  `AndroidSharedPreferencesKeyValueStore` using the shared
  `SlockLocalPreferenceStore.AndroidPreferencesName`; Kuikly/OHOS provides
  `KuiklySharedPreferencesKeyValueStore` through `kuiklySharedPreferenceStore(...)`.
  Business repositories for locale, chat layout, notifications, tasks view mode,
  and future local settings stay in shared.

## Compose Shared Workflow

- The Android app lives at `compose/androidApp` and consumes Kuikly Compose shared code
  through `implementation(project(":shared"))` in the same compose Gradle project.
- When changing code under package `ai.slock.compose.*`, make the source change in
  `compose/shared/src/commonMain/kotlin/...`, not in `compose/androidApp/src/main/java/...`.
- After changing `compose/shared`, compile the shared module and Android app from the
  compose Gradle root:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin
```

- Then package the Android app from the same Gradle root, for example:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:assembleDebug
```

- Do not keep shadow copies of shared classes in
  `compose/androidApp/src/main/java/ai/slock/compose/...`.
  Temporary app-side shims can make one APK build, but they drift from the real shared
  artifact and should be removed before handoff.

## Network Priority Workflow

- Every Android request that reaches `ai.slock.compose.network.SlockApiClient` must be
  started inside `withSlockRequestPriority(...)`; do not rely on an implicit default.
- New product API routes must be implemented in `compose/shared` behind shared
  request/response DTOs and `SlockNetworkClient`; Android must not reintroduce a
  parallel `SlockApi`, endpoint facade, or app-local JSON parser for those
  contracts.
- Request bodies must be explicit `@Serializable` shared DTO types too. Do not
  build API bodies with `mapOf`, ad hoc `JsonObject`, string concatenation, or
  route-local anonymous shapes; expose a named request DTO/builder in shared and
  encode that DTO at the network boundary.
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

## Shared MVI + Repository/DataSource Layered Contract

The project enforces a strict unidirectional data-flow chain for all feature pages:

```
UI -> Store -> UseCase/Interactor -> Repository -> DataSource/API/HostCapability
```

Core constraints every agent must follow:

- **UI layer**: only reads `State`, only emits `Intent`, only receives one-shot
  `Effect`. Never calls Repository/DataSource directly.
- **Store layer**: owns the reducer, concurrency/debounce/stale-token, in-flight
  idempotency guard, no-op equality gate, and typed result/error mapping.
  Same snapshot must not re-emit; local patches must not cause full-page refresh.
- **Repository layer**: exposes typed domain results (`DataResult<T>` / `DomainError`).
  Never leaks raw HTTP response, token, headers, or platform-specific payloads upward.
- **Success path**: patch only the affected entity slice. Do not replace the whole
  state tree.
- **Failure path**: keep the previous state intact and attach a route/action error.
  Never clear content on failure.
- **Cache / realtime**: must flow back through the Store reducer. Never bypass MVI
  to directly mutate UI state from a socket event or local cache read.
- **Shared MVI runtime**: `ai.slock.compose.mvi` provides the lightweight foundation
  (`Intent`, `State`, `Effect`, `Store`, `dispatch`, `no-op gate`, `single-flight`,
  `stale token`, `transition log`, `test harness`). Feature stores (Home, Thread,
  Settings, Tasks) extend this contract, but keep their own state shape, selectors,
  and snapshot granularity.

## Compose ViewModel Lifecycle Rule

This rule prevents a real production crash (white screen on launch).

- **Never** access `viewModelScope`, `pagerId`, `Utils.bridgeModule(pagerId)`, or
  any Pager/runtime-context-dependent object inside a ViewModel constructor,
  property initializer, or `init {}` block.
- Kuikly may not have finished Pager initialization when the ViewModel is
  constructed. Premature access triggers `lateinit property pagerId has not been
  initialized` and blocks the first frame.
- **Correct pattern**: save pure dependencies (repository, auth store, data source,
  configuration) in the constructor. Defer creation of `Store` / `Effect` / coroutine
  jobs that need `viewModelScope` to a `lazy { ... }` block, or to an explicit
  `start` / `attach` / `dispatch(Start)` call after composition begins.
- In `onCleared()`, check `isInitialized()` before touching lazy stores to avoid
  triggering initialization during cleanup.

Example:

```kotlin
class SharedExampleViewModel(
    private val dataSource: SharedExampleDataSource
) : ViewModel() {
    private val storeDelegate = lazy {
        SharedExampleStore(dataSource = dataSource, scope = viewModelScope)
    }
    private val store: SharedExampleStore by storeDelegate

    val state: StateFlow<SharedExampleState>
        get() = store.state

    fun dispatch(intent: SharedExampleIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) store.clear()
        super.onCleared()
    }
}
```

## Kuikly Compose API Quick Reference

For the full specification, read `.agents/rules/kuiklyComposeDSL.mdc`. This is the
minimal "don't-use" list that covers ~80% of compile/runtime errors:

| ❌ Do NOT use | ✅ Use instead |
|---|---|
| `OutlinedTextField(...)` | `TextField(...)` with custom `shape` / `colors` |
| `Modifier.verticalScroll(state)` | `LazyColumn` wrapping a single `item {}` |
| `Modifier.horizontalScroll(state)` | `LazyRow` wrapping a single `item {}` |
| `Modifier.imePadding()` / `WindowInsets.ime` | Delete — keyboard is handled by the native layer |
| `Icon` / `IconImage` | `Image` with `painterResource` |
| `reverseLayout` / `flingBehavior` on LazyColumn/LazyRow | Delete these params |
| `PullToRefreshBox` | `pullToRefreshItem()` as the first item in LazyList |
| `pointerInput(Unit) { ... }` reading mutable state | `pointerInput(state1, state2) { ... }` — all captured state must be a key |
| `PasswordVisualTransformation()` | `KeyboardOptions(keyboardType = KeyboardType.Password)` |
| `BasicTextField2` / `TextFieldState` | `BasicTextField(value, onValueChange)` legacy API |
| `Modifier.animateItem()` in LazyColumn/LazyRow | Delete — not supported (OK in Grid/StaggeredGrid) |
| `TopAppBar(scrollBehavior = ...)` | Fixed `TopAppBar`; scroll collapse is not supported |

Import rules: only `androidx.compose.runtime.*` uses the official package; everything
else (UI, foundation, material3, animation) imports from `com.tencent.kuikly.compose.*`.

## Multi-Platform Build Validation

After any shared-layer change, validate all target platforms before handoff:

```bash
# 1. Shared Android compile
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :shared:compileDebugKotlinAndroid

# 2. Shared OHOS compile (Kotlin/Native cross-check)
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64

# 3. Android app compile + assemble
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:compileDebugKotlin :androidApp:assembleDebug

# 4. OHOS HAP (requires DevEco Studio)
cd /Users/artin/AndroidStudioProjects/Slock/compose
sh ohosApp/runOhosApp.sh

# 5. Focused unit tests
cd /Users/artin/AndroidStudioProjects/Slock/compose
./gradlew :androidApp:testDebugUnitTest \
  --tests "ai.slock.android.core.data.quality.NetworkPriorityCallSiteTest*" \
  --tests "ai.slock.android.core.data.mock.MockRepositoryTest*"
```

Key invariants:
- If step 1 fails, fix shared before validating the app. The Android app now sees
  shared changes directly through the project dependency.
- Step 2 must pass even if you only changed commonMain; Kotlin/Native catches
  expect/actual mismatches, missing `ohosArm64` source sets, and
  platform-incompatible dependencies.
- Step 4 may emit warnings from `@kuikly-open/render` `.d.ets` strict-typing
  comments; treat them as warnings unless Hvigor reports a hard compile error.
