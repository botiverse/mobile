# Thread First-Open Performance Attribution

> **Obsolete (2026-10-20):** Kuikly-era investigation snapshot; the attributed render path was replaced by Compose Multiplatform. Kept for history only.

Date: 2026-06-22
Owner: KMP / Kuikly shared
Status: Investigation note

This note records the current Thread first-open root-cause split from task #202.
It is meant to prevent future perf work from conflating Android host traversal,
Kuikly renderer work, shared Compose first composition, repository work, and
profiler visibility.

## Artifacts

Perfetto:

```text
/private/tmp/slock-task202-perfetto-20260622-1558/thread_open3.perfetto-trace
```

Scratchpad:

```text
/private/tmp/slock-task202-perfetto-20260622-1558/thread_open3.perfetto-trace_analysis.md
```

Profiler sample used for profiler-limit comparison:

```text
/tmp/slock-task202-profiler/profiler_report.json
/tmp/slock-task202-profiler/profiler_frames.jsonl
```

The profiler sample is a Home/Tasks/Settings-oriented capture, not a clean
Thread-only capture. It is still useful for proving what the profiler can and
cannot see.

## Layer Model

Thread first-open needs four separate layers:

| Layer | Evidence Source | What It Can Explain | What It Cannot Explain |
| --- | --- | --- | --- |
| Android app main thread | Perfetto, JankStats, `thread_host_frame`, `thread_host_traversal` | `Choreographer`, `traversal`, View measure/layout/draw, Kuikly container time, jank deadline miss | Shared composable function names unless explicit trace sections are added |
| Kuikly Android renderer | Perfetto slices under main traversal/layout/draw | `RV OnLayout`, `Record View#draw`, `Compose:recompose`, `Compose:applyChanges`, `AndroidOwner:onMeasure` inside renderer layout | Which Slock composable caused a renderer subtree without profiler/source correlation |
| Kuikly Compose thread (`HRContextQueueH`) | Perfetto thread slices, Kuikly recomposition profiler | Shared Compose first composition/recomposition, applyChanges, source locations when profiler is enabled | Android View traversal/draw and native unsliced layout time |
| Slock shared / data / bridge code | Business markers, logcat, explicit `Trace.beginSection`, profiler source locations | Route state, Thread payload phases, composer/list/action-card/markdown boundaries, repository/cache/realtime timing | Native renderer cost unless host markers are joined |

The important rule: a jank frame can be host-heavy even when shared profiler
events are small, and it can be shared-heavy even when main thread only shows
generic renderer slices. Treat single-plane conclusions as suspect.

## Current Perfetto Split

The capture has two Slock Activity app-deadline misses:

| Frame | FrameTimeline | Main Slice | Key Main Cost |
| --- | ---: | ---: | --- |
| Loading first render | 120.520ms | `Choreographer#doFrame 35101480` 113.337ms | `traversal=99.665ms`, `layout=66.832ms`, `slock_kuikly_container_layout=66.295ms`, `dispatchDraw=27.223ms` |
| Content commit | 92.994ms | `Choreographer#doFrame 35101572` 75.118ms | `layout=47.759ms`, `slock_kuikly_container_layout=47.459ms`, `dispatchDraw=15.594ms` |

Thread state proves this is not mostly I/O, Binder, or lock wait:

| Window | Main Running | Main Runnable/Waiting Signal |
| --- | ---: | --- |
| Loading frame | 108.745ms / 113.337ms | small runnable/sleeping tail |
| Content frame | 71.862ms / 75.118ms | small runnable/sleeping tail |

## What Main Thread Is Doing

The Android main thread is the app UI thread. In this capture, the heavy named
work is not arbitrary Slock business code running directly on main. The visible
main-thread tree is:

```text
Choreographer#doFrame
  traversal
    measure
      slock_kuikly_container_measure
    layout
      slock_kuikly_container_layout
        RV OnLayout
          Compose:recompose / Compose:applyChanges / AndroidOwner:onMeasure
    draw
      Record View#draw()
        slock_kuikly_container_dispatchDraw
```

`slock_kuikly_container_*` comes from `RaftTraceFrameLayout`, which wraps
the Kuikly container's Android `onMeasure`, `onLayout`, and `dispatchDraw`.
Inside that container the renderer uses RecyclerView/View traversal and Kuikly
Compose node work. The capture therefore says:

- Main is mostly Android/KuiKly renderer traversal, layout, and draw.
- Shared state/repository/network work is not the direct main-thread owner of
  these two frames.
- Slock shared code still indirectly creates the renderer tree shape, so the
  next fix must reduce what the first loading/content commits ask the renderer
  to lay out.

Without explicit app trace sections, Perfetto will not name Slock composable
functions inside `RV OnLayout`. It only shows renderer-level names.

## Loading Frame Attribution

Loading `RV OnLayout` is 63.772ms. Its named descendants are many small Compose
passes, not one expensive row bind:

| Descendant | Count | Total | Max |
| --- | ---: | ---: | ---: |
| `Compose:recompose` | 20 | 24.110ms | 1.854ms |
| `Compose:applyChanges` | 20 | 4.421ms | 0.544ms |
| `Compose:onRemembered` | 20 | 2.458ms | 0.210ms |
| `AndroidOwner:onMeasure` | 10 | 2.051ms | 0.410ms |
| `RV CreateView` / `RV OnBindView` | 1 / 1 | 0.007ms / 0.008ms | tiny |

The direct named child sum is 34.002ms, leaving 29.770ms of unsliced native /
renderer layout time inside `RV OnLayout`.

`HRContextQueueH` overlaps the loading frame with `Recomposer:recompose` and
`Compose:applyChanges`:

| Window | HRContextQueueH Running | Apply/Recompose Signal |
| --- | ---: | --- |
| Pre-loading recomposition | 107.260ms | starts before first main frame |
| Loading main frame | 107.779ms | `Compose:applyChanges` overlaps by 76.694ms |
| Content main frame | 10.111ms | mostly no longer driving the frame |

Interpretation:

- Loading jank is a coupled cold first-mount cost: the Kuikly Compose thread is
  applying the initial tree while main simultaneously lays it out and draws it.
- JIT/GC/class verification amplify the cold path, but are not the whole root.
  For example, pre-loading `Input/TextField` verification was 78 slices totaling
  5.857ms.
- `RV CreateView`/`RV OnBindView` is not the problem by itself.

## Content Frame Attribution

Content `slock_kuikly_container_layout` is 47.459ms. The main cost is two
`RV OnLayout` roots:

| Root | Duration | Named Shape |
| --- | ---: | --- |
| `RV OnLayout` id=11777 | 13.048ms | 3 small `Compose:recompose` slices, 7.775ms unsliced direct time |
| `RV OnLayout` id=11815 | 30.269ms | 7 small `Compose:recompose` slices, 19.116ms unsliced direct time |

`HRContextQueueH` only runs 10.111ms during this frame, so content jank is mostly
main-thread layout/draw of the real content commit. Continuing to optimize only
background recomposition or repository work will miss this frame.

## Source Clue: Loading Gate Is Too Low

The current lightweight loading shell is inside `ThreadPageContent`:

```kotlin
if (payload.status == "loading" && payload.originStatus == "none") {
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        ThreadStateMessage("Loading thread...", null)
    }
    return
}
```

However, the parent route composable already builds composer-related state
before that call:

- `renderedPayload.resolveThreadComposer()`
- member/channel suggestion item lists
- `composerState`
- `composerSurface`
- `ThreadRouteUiState`

The footer composer render is hidden later with `payload.status != "loading"`,
but the loading route can still touch composer/input model classes before first
render. That matches the class verification signal for `ThreadComposerPage`,
`BasicTextField`, `CoreTextField`, and related input/focus classes.

## What Kuikly Profiler Can See

The Kuikly recomposition profiler can answer:

- which shared composable was first-composed or recomposed;
- source locations for tracked composables;
- whether a slow frame is mostly `noScope` first composition or repeated scope
  recomposition;
- parameter/state churn when the profiler records it.

It cannot answer by itself:

- Android `Choreographer` deadline misses;
- `slock_kuikly_container_layout` / `dispatchDraw`;
- RecyclerView `RV OnLayout` unsliced native time;
- RenderThread/GPU scheduling;
- JIT/GC/system thread interference;
- app main-thread host traversal if no shared composable is being recomposed.

The `/tmp/slock-task202-profiler` sample shows this limitation clearly. Slow
frames such as 176ms, 135ms, and 111ms were almost entirely `noScope`
first-composition events. Component durations were mostly small and sourceful,
while the frame wall time was much larger. That is useful attribution: it says
"first composition / route mount / host traversal", not "one repeated
recomposition bug".

For Thread first-open, a clean profiler capture still needs:

1. Reset profiler immediately before Home -> Thread open.
2. Export `profiler_report.json` and `profiler_frames.jsonl`.
3. Collect matching `SlockPerf` logcat and, for host slices, Perfetto.
4. Join slow profiler frames to `thread_first_render`,
   `thread_payload_ready`, and `thread_first_frame`.

## Next Fix Boundary

The next code change should stay narrow:

1. Move the loading gate above composer/suggestion/files/tasks route-state
   construction, or split the Thread scaffold so loading does not touch
   composer/input/action-card/markdown machinery.
2. Stage content ready into two frames:
   - first content frame: header + list/message rows;
   - later frame: composer/footer/action cards/markdown extras.
3. Add explicit trace sections around:
   - `resolveThreadComposer` / `composerState`;
   - `ThreadMessagesList`;
   - action-card rendering;
   - markdown rendering;
   - content staging boundaries.

This turns the next Perfetto from "renderer layout bucket" into source-level
attribution.

## Queries Worth Reusing

Direct frame roots:

```sql
SELECT id, ts / 1e6 AS ts_ms, dur / 1e6 AS dur_ms, name
FROM slice
WHERE name IN (
  'Choreographer#doFrame 35101480',
  'Choreographer#doFrame 35101572'
);
```

Main-thread render tree:

```sql
WITH roots(id, label) AS (
  VALUES (9559, 'loading'), (11691, 'content')
),
d AS (
  SELECT r.label, s.*
  FROM roots r
  JOIN descendant_slice(r.id) s
)
SELECT
  label,
  id,
  parent_id,
  depth,
  ROUND(ts / 1e6, 3) AS ts_ms,
  ROUND(dur / 1e6, 3) AS dur_ms,
  name
FROM d
WHERE
  parent_id IN (9559, 11691)
  OR name GLOB 'slock_kuikly_container_*'
  OR name IN (
    'RV OnLayout',
    'RV OnBindView',
    'RV CreateView',
    'Compose:recompose',
    'Compose:applyChanges',
    'AndroidOwner:onMeasure',
    'traversal',
    'measure',
    'layout',
    'draw',
    'Record View#draw()'
  )
ORDER BY label, ts, depth;
```

HRContextQueueH overlap:

```sql
WITH roots(id, label) AS (
  VALUES (9559, 'loading_main_frame'), (11691, 'content_main_frame')
),
w AS (
  SELECT label, ts AS start_ts, ts + dur AS end_ts
  FROM roots JOIN slice USING (id)
),
h AS (
  SELECT w.label, s.*
  FROM w
  JOIN slice s ON s.ts < w.end_ts AND s.ts + s.dur > w.start_ts
  JOIN thread_track tt ON s.track_id = tt.id
  JOIN thread t ON tt.utid = t.utid
  WHERE t.name = 'HRContextQueueH'
)
SELECT
  label,
  ROUND(SUM(
    CASE WHEN name = 'Compose:recompose'
    THEN MIN(ts + dur, end_ts) - MAX(ts, start_ts)
    ELSE 0 END
  ) / 1e6, 3) AS compose_recompose_overlap_ms,
  ROUND(SUM(
    CASE WHEN name = 'Compose:applyChanges'
    THEN MIN(ts + dur, end_ts) - MAX(ts, start_ts)
    ELSE 0 END
  ) / 1e6, 3) AS apply_overlap_ms,
  ROUND(SUM(
    CASE WHEN name GLOB 'VerifyClass*'
    THEN MIN(ts + dur, end_ts) - MAX(ts, start_ts)
    ELSE 0 END
  ) / 1e6, 3) AS verify_overlap_ms
FROM h JOIN w USING (label)
GROUP BY label;
```
