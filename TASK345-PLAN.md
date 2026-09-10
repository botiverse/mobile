# Task #345 — implementation plan (Mobile Tasks pagination/summary)

Worktree: `worktrees/task345`, branch `codex/task345-tasks-summary` (from `origin/main`).
Wire contract (pinned from merged Web PRs botiverse/slock #7189 + #7202): see `/tmp/task345-wire-contract.md`.

## Layers and exact seams (verified in this worktree)

1. **Transport** — `build.raft.app.data.SharedSlockNetworkClient.request(priority, path, method, query: List<Pair<String,String>>, serverIdOverride, bodyText)`. **Supports query params already.**
2. **API surface** — `TasksDataSource.kt`:
   - `NetworkSlockTasksApi.serverTasks(serverId, priority)` → `request(priority, "tasks/server", serverIdOverride)`.
   - `SlockTasksApi.serverTasks(serverId, priority)` interface + fakes/other impls (`TasksDataSource.kt:472`, `:617`).
3. **Parser** — `TaskParser.kt` (`parseSharedServerTasksPayload`) + `TasksDataSource.kt`: `@Serializable TasksListEnvelope(val tasks: List<TaskWireDto>)` (`:1014`), `TaskWireDto` (`:1048`), `toTaskSummary` (`:1072`).
4. **Store** — `SharedTasksStore.kt` (list state, refresh, socket merge).

## Change plan

1. **Transport/API**: add `serverTasksPage(serverId, priority, detail: String?, limit: Int?, cursor: String?): HomeNetworkResponse` building `query = listOf("detail" to "summary", "limit" to "200") + cursor`. Keep the existing no-arg `serverTasks` (legacy usage) untouched.
2. **Parser**: extend the server-tasks envelope with `next_cursor: String? = null`. Add summary projection parsing that accepts the summary key set and maps into `TaskSummary` (map `hasDescription`/`descriptionBytes`; `description` absent). Do NOT use `ignoreUnknownKeys` alone as the guarantee — lock the accepted keys with a test.
3. **Store**: add `serverTasksNextCursor`/`hasMore`; on load-more follow `next_cursor`, **append/merge by task id (no reset, no dup)**; socket updates merge into the same list without duplicating or resetting; fail-open on error (keep existing items, don't clear).
4. **400 handling**: treat `400 {error:"Invalid cursor"}` as "stop paging / refresh from head", matching the Web consumer.

## Tests to add (with right-cause mutations)

- cursor disabled → paging stops after page 1 → RED.
- `detail` missing → parser falls back / multipart semantics → RED.
- socket merge of an existing id → must not duplicate or reset → RED.
- summary item with `description` present must be rejected/mapped per contract.
- fail-open: page 2 error keeps page 1 items.

## Evidence required before review

Android/iOS/OHOS compile + consumer (real/emulator) evidence, Hosted terminal, independent review.

## Blocked/deferred

- **Step (b) production readback**: DO NOT touch TestBed `0d278dd5` (@artin R2). Use @artin's session or `testbed acquire` an independent device. Non-blocking for implementation.
