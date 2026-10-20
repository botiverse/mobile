# Kuikly Route Realtime Performance Checklist

Scope: Slock shared-shell route transitions, especially Home -> Thread, where
one Kuikly page hosts multiple product routes and realtime/cache work can keep a
background route hot.

This checklist was extracted from the OHOS Thread freeze investigation in
`#KMP` task #136. The failure mode was not a native crash: Thread first content
rendered, socket heartbeat continued, but the Kuikly thread stayed near a full
core because Home presence realtime kept reducing Home state and writing/reading
Home cache while Thread was active. (OHOS has since been frozen at 1.14.x and
removed from `main`; the principles below apply to the Android and iOS hosts.)

## Principles

- The active route owns the Kuikly render budget. Background routes may keep
  durable cache facts fresh, but they must not continuously emit UI state or
  trigger broad recomposition while another route is active.
- Realtime events need product priority, not a single shared "apply to every
  route immediately" path.
- Diagnostics should preserve enough data for Quiver/export and device analysis,
  but high-frequency facts must not flood `console.log`.
- Page-visible content is not sufficient acceptance for transition work.
  A route can appear correct while the render thread is pegged.

## Route Activation

For each route transition, decide which route is active and which routes are
background:

- Active route may update UI state, run route-local effects, render loading or
  content, and schedule route-specific cache writes.
- Background routes should prefer cache-only updates, dirty flags, or deferred
  coalesced refresh.
- Returning to a backgrounded route may flush queued facts once, then refresh
  normally.
- Do not keep Home presence, task joins, member enrichment, or file refreshes
  fully active just because the shared shell object is still alive.

Home -> Thread specific expectation:

- `message:new`, `message:updated`, and visible Thread membership/task facts can
  update Thread immediately.
- Home-only presence changes should be queued or cache-only while Thread is
  active.
- Heartbeats and realtime skip diagnostics should never cause route state
  emissions.

## Realtime Event Classification

Classify every realtime event before applying it to UI state:

- UI-critical for active route: apply immediately.
- UI-critical for another route: queue or mark dirty unless the other route is
  visible.
- Cache-only: write a deduped durable fact without emitting active UI state.
- Diagnostics-only: record telemetry, but do not touch route stores.

Examples:

- `message:new` for the open Thread: UI-critical active route.
- `presence_batch` while Thread is active: Home background; queue/cache-only.
- `write_realtime_skip type=heartbeat`: diagnostics-only.
- server membership deletion affecting current session: active shell/auth fact;
  handle immediately even if the visible route is Thread.

## Effect Key Discipline

Compose/Kuikly effects must be keyed by stable scalars, not whole state objects:

- Avoid `DisposableEffect(homeState)` or keys derived from full content
  snapshots.
- Use route id, server id, access token, feature flag, or explicit lifecycle
  nonce as keys.
- Use `rememberUpdatedState` to read the latest state inside long-lived
  callbacks when the callback identity should not restart.
- Make high-frequency callbacks idempotent; an equivalent event should no-op
  before state emission and before cache write-through.

Audit pattern:

```bash
rg -n "collectAsState|DisposableEffect|LaunchedEffect|rememberUpdatedState|bridge\\.log" \
  compose/shared/src/commonMain/kotlin/build/raft/app
```

Flag any effect that:

- restarts on a full payload/state object,
- reads/writes Home while Thread is active without a route guard,
- logs on every recomposition or repeated equivalent payload,
- performs cache write-through from a diagnostics-only event.

## Shared Store Reducer Rules

Shared stores should preserve the MVI no-op contract:

- Equivalent reducer output does not emit a new state object.
- Equivalent reducer output does not repeat durable cache write-through.
- Background low-priority facts do not create broad active-route state.
- Full snapshot replacement paths must be rare and justified; prefer typed
  semantic reducers and narrow payload patches.

Home/Thread acceptance:

- Irrelevant realtime for the inactive route must not change active route state.
- Presence batching must not repeatedly replace Home `Content` while Thread is
  visible.
- Returning to Home may emit one coalesced update if queued facts changed the
  visible Home projection.

## Cache And Write-Through

High-frequency realtime is allowed to update durable cache only when it is
deduped and bounded:

- Avoid read-full-snapshot -> write-full-snapshot loops for each presence batch.
- Write only after reducer acceptance or explicit cache-only dedupe acceptance.
- Use route/session keys so background queue flushes cannot apply to stale
  servers or stale Thread ids.
- Batch or rate-limit cache-only presence/freshness writes.
- Keep cache diagnostics separate from render-critical UI logs.

## Logging Policy

Host bridge logging must separate persistent diagnostics from console noise:

- Default `bridge.log` should record through diagnostics storage/export.
- `console.log` should be limited to errors, warnings, key lifecycle boundaries,
  and low-frequency summaries.
- High-frequency diagnostics should not mirror to console by default:
  `write_realtime_skip`, `presence_batch`, `read_home`, `write_home`,
  `shared_*_cache`, heartbeat skip, and repeated equivalent no-ops.
- Device debugging should use explicit summary tags rather than dumping every
  bridge log line.

Audit pattern:

```bash
rg -n "bridge\\.log|console\\.log" \
  compose/shared/src/commonMain/kotlin/build/raft/app
```

## Performance Instrumentation

Kuikly page-level metrics and business route logs must be correlated:

- Android already has Kuikly performance callbacks wired through
  `SlockKuiklyPerf`.
- Add route business logs around the transition:
  `home_open_conversation`, `thread_start`,
  `thread_persistent_cache_result`, `thread_first_render`,
  `thread_refresh_result`, `thread_tasks_join_start`,
  `thread_tasks_join_result`.
- Do not call Kuikly `PerformanceModule.getPerformanceData()` from hot
  recomposition paths. Trigger it once from an event boundary if a snapshot is
  needed.
- During device validation, correlate platform logs (logcat / iOS unified
  log), per-thread CPU, Kuikly perf logs, and business route logs.

## UI Layer Batch Audit

Prioritize these surfaces:

- `SettingsHomePage.kt`: shared shell state collection, Home realtime effect
  keys, active Thread route guard, realtime dispatch, app badge effect, and
  Home/cache diagnostics.
- `ThreadPage.kt`: Thread store state collection, route startup effects, task
  join effects, render/performance snapshots, files effects, auto-scroll
  effects, and repeated render logs.
- `RaftHomeComponents.kt`: Home render/refresh effects, pull-refresh timers,
  server sort effects, and `home_render` logging frequency.
- `MembersPage.kt`: member detail refresh effects and activity timeline effects,
  especially payload/list-size keyed effects.
- Settings subpages and panels: `SettingsAccountPage.kt`,
  `SettingsComputersPage.kt`, `SettingsBillingPage.kt`,
  `SettingsStoragePage.kt`, `SharedInboxPanel.kt`, and
  `ThreadComposerPage.kt` render logs keyed by full payloads.

For each hit, record:

- key expression,
- whether it can restart during unrelated realtime,
- whether it writes cache or emits UI state,
- expected behavior when the route is inactive,
- verification log or test that proves equivalent/noisy input no-ops.

## Compile And Verification Gates

Keep platform compile fixes separate from performance fixes:

- Source-set/API compile repairs, such as platform `Clock` actuals, belong to their
  own task and commit.
- Performance patches should not silently absorb unrelated compile fixes.
- Final acceptance must run on the final pushed-main shape or a clearly stated
  local commit stack that includes all prerequisites.

Minimum verification for a Home -> Thread performance fix:

```bash
cd compose
./gradlew --no-daemon --console=plain :shared:compileDebugKotlinAndroid :shared:compileKotlinIosSimulatorArm64
```

Then on a device:

- enter Thread from Home,
- confirm first content renders,
- confirm Kuikly thread does not stay near a full core,
- confirm no Home `presence_batch` cache storm while Thread is active,
- confirm the platform log is not flooded by high-frequency bridge logs.
