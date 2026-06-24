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

## Members Invite Link URL Rule

- Members invite-link loading and copying must use the current Developer
  environment web base URL, not a hardcoded production host.
- On load, read the active Developer environment through
  `BridgeSettingsPlatformCapabilities.loadDeveloper()`, pass its `webUrl` to
  `normalizeInviteHumanJoinUrl(rawUrl, webBaseUrl)`, and store/display the
  normalized result.
- The copy button must pass the same `webBaseUrl` into
  `normalizeInviteHumanJoinUrl(rawUrl, webBaseUrl)` so the copied link and the
  displayed field cannot drift across staging, local, or production
  environments.

## Route / PageData MVI Contract Rule

- Iron rule: do not pass product content/state through route, launch, or
  Kuikly `pageData` payloads. Pass only identity and intent, then let the
  destination MVI store/repository load canonical state.
- Visual, smoke, and test data must enter through fixtures/providers or direct
  typed construction, not through production route parsers.
- In-memory cache, durable local cache, realtime payloads, sparse responses,
  and legacy fallback/context are candidate snapshots, not automatically
  canonical state. Before using them in UI/MVI/cache/subscription/send scope,
  match them to the current route identity (for example server id, channel id,
  thread stream id, or parent channel id + parent message id). If they do not
  match, discard them and reload through MVI. If they match, they may only fill
  missing data and must never override canonical store state.
- This candidate-snapshot validation is a migration and contamination guard,
  not a long-term fallback payload architecture. The end state is no production
  full content/state payload or fallback display metadata in route/pageData.
- Guard this rule with structure-based tests: launch DTO descriptors,
  production parser rejection/ignore behavior, and narrow static checks on
  known route/pageData entry points. Do not use broad word-grep as a hard gate.

## Kuikly Overlay Coordinate Rule

- For Slock Kuikly Compose floating overlays, dropdowns, context menus, and
  anchored popovers, keep anchor and overlay geometry in pixels. Do not convert
  `positionInRoot()` or `coords.size` through `LocalDensity`, `toDp()`, `.dp`,
  or `toSlockDpOffset()` before passing an anchor to `SlockAnchoredPopover`.
- Use dp only for visual dimensions and spacing passed into Compose layout
  modifiers or parameters explicitly named `*Dp`.
- See `docs/kuikly-overlay-coordinate-contract.md` before changing
  `SlockAnchoredPopover`, `SlockFloatingOverlayLayout`, or trigger components
  such as `SlockAnchoredToolbarChip`.

## Task / Commit / Thread Discipline

Workflow rules for how every agent (human-supervised or autonomous) should move
work through Slock task threads, commits, and pushes. These rules apply on top
of the API contract and MVI rules above; they exist so other agents and humans
can verify any task in this repo from its thread alone, without reading code.

### Lightweight in_review handoff (2026-06-21)

When moving a task from `in_progress` to `in_review`, the task's thread should
contain a **lightweight** summary:

1. One sentence describing what changed and why.
2. Commit hash (or list of commit hashes).
3. Main integration status: explicitly say whether the work is still only on a
   worktree/branch, has been merged into local `main`, has been pushed/synced
   to remote `main`, or is not applicable. If it is not merged, name the
   branch/worktree and the blocker; if it is merged, include the local `main`
   commit hash.
4. (Optional) Anything owner / QA needs to runtime-verify (hilog command, emulator steps, fixture capture path).

Any meaningful progress / completion report should include this same main
integration status line. Do not leave reviewers or the owner guessing whether
the code they are hearing about is actually present on `main`.

Detailed file lists, full verification command output, and entry/walkthroughs
are still useful — put them in the commit message body, the worktree's commit
notes, or the `notes/` directory. Do not enforce a 4-piece rigid gate in the
thread; reviewers can `git show <hash>` to inspect the diff.

### No code gate required (2026-06-21)

There is no AD1 / AD2 / peer code review gate before pushing to `main`. When a
worktree is clean, validated locally, and the assignee judges it ready, the
assignee (or `Cindy` on behalf) can push it. If a problem surfaces later,
revert or follow-up; do not block the worktree-onto-main path on a review step.

Trust the worktree-then-push flow. Validation, lint, focused tests, and
runtime smoke still matter — but they are the assignee's responsibility, not a
gate another agent has to clear.

### Commit message should describe the change (2026-06-21)

Commit messages should clearly describe the change in the subject and (when
useful) the body. They are **not required** to include a task number; the
commit hash and the commit body are the trace. If a task number is helpful for
filtering, include it; otherwise omit.

### One thread, one topic

- Before replying in a thread, resolve the parent message and confirm which task
  number the thread belongs to (`slock message resolve <msgId>` if uncertain).
- If your reply actually belongs to a different task (e.g. the thread is #51
  but you're discussing #72), stop and move the discussion to the right thread.
- Do not keep expanding a wrong-thread reply just to "close it out"; an out-of-thread
  closure is worse than acknowledging and moving on.

If a thread has already mixed multiple tasks, restrict the current thread to
its parent task; other tasks must continue in their own task / spec threads.
You may ack the cross-task content ("this belongs to task X, continuing there")
without expanding it.

### DTO mocks must be verified against upstream fixtures, not invented

When writing test fixtures for typed API DTOs, **do not invent a "looks
reasonable" payload**. The Iron Rule says verify field names against
`third_party/slock-source/`; that rule also applies to test mocks.

A mock whose only purpose is to make a happy-path test pass is a contract drift
waiting to happen: the real server returns a different shape, and the field
that was guessed silently decodes to `null` (or default) at runtime, with no
test failure. This is exactly how the
`SlockAgentsApi.AgentActivityWireDto(val id: String)` drift (D1 of #50) was
introduced — the mock used `[{"id":"a1",...}]` instead of the upstream
`[{timestamp, entry: {kind, activity, detail}}]`.

When adding DTOs:

- If the field shape is verified, write a regression test that uses the
  canonical upstream payload (either copy-pasted from
  `third_party/slock-source/packages/server/...` or loaded from a JSON fixture
  that was round-trip-verified).
- If a real upstream fixture is not available, mark the DTO as "pending
  contract verification" in the test name and the source comment, and follow
  up before the task goes to In Review.
- Negative tests (`xxxRejectsGuessedWrappersAndAliases`) are still useful and
  should be retained.

### "Edit main" vs "push main" — distinct operations

- **Edit main** = creating new commits directly on the `main` branch. The
  rule is: do not do this. Every fix, refactor, or feature must come from a
  worktree branch off `main` (e.g. `fix/api-task50-d1-d2`,
  `chore/agents-md-workflow-discipline`).
- **Push main** = `git push origin main` to publish commits that already exist
  on local `main` to the remote. This is just a sync; it does not add new
  content, and is not a workflow violation. The previous rule "main ahead of
  origin/main is a stop sign" referred to commits *being created* on main,
  not to publishing accumulated work.

When pushing main, the canonical flow is: edit in a worktree branch, push the
branch, fast-forward `main` to that branch tip (`git checkout main && git merge
--ff-only <branch>` or `git push origin main` if `main` is already pointing
where the branch is), then push. Do not push a divergent / non-fast-forward
`main` to origin; stop and resolve the branch/main state before publishing.

### Non-interactive Git commands

- Prefer non-interactive Git invocations in agent sessions. For commands that
  may page output, set `GIT_PAGER=cat` or `PAGER=cat` instead of letting Git
  open `less`.
- If a rebase, cherry-pick, revert, or commit continuation could open an editor,
  set `GIT_EDITOR=true` for non-interactive continuation commands. For
  interactive rebase todo editing, set `GIT_SEQUENCE_EDITOR=true` only when the
  intended todo file requires no manual edits.
- Do not let agent work block inside vim or another terminal editor. If a
  command unexpectedly enters an editor, exit without changing the file and
  rerun with explicit pager/editor environment variables.

## Shared UI Component Discipline

- Product/page code must prefer the existing shared brutal components for
  common controls: `SlockBrutalActionButton`, `SlockBrutalIconButton`,
  `SlockBrutalTextField`, `SlockBrutalSearchField`, section labels, chips, and
  modal/action helpers. Do not hand-roll local back buttons, icon buttons,
  primary/secondary buttons, text fields, or search controls with ad hoc
  `Box`/`Row`/`clickable` styling when a shared component already covers the
  behavior.
- Local wrappers are acceptable only as semantic adapters, and should preserve
  shared component defaults for border, hard shadow, padding, typography,
  disabled behavior, and icon treatment unless the product spec explicitly
  calls out a variant. If a repeated variant is needed, promote it to a shared
  component instead of copying per-page styling.
- Iron rule: when React parity or Product feedback identifies a reusable
  control pattern (select/dropdown, textarea counter, clear action, refresh
  affordance, section label accessory, etc.), first add or extend a shared
  component under `compose/shared/src/commonMain/kotlin/ai/slock/compose/components/`
  and then wire pages to that component. Do not fix the same visual primitive
  separately inside each page; page-local UI is only allowed for one-off layout
  composition around shared controls.
- Members/detail pages in particular should use the shared brutal buttons for
  top-left back navigation and all action buttons. Do not reintroduce bespoke
  members-only button metrics such as custom 30dp icon buttons, 1dp shadows, or
  caption-sized action text for ordinary page actions.

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
- Cross-platform visual states must not rely on parent/view opacity when the
  intended result is a specific token color. Kuikly Compose emits
  `Modifier.alpha(...)` as an `opacity` prop; Android applies that through
  `View.alpha`, while OHOS applies it through ArkUI `NODE_OPACITY`. The final
  subtree blending can differ, especially for disabled buttons/icons on light
  backgrounds. Prefer explicit ARGB colors such as
  `AccentPink.copy(alpha = 0.3f)` and matching icon colors (`#4D000000`) for
  disabled/pressed states instead of wrapping the whole component in
  `Modifier.alpha(0.3f)`.
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
- Current `staging` may sit ahead of the latest public Kuikly tag. Treat the
  fork `git describe` as part of the dependency version evidence. For example,
  a build pinned to public `2.21.0` may still need fork artifacts from
  `2.21.0-33-gea0788c8` in mavenLocal.
- Current staging patches include font-family serialization/input fixes,
  reverse lazy-list behavior, and OHOS/Compose cancellation/runtime fixes that
  may not exist in the public Maven artifacts for the same release line.
- When a fix is accepted upstream and released as a new Kuikly version, update
  `KUIKLY_VERSION` in `compose/buildSrc/src/main/java/KotlinBuildVar.kt` and
  simplify any workaround code that the fix obsoletes (e.g. per-weight
  FontFamily dispatch in `SlockDesignTokens.kt`).
- To publish the fork locally for testing:
  ```bash
  cd /Users/artin/AndroidStudioProjects/Slock
  sh scripts/build_kuikly_fork.sh
  ```
  Do not run bare `:core-render-android:publishToMavenLocal` from inside the
  Kuikly checkout unless you also pass the Slock release coordinates. The
  default Kuikly Gradle properties publish `core-render-android` under a
  snapshot coordinate such as `2.0.0-2.1.21-SNAPSHOT`, while Slock consumes
  `com.tencent.kuikly-open:core-render-android` at the `kuiklyAndroid` version
  pinned in `compose/gradle/libs.versions.toml` (currently
  `2.21.0-2.1.21`). For Android-only republish during a local fix, use:
  ```bash
  cd /Users/artin/AndroidStudioProjects/Slock/third_party/kuikly-ui
  KUIKLY_VERSION=2.21.0 KUIKLY_KOTLIN_VERSION=2.1.21 \
    ./gradlew :core-render-android:publishToMavenLocal
  ```
  Then verify the MavenLocal AAR timestamp/hash under
  `~/.m2/repository/com/tencent/kuikly-open/core-render-android/2.21.0-2.1.21/`
  before rebuilding Slock.
- After publishing, Slock Android and OHOS Gradle builds pick up the forked
  artifacts from mavenLocal automatically (`mavenLocal()` is already in the
  repository config). Android Studio Run works without extra steps — only
  re-run the script after pulling fork changes (e.g. `git submodule update`).
- Do not use `includeBuild` for the fork. The KuiklyUI project includes
  androidApp/demo/ohosApp/h5App/iosApp in its settings, causing composite
  builds to configure the entire project tree on every Gradle sync and
  timing out.""

## OHOS Kuikly Workflow

- The Slock OHOS app bundle/module/ability is `ai.slock.compose` / `entry` /
  `EntryAbility`.
- `hdc` may not be on `PATH`; the default DevEco path in this workspace is
  `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc`.
- Do not assume global `hvigor` or `hvigorw` commands exist on this machine.
  Command-line OHOS builds should use DevEco Studio's bundled Node +
  `hvigorw.js` and bundled `ohpm`.
- The default DevEco installation path used by this project is
  `/Applications/DevEco-Studio.app/Contents`. Override it in scripts with
  `DEVECO_STUDIO_HOME` only when DevEco is installed elsewhere.
- To sync and assemble a HAP without relying on global `hvigorw`, run:

```bash
cd /Users/artin/AndroidStudioProjects/Slock/compose/ohosApp
/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm install --all
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw.js \
  --sync -p product=default --analyze=normal --parallel
/Applications/DevEco-Studio.app/Contents/tools/node/bin/node \
  /Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw.js \
  --mode module -p module=entry@default -p product=default \
  -p requiredDeviceType=phone assembleHap --analyze=normal --parallel
```

- To build, install, and launch the OHOS app, run the wrapper from the Compose
  project. It applies the same bundled DevEco toolchain path above, then installs
  the signed HAP on a connected target. The script is not guaranteed to have its
  executable bit set, so prefer invoking it through `sh`:

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

## Gradle Build Safety

- If Gradle compilation fails due to **cache corruption** or because the Gradle daemon
  is **locked/occupied by another process**, **stop immediately** and report the failure.
  Do **not** retry the build — retrying will not resolve cache or lock contention and
  wastes time.
- Before reporting, capture the exact error message and the Gradle task that failed.
  Let the owner or the blocking process holder decide the next step.

## Network Priority Workflow

- Every Android request that reaches `ai.slock.compose.network.SlockApiClient`
  must run inside `withSlockRequestPriority(...)`; missing priority is a bug.
- New product API routes live in `compose/shared` behind shared
  `@Serializable` request/response DTOs and `SlockNetworkClient`. Do not add
  parallel Android endpoint facades, app-local JSON parsers, `mapOf` request
  bodies, ad hoc `JsonObject`, or route-local anonymous body shapes.
- Shared `SlockNetworkClient` APIs must require explicit `DataRequestPriority`
  with no no-priority overloads/defaults, so new API/fake call sites fail to
  compile until they choose `Foreground`, `Visible`, or `Background`.
- Priority choice: `Foreground` is only for user-blocking opens/mutations;
  `Visible` is for on-screen refresh/pagination/retry; `Background` is for
  warmup, reconcile, polling, cached silent refresh, diagnostics, and preloads.
- OkHttp lanes stay separated per priority: Foreground `12`, Visible `8`,
  Background `4`. Do not collapse them into one client/dispatcher; background
  waits behind active or queued Foreground/Visible work.

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
- **Effect boundary**: hot `LaunchedEffect` / `snapshotFlow` work must be keyed by
  stable render-axis signatures, not whole payload/list objects or mutable flags
  that the effect itself writes. Every `snapshotFlow` used for scrolling, paging,
  visibility, or read markers must apply `distinctUntilChanged` (or an equivalent
  equality gate), and every boolean/state assignment inside the collector must
  be guarded by `if (old != new)`.
- **IO intent idempotency**: any intent that reaches Repository/DataSource/host IO
  from a hot UI path must be single-flight and marker-based in the Store. Repeated
  visibility/read/paging/refresh intents for the same route/entity marker must
  no-op or coalesce to the latest marker instead of issuing parallel requests.
- **Selector boundary**: UI must not subscribe to an entire shared store state when
  it only needs one route/channel/thread/member slice. Expose stable selectors or
  slice flows keyed by the exact scope the UI consumes, and put `distinctUntilChanged`
  / equality no-op gates on selector output. Realtime/cache updates may update the
  store globally, but unrelated slices must not cause the current page/composer to
  recompute or receive another candidate/status emission.
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

Do not duplicate the Compose API mapping here. The canonical source is
`.agents/rules/kuiklyComposeDSL.mdc`; read it before touching Kuikly Compose
code, especially for package imports, unsupported APIs/modifiers, TextField,
nested scroll, pull-to-refresh, animation, and lazy list/grid differences.

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

# 4. OHOS HAP (requires DevEco Studio; uses bundled hvigorw.js, not a global CLI)
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
