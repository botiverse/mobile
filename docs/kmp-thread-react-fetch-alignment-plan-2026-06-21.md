# P0 React alignment — work plan (Codex-Kuikly-KMP专家)

> Status: planning / pre-implementation
> Author: @Codex-Kuikly-KMP专家
> Spec: `artifacts/product/kmp-thread-react-fetch-alignment-spec-2026-06-20.md` v2 (owner handoff from @KMP-专家)
> Worktree: `/Users/artin/AndroidStudioProjects/Slock/Slock-p0-react-alignment` (branch `slock-task-p0-react-alignment`, base `dd215e3f`)

## Goal

P0 React alignment Subtasks A-E land in main so that Activity → Thread (and Search → Thread / Saved → Thread / Task → Thread) no longer error out with `thread_parent_mismatch`, and the KMP fetch path matches React's "parent channel messages + thread summary overlay" model.

## Subtask breakdown (mapped to spec v1)

### A. Repository: `getThread(threadId)` → `getReplyThread(parentChannelId, parentMessageId)`

**Files to touch**:
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ConversationRepositoryDataSource.kt` — add `getReplyThread(parentChannelId, parentMessageId, ...)`; delete `getThread(threadId)`
- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedThreadStore.kt` — 2 call sites (`fetchInitialThreadSnapshot` line 1305, `fetchResolvedReplyThreadSnapshot` line 1344)
- `compose/shared/src/commonTest/kotlin/build/raft/app/data/ConversationRepositoryDataSourceTest.kt` — add tests for new signature; delete `getThread` tests

**Implementation outline**:
1. `getReplyThread` flow:
   - resolve `threadChannelId` via `createOrOpenThread(parentChannelId, parentMessageId)` (already exists, returns `threadChannelId`)
   - call `networkClient.channelMessages(parentChannelId, ...)` (existing endpoint) — verify if server supports `?threadId=...` filter
   - If server doesn't support filter: filter client-side on `message.parentMessageId == parentMessageId`
   - Build `ThreadSnapshot` from response (existing `buildSharedThreadSnapshot`)
2. Update 2 `SharedThreadStore` call sites: pass `parentChannelId` + `parentMessageId` instead of `threadId`
3. `ThreadSnapshot.toThreadRouteId()` (in `ThreadCandidateProofGate.kt:181+`) needs update — current builder assumes `conversation.thread` exists; new builder assumes snapshot is from parent channel so parent pair comes from `base`, thread channel from `createOrOpenThread` response

**Dependencies**:
- @API-Integration-Developer verifies `GET /messages/channel/{parentChannelId}?threadId=...` server support
- Falls back to client-side filter if server doesn't support (spec v2 risk 1)

### B. Delete `matchesReplyThreadParent` + same-source checks + fallback cleanup

**Files**:
- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedThreadStore.kt` — delete `matchesReplyThreadParent` (line 2305-2317), delete same-source check at line 1311 + 1348, clean `toKuiklyThreadPayload` fallback chain (line 3183-3252)
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ThreadCandidateProofGate.kt` — re-audit `ThreadSnapshot.toThreadRouteId()` & `ThreadFallbackProof.resolve()`
- Tests: `ThreadPayloadTest` + `ThreadCandidateProofGateTest` + `SharedThreadStoreTest`

**Implementation outline**:
1. Delete `matchesReplyThreadParent` private function
2. Delete the `if (!basePayload.hasUntrustedReplyThreadStreamHint() || initialResult.data.matchesReplyThreadParent(basePayload))` branch — replace with direct `DataResult.Content(basePayload to initialResult.data)` (snapshot IS the parent channel, no check needed)
3. Delete same-source check in `fetchResolvedReplyThreadSnapshot` line 1348
4. `toKuiklyThreadPayload` cleanup: remove `base.parentChannelId ?: conversation.thread?.parentChannelId?.value` fallback chain — just use `base.parentChannelId` directly (subtask A ensures it's always non-null on Thread kind)
5. Add dev `require(base.parentChannelId != null && base.parentMessageId != null)` only on `ThreadRouteId.ReplyThread` branch (not on Channel / FocusedThread kinds)
6. Re-audit `ThreadFallbackProof.resolve()` (line 220+): after P0, cache content is from parent channel, so fallback source matching still works but simplify where possible
7. Update tests:
   - `ThreadPayloadTest`: replace v0.2 negative fixture (test was: `appShellActivityThreadItemKeepsStreamUnresolvedWhenThreadStreamIsMissing`) — this fixture now passes because we don't check, so test must assert direct thread open succeeds with same payload
   - `ThreadCandidateProofGateTest`: assert new invariant "parent is the queried channel"
   - `SharedThreadStoreTest`: add cases for `getReplyThread` flow + remove `matchesReplyThreadParent`-based cases

### C. Persistent cache redesign

**Files**:
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/ComposeLocalDataStore.kt` (or similar) — bump cache version + change cache key shape
- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedThreadStore.kt` — update cache write/read paths

**Implementation outline**:
1. Bump cache version constant → invalidate old cache (one-time slowness on first launch after deploy)
2. Change cache key from `threadId` to `(parentChannelId, parentMessageId)`
3. Update cache write/read paths
4. Verify cold restart: open a previously-cached thread refetches from network, then writes new cache
5. Realtime cache invalidation: change invalidation key to `(parentChannelId, parentMessageId)` to match

### D. Realtime path replacement

**Files**:
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/SlockRealtimeHandler.kt` (or similar) — replace `message:new` filter
- `compose/shared/src/commonTest/kotlin/...` — add unit tests for filter logic + integration smoke

**Implementation outline**:
1. Replace thread channel filter with parent channel + `parentMessageId == expectedParent` filter
2. Add unit test: filter rejects messages with `parentMessageId != expectedParent`
3. Add unit test: filter accepts messages with `parentMessageId == expectedParent`
4. Integration smoke: open thread, send reply from another client, verify it appears in panel <1s

### E. Code gate + integration smoke

**Owner**: @Android-Developer-1 (reviewer)
**Focus**:
- #263/#266 thread chrome / origin preview no regression
- Visual regression: thread panel layout matches React baseline
- Full matrix smoke: Activity / Search / Saved / Task entry all open thread correctly
- `v0.2` 负面 fixture + #275 regression all pass

## Boundary audit (carry-over from #266/#274 review)

Per my earlier review (5 boundary items in #KMP thread):
1. `ThreadFallbackProof.resolve()` (line 220-318) is same-source as `matchesReplyThreadParent` — re-audit in subtask B
2. `ThreadCandidateProofGate.prove()` (line 87+) sealed proof gate — `ThreadSnapshot.toThreadRouteId()` needs update in subtask A
3. `toKuiklyThreadPayload` fallback chain (line 3183-3252) — covered in subtask B
4. `createOrOpenReplyThreadStream` (line 1350+) — still needed (POST returns `threadChannelId`); repositions in subtask A flow
5. `SharedThreadStore.fetchInitialThreadSnapshot` vs `fetchResolvedReplyThreadSnapshot` — subtask B simplifies `fetchInitialThreadSnapshot` (cache hit no longer needs proof-gate repeat)

## Validation gate

Each subtask must pass before merge:
- `:shared:testDebugUnitTest --tests "*SharedThreadStoreTest*" --tests "*ThreadCandidateProofGateTest*" --tests "*ThreadPayloadTest*" --tests "*SharedInboxPanelTest*" --tests "*SharedActivityStoreTest*" --tests "*SharedHomeSearchSavedStoreTest*" --tests "*ConversationRepositoryDataSourceTest*"`
- `:shared:compileDebugKotlinAndroid` ✅
- `:shared:compileKotlinOhosArm64` ✅
- `:androidApp:compileDebugKotlin` ✅
- `:androidApp:compileConnectedTestAndroidTestKotlin` ✅
- `git diff --check HEAD~1 HEAD` clean
- `git diff --check` clean

Real-device smoke (manual):
- Activity → Thread (clean install)
- Search → Thread (clean install)
- Saved → Thread (clean install)
- Task → Thread (clean install)
- Send reply from another device, verify open thread panel receives message <1s
- Cold restart with previously-cached thread (refetch works)
- v0.2 负面 fixture (`appShellActivityThreadItemKeepsStreamUnresolvedWhenThreadStreamIsMissing`) — still passes (now asserts different invariant)
- #275 regression (`threadActionsRequireExplicitThreadChannelId`) — still passes

## Push gate

Per @Cindy 02:44 对账:
- `git fetch upstream main` 0 behind
- tracked clean
- owner gate clear (this PR)
- worktree clean

## Worktree commit strategy

Each subtask is a separate commit, sequenced:
- `feat: add getReplyThread to ConversationRepository` (subtask A)
- `refactor: SharedThreadStore.fetchInitial/FetchResolved use parent pair` (subtask A part 2)
- `refactor: delete matchesReplyThreadParent + same-source checks` (subtask B)
- `refactor: cleanup toKuiklyThreadPayload fallback chain` (subtask B part 2)
- `refactor: ThreadSnapshot.toThreadRouteId from parent channel snapshot` (subtask B part 3, requires ThreadCandidateProofGate update)
- `feat: cache key (parentChannelId, parentMessageId) + bump version` (subtask C)
- `refactor: realtime message:new filter to parent channel` (subtask D)
- `test: add #266/#274 boundary contract tests` (subtask B regression)
- `docs: AGENTS.md "Kuikly References And Skills" — typography hoist / resolved-style cache / ThreadMessageCard marker wrap 教训`

## Estimate

3.5-6 days (per spec v1 / @artin "half-week to one week").

## Open dependencies

- @API-Integration-Developer: server `?threadId=...` filter support verification
- @KMP-专家: review AGENTS.md "Kuikly References And Skills" 措辞 (subtask B boundary re-audit)
- @Android-Developer-1: Phase 4 code gate after all subtasks landed

## Not in scope

- P0 hotfix (cancelled per @artin)
- P1 (Domain 3-6: state / composer / realtime smoke / read receipt) — separate PR
- Other thread-related contracts (origin preview anchor, focus message scroll) — separate tasks
- (B) sealed `InboxItem` migration — separate task (owner @Android-App-Integrator, deferred until #280 fully done)
