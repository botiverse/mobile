# Kuikly Profiler Surface Audit Spec

Date: 2026-06-22
Owner: KMP / Kuikly shared
Status: Draft

## Problem

Slock now has Kuikly recomposition profiler infrastructure that can export
`profiler_report.json` and `profiler_frames.jsonl`. The Home/Tasks tab-switch
investigation proved that profiler data is useful, but also showed a trap:
slow frames are not automatically recomposition bugs. In that case the report
showed repeated `noScope` first composition, and the correct fix was a tab
retention policy change, not a reducer or data-source rewrite.

We need a repeatable spec for using KuiklyProfiler to audit other high-value
surfaces: Thread conversation flow, Tasks, and Settings. Each audit must produce
evidence that separates:

- real shared recomposition/state churn;
- normal first composition, lazy item entry, or route mount cost;
- Android/OHOS host render, view traversal, or Kuikly container sync cost;
- repository/network/cache/realtime work that only appears as UI jank.

## Goals

- Standardize capture, export, parsing, and reporting for Slock surfaces.
- Make every result comparable across Home, Thread, Tasks, Settings, and
  Members.
- Require focused windows: reset profiler immediately before the scenario.
- Pair profiler frames with Slock business markers and host frame diagnostics.
- Avoid false conclusions from high `recompositionCount` when the events are
  mostly `noScope` or sub-millisecond.
- Turn each audit into either a scoped fix task or an explicit "host/runtime
  attribution needed" result.

## Non-Goals

- Do not redesign KuiklyProfiler APIs in this spec.
- Do not make profiler enabled by default for production users.
- Do not treat profiler output alone as final jank attribution.
- Do not add broad UI rewrites without first proving a component/state owner.

## Surfaces

Audit in this priority order:

1. Thread conversation flow
   - Home -> Thread open
   - Thread message list first render
   - New message / realtime apply
   - Composer focus and send
   - Files, task panel, and member modals if visible in the flow
2. Tasks
   - Tasks tab entry after Home
   - Filter changes
   - Task status update
   - Open task thread
3. Settings
   - Settings tab entry
   - About -> Diagnostics
   - Server settings
   - Danger-zone modal
   - Storage / Computers / Billing pages
4. Home follow-up scenarios
   - Search / saved / inbox panels
   - Server switcher
   - Notification center
5. Members
   - Members tab entry
   - Agent detail
   - Activity timeline

## Capture Contract

Each capture must record one scenario and one scenario only.

Required steps:

1. Install the candidate APK/HAP.
2. Open Settings -> About -> Diagnostics.
3. Enable Recomposition profiler.
4. Tap Reset scene window immediately before starting the scenario.
5. Run the scenario with a documented input script or exact manual steps.
6. Return to Diagnostics and tap Export report.
7. Pull `profiler_report.json` and `profiler_frames.jsonl`.
8. Pull host logs from the same window.
9. Save the scenario metadata beside the artifacts.

Android pull commands:

```bash
OUT=/tmp/slock-profiler-<surface>-<yyyymmdd-hhmm>
mkdir -p "$OUT"
adb -s <device> shell run-as ai.slock.android cat \
  cache/KuiklyProfiler/profiler_report.json > "$OUT/profiler_report.json"
adb -s <device> shell run-as ai.slock.android cat \
  cache/KuiklyProfiler/profiler_frames.jsonl > "$OUT/profiler_frames.jsonl"
adb -s <device> logcat -d > "$OUT/logcat.txt"
```

For repeatable Android captures, use the repository adb helper instead of
hand-typing individual adb commands:

```bash
python3 scripts/slock_android_adb.py --device <device> get-app-state \
  --out "$OUT" \
  --prefix <surface>-before
python3 scripts/slock_android_adb.py --device <device> click <x> <y>
python3 scripts/slock_android_adb.py --device <device> scroll --direction up
python3 scripts/slock_android_adb.py --device <device> capture-logcat \
  --out "$OUT" \
  --prefix <surface> \
  --filter-regex 'SlockPerf|SlockPerfScene|tab_switch_frame|Choreographer|Skipped|jank|Exception|FATAL'
python3 scripts/slock_android_adb.py --device <device> pull-profiler \
  --out "$OUT" \
  --package ai.slock.android
```

The helper prints JSON and `get-app-state` returns the `screenshot_path` and
`uixml_path`, so those paths should be copied into `scenario.md`.

OHOS collection should use the existing collector:

```bash
HDC_BIN=/path/to/hdc ./scripts/collect_kuikly_performance_attribution.sh \
  --out artifacts/kuikly-performance/<surface>-<yyyymmdd-hhmm>
```

The collector checks both profiler locations:

```text
/data/storage/el2/base/haps/entry/cache/KuiklyProfiler/
/data/storage/el2/base/haps/entry/files/KuiklyProfiler/
```

## Scenario Metadata

Every artifact directory must include `scenario.md`:

```markdown
# Scenario

Surface: Thread | Tasks | Settings | Home | Members
Device: emulator-5560 | Pixel 9a | nova9a | ...
Build: <git short sha>, debug/release, APK/HAP path
Profiler: enabled from Settings Diagnostics, reset at <timestamp>
Steps:
1. ...
2. ...

Expected user-visible result:
- ...

Companion logs:
- profiler_report.json
- profiler_frames.jsonl
- logcat.txt / hilog.txt
- optional screenshot/video
```

## Analysis Rules

Use the Kuikly recomposition analyzer workflow:

1. Health check
   - `totalFrames < 30`: mark data as thin.
   - `totalRecompositions == 0`: report no shared recomposition evidence.
   - Record `filteredNames` and `filteredPrefixes`.
2. Report pass
   - List top 20 non-`noScope` components by `recompositionCount`.
   - Include every component with `recompositionCount > 50`.
   - Include any component with `maxDurationMs > 10`.
   - Include components with repeated scope hits over 5.
   - Include parameter changes over 90%, but only after checking whether the
     value really changed or a new equal-value instance was passed.
3. Frame pass
   - Parse `profiler_frames.jsonl` line by line.
   - Do not parse JSONL as a single JSON file.
   - Mark slow frames over 16 ms.
   - Mark hotspot frames over 50 recomposed events.
   - Split `frame`, `touch_context`, and `scroll_context` lines.
4. Source confirmation
   - For each real suspect, locate `sourceLocation`.
   - Identify who writes the state, not only who reads it.
   - Explain why the state changes repeatedly.
   - State whether the affected readers all need to update.
5. Host correlation
   - Align slow profiler frames with `SlockPerf` / `SlockPerfScene` /
     `tab_switch_frame` / route markers.
   - If profiler event time is low but host frame time is high, mark the issue
     as host/container attribution, not shared recomposition.
   - For Thread first-open, use
     `docs/thread-first-open-performance-attribution.md` as the current
     layer model and query reference before assigning ownership.

## Interpretation Rules

Treat these as normal unless the surrounding evidence proves otherwise:

- `noScopeRecompositions == recompositionCount`: first composition, not
  repeated recomposition.
- Lazy list item first appearance during scroll.
- Route first mount after intentionally disposing a tab/pane.
- Icon/vector first creation when the route was not mounted before.

Treat these as suspicious:

- Same scope recomposes repeatedly within one click/touch window.
- Same state lists many readers and drives multiple unrelated components.
- Full screen state object changes while the visible projection is equivalent.
- Parameter changes are near 100% because callers create new equal-value
  objects each render.
- Slow frames line up with realtime/cache/network logs rather than visible user
  actions.

## Required Report Shape

Each audit must produce `kuikly-profiler-<surface>-<yyyymmdd-hhmm>.md` with:

- scenario metadata;
- data overview;
- top 20 non-`noScope` components by count;
- high-count low-cost components that are still worth watching;
- normal first-composition list;
- slow frame table;
- host correlation table;
- findings sorted by impact;
- fix candidates or explicit "needs host/container proof";
- validation plan for the next patch.

Data overview must include frame counts without percentages:

```markdown
| Metric | Value |
| --- | ---: |
| Total frames | 96 |
| Total recompositions | 2276 |
| Slow frames >16ms | 33 |
| Hot frames >50 events | 12 |
| Components | 57 |
```

## Surface-Specific Checklists

### Thread Conversation Flow

Capture windows:

- Home -> open channel/thread
- Thread visible -> refresh completes
- Receive or send one message
- Focus composer and type a short message
- Open files/task/member panel if relevant

Must correlate:

- `thread_start`
- `thread_visible_payload`
- `thread_first_render`
- `thread_refresh_result`
- `thread_tasks_join_start/result`
- composer focus/send markers
- realtime batch logs
- cache read/write logs

Likely owners:

- `ThreadPage.kt`
- `SharedThreadStore.kt`
- `SharedThreadComposerDraftStore.kt`
- `ThreadComposerPage.kt`
- `SharedThreadFilesStore.kt`
- `ConversationRepositoryDataSource.kt`

Acceptance:

- Thread first visible frame has no unexplained >16 ms shared recomposition
  burst.
- Refresh does not replace an equivalent loaded timeline with broad state churn.
- Composer focus does not recompose the full thread list.
- Realtime events that do not affect visible Thread content do not emit broad
  UI state.

### Tasks

Capture windows:

- Home -> Tasks tab
- Tasks -> Home -> Tasks hot return
- Filter change
- Status update
- Task row -> Thread open

Must correlate:

- `tasks_screen_enter`
- `tasks_refresh_start/skip/result`
- `tasks_first_render`
- status update logs
- bottom tab markers

Likely owners:

- `SharedTasksPage.kt`
- `TasksDataSource.kt`
- `SettingsHomePage.kt` app shell tab retention
- task-related thread open path

Acceptance:

- Empty task list is treated as a completed fresh snapshot.
- Hot return does not start network refresh unless stale or dirty.
- Filter/status update recomposes only header/filter/body rows that changed.
- Opening a task thread preserves loaded origin metadata.

### Settings

Capture windows:

- Home -> Settings tab
- About -> Diagnostics
- Diagnostics profiler start/reset/export
- Server settings entry
- Danger-zone modal open/confirm/cancel
- Storage/Computers/Billing page entry

Must correlate:

- settings route destination changes
- diagnostics profiler action state
- storage/about/diagnostics host capability calls
- modal show/dismiss actions
- bottom tab markers

Likely owners:

- `SettingsHomePage.kt`
- `SharedSettingsStore.kt`
- `BridgeAuth.kt`
- Android/OHOS settings capability bridges

Acceptance:

- Settings destination changes do not recompose retained Home/Tasks panes.
- Profiler controls do not create broad app-shell recomposition after export.
- Modal open/close cost is attributed to modal subtree or host Modal layer, not
  the whole Settings shell without proof.

## Fix Decision Matrix

| Evidence | Likely fix path |
| --- | --- |
| High same-scope recomposition, state has many readers | Split state or derive narrower projection |
| High parameter-change rate from new equal-value objects | Cache/canonicalize caller inputs with correct keys |
| High `noScope` on hot return | Retain or prewarm only the needed pane/subtree |
| Low profiler duration, high host frame duration | Add host/container markers or investigate Kuikly render |
| Network/cache logs align with slow frames | Move work out of visible frame or no-op equivalent writes |
| Scroll context with item first appearance | Usually normal; optimize only if per-item cost is high |

## Task Creation Rule

Do not create one broad "optimize page" task from a profiler report. Create
small tasks by owner:

- shared reducer/state task;
- shared composable identity/stability task;
- host/container attribution task;
- repository/cache/realtime task;
- visual/interaction validation task.

Each task must cite:

- artifact directory;
- frame ids or component rows;
- affected source file and owner;
- expected validation command or replay script.

## Validation Gates

Minimum local validation for a shared KMP fix:

```bash
cd compose
./gradlew -c settings.gradle.kts :shared:testDebugUnitTest --tests <focused-test>
./gradlew -c settings.gradle.kts :androidApp:assembleDebug
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64
```

Minimum runtime proof:

- rerun the same scenario on the same device;
- compare before/after profiler summary;
- compare before/after host frame markers;
- report any cold-start frames separately from hot interaction frames.

## Open Questions

- Whether Android should get a small collector script mirroring the OHOS
  collector for profiler + logcat + metadata.
- Whether profiler start/reset/export should have an adb-accessible debug
  command for automation, instead of relying on Settings UI taps.
- Whether Slock should add a standard `scenarioId` marker so profiler, logcat,
  screenshots, and visual tests can be joined without timestamp heuristics.
