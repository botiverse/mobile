# Slock Android Agent Notes

## Kuikly AI Skills

- Workspace-level Kuikly guidance lives under `.codex/`:
  - `.codex/skills/kuikly-*` contains the vendored skills from
    `Tencent-TDS/KuiklyUI-AI`.
  - `.codex/rules/kuiklyComposeDSL.mdc` and `.codex/rules/kuiklyDSL.mdc` contain
    the Kuikly Compose / Kuikly DSL rules.
  - `.codex/kuikly-ui-ai-source.txt` records the upstream source and commit.
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
- `SlockApi` methods must take an explicit `SlockRequestPriority` parameter. Do not
  add no-priority overloads or defaults. This is the compile-time tripwire: new API
  methods and fake implementations should fail to compile until the caller chooses
  Foreground, Visible, or Background.
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
