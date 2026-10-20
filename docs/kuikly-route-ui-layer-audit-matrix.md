# Kuikly Route UI Layer Audit Matrix

> **Obsolete (2026-10-20):** Kuikly/OHOS route audit plan; both were removed from `main`. Kept for history only.

Baseline: `origin/main` at `67747e49`, after `6a654fc8` / #137 was moved
to review. This matrix is the execution plan for the broader UI-layer audit
requested after the OHOS Home -> Thread freeze investigation.

Reference checklist: `docs/kuikly-route-realtime-performance-checklist.md`.

## Split Of Work

- #137 owner: finish fix validation for the known Home presence storm. Remaining
  proof is OHOS device HDC/top/hilog validation.
- #138 owner: product/acceptance matrix, risk ordering, owner suggestions, and
  evidence requirements. No implementation changes.
- #139 suggested owner: code-level audit of shared-shell effect keys, realtime
  dispatch, cache write-through, and bridge logging hot paths. Do not patch in
  the audit unless a follow-up task is explicitly split and claimed.

## Audit Command

Use this as the initial scan:

```bash
rg -n "collectAsState|DisposableEffect|LaunchedEffect|rememberUpdatedState|bridge\\.log|console\\.log|SlockOhosDiagnostics.record" \
  compose/shared/src/commonMain/kotlin/build/raft/app \
  compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets
```

For each hit, record:

- key expression,
- restart driver,
- whether the effect can run while the route is inactive,
- whether it emits UI state or writes cache,
- log frequency and OHOS console behavior,
- proof required before marking it safe.

## Matrix

| Priority | Surface | Current Signal | Risk To Audit | Suggested Owner | Acceptance Evidence |
| --- | --- | --- | --- | --- | --- |
| P0 | #137 Home -> Thread validation | #137 now gates Home presence while Thread is active and filters high-volume OHOS console logs. | Fix is compiled, but still needs device proof. | #137 owner plus whoever has OHOS device access. | From Home enter Thread; first content renders; Kuikly thread does not stay near full core; no sustained `presence_batch -> read_home/write_home`; hilog console is not flooded by high-volume bridge logs. |
| P0 | `SettingsHomePage.kt` realtime observer | Observer key is now stable (`pager`, stores, mapper) and dynamic route/session values use `rememberUpdatedState`. `homeViewModel.dispatch(ApplyRealtimeEvent)` still receives all decoded shell realtime. | Confirm Home store classifies inactive-route realtime as cache-only/queued/no-op, not broad Home UI state. | #139 code audit. | SharedHomeStore tests proving inactive Thread presence does not emit Home UI state or repeat full cache writes; HDC proof from #137. |
| P0 | OHOS `KRBridgeModule.log` | `SlockOhosDiagnostics.record` remains default; `console.log` is allowlisted and `isHighVolumeBridgeLog` filters `write_realtime_skip`, `presence_batch`, `read_home`, `write_home`, `shared_home_cache`, and `shared_thread_cache`. | Confirm allowlist does not include high-frequency summaries by another name, and diagnostics export still keeps enough evidence. | #139 code audit. | HDC hilog sample: no high-volume bridge flood; Settings diagnostics/export still contains relevant route events. |
| P1 | `ThreadPage.kt` route effects | `thread_first_render`, `thread_content_gate`, task join, files, and perf snapshot are route-keyed, but `LaunchedEffect(renderedPayload)` logs `thread_render` from the full rendered payload. | Full-payload render logging can become noisy on every message/task/files mutation. It should not mirror to OHOS console at high frequency. | #139, with Thread owner for follow-up if needed. | Log sample for active Thread under realtime load; no console flood. If noisy, narrow key to scalar summary or downsample. |
| P1 | `RaftHomeComponents.kt` Home render log | `LaunchedEffect(payload)` logs `home_render` for full Home payload. | On active Home this can be acceptable as summary, but realtime presence/cache churn must not cause repeated logs. | #139 / Home owner. | Home realtime presence burst does not produce repeated `home_render` unless visible projection actually changes. |
| P1 | `MembersPage.kt` member detail effects | `LaunchedEffect(selectedMemberId, payload.members)` watches the full members list and can clear selection if the selected id disappears. Agent detail refresh has narrower keys. | Full-list key may restart during unrelated presence/member ordering updates. | #139 / Members owner. | Presence-only updates do not reset selected member/detail or refetch agent detail unnecessarily. |
| P1 | Settings subpages and panels | `SettingsAccountPage`, `SettingsComputersPage`, `SettingsBillingPage`, `SettingsStoragePage`, `SharedInboxPanel`, and `ThreadComposerPage` contain payload-keyed render logs/effects. | Mostly lower-frequency settings routes, but full-payload render logs should not become a general pattern for realtime-heavy pages. | Page owner after #139 triage. | For each page, render log key is scalar or low-frequency; no repeated console logs from unchanged payload-equivalent updates. |
| P2 | Generic handlers (`RaftBackHandler`, `BasicWidget`) | Use `rememberUpdatedState` for callbacks and stable pager/effect keys. | Low current risk; keep as positive pattern. | No follow-up unless regression appears. | No action. |
| P2 | Performance instrumentation | OHOS `KuiklyViewDelegate.performanceMonitorTypes()` now returns LAUNCH, FRAME, and MEMORY; callbacks log `SlockKuiklyPerf`. | Data is page-level, not route-level. It must be correlated with business route logs. | #137 validation owner / future perf owner. | HDC sample includes both `SlockKuiklyPerf` and business logs such as `home_open_conversation`, `thread_first_render`, and `thread_tasks_join_*`. |

## Follow-Up Split Rule

If #139 confirms a real problem, split follow-ups by independent surfaces:

- P0 route shell / realtime classification defects.
- P0 OHOS bridge logging defects.
- P1 Thread render/effect logging defects.
- P1 Home render/effect logging defects.
- P1 Members detail refresh defects.
- P2 Settings page render-log cleanup.

Do not combine platform compile fixes, #137 route storm fixes, and unrelated UI
log cleanup in one task unless the same failing test or HDC proof requires it.

## Review Gate

#138 is complete when:

- the checklist and this matrix are present in `docs/`,
- #139 has a clear code-audit owner request,
- #137 remains the known-fix validation track,
- no implementation files are modified by the matrix work,
- `git diff --check` passes.
