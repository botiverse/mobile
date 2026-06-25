# React vs KMP Alignment: Realtime Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Realtime

包含: socket connect / reconnect / realtime event delivery / state precedence / stale reconcile / native realtime / foreground notifications

## React source of truth

`packages/web/src/store/agentStore.ts`:
- `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 三个 reconcile 字段
- `updateActivity(agentId, activity, activityDetail, serverSeq, timestamp)` 带 serverSeq reconcile
- `resetActivitySeq()` on socket.connect
- `resolveAgentDisplayState(agent, activityState, isExternal)` 单一 resolution 函数

## KMP source

- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeEventMapper.kt:540` — wire `slockRealtimeEvent` → Activity
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SharedHomeStore.kt:1413` — realtime push 接收
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ChannelRepositoryDataSource.kt:554-590` — `MemberProfile.agentStatus` 增量更新
- `compose/androidApp/src/main/java/ai/slock/android/shared/AndroidAppScopeRealtimeOwner.kt` — native realtime bridge
- `compose/androidApp/src/main/java/ai/slock/android/shared/AndroidUnreadNotificationPoster.kt` — per-message notification

## Feature points inventory (06-23 → 06-26)

(22 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 10)

主要类别:
1. **Native realtime bridge** (8 commits): keepalive, retry after auth restore, retain rooms while paused
2. **Realtime projection** (6 commits): project through shared domain owner, update boundary reply count, gap recovery
3. **Stale handling** (4 commits): recover visible gaps from heartbeat seq, newer gaps sync
4. **Keepalive 开关** (2 commits): namespaced preference, gate behind setting (per #105 done)
5. **Notification 链路** (2 commits): post from background realtime, foreground realtime

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Realtime 推送走 `slockRealtimeEvent` → wire activity → `AgentStatus` push 到 member store
- Member status flow 走 member store (per `fix(status): scope agent status flows by member`)
- Agent status resolve from member store (per `fix(thread): project agent status from members store`)
- Foreground realtime notifications (per #103 done)
- Native realtime keepalive (per #105 done)
- Auth restore retry
- Rooms retained while paused

### ⚠️ 接近对齐 (P1)
- Realtime parent updates out of replies (`fix(thread): keep realtime parent updates out of replies`)
- Realtime visible when cache is stale
- Map realtime messages to owning channel
- Update boundary reply count from live replies
- Subscribe standalone realtime

### ❌ 仍未对齐 (06-26 仍 P0) — 核心架构问题
1. **缺 `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 三字段 reconcile** (per spec v2 #68 P0 #2)
   - 当前: REST snapshot 和 socket push 可能 race (REST 后到会覆盖 socket 新 state)
   - socket reconnect 后 seq 残留 → 新 socket push 应用不上
   - 修复: 加三字段 + `resetActivitySeq()` on socket.connect
   - 任务: #74 AD-4 in_progress
2. **Activity 数据驱动 vs standalone state** (Android-App-Integrator #386/#387/#388 in_progress)
   - 当前 KMP 还有 Activity 页面 standalone route 本地临时 state → 易漏 realtime
   - 修复: "global store receives realtime event → canonical cache/store update → Activity projection observes same identity update"
3. **Realtime 漏事件** (@AD-2 在分析 #418 in_progress)
   - 线程中收不到 realtime 数据
   - Channel 流渲染外部数据 (#417 done 一次但根因 #418 audit)
   - 给某个 thread reactions 先展示后瞬间消失
4. **Native realtime bridge cleanup** (Codex-KMP-Developer #321 todo)
   - 统一通过 NotifyModule 分发
   - 当前 bridge 自定义分发, 有 notification title/body 退化成数字 (per #102 #103 in_progress)
5. **Background realtime 通知链路** (per #103 in_progress, API-Integration-Developer owner)
   - notification title + body 退化成 "x unread" 数字
   - 修复: postUnreadNotification call 的 request title/body 不是数字

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- Native realtime app-scope owner 重构 (#370/#371 Cindy + Codex-Kuikly-MP in_progress)
- Foreground keep-alive notification switch (#105 done 但 #113 todo 分离)
- Bugly 集成 (per #111 done)

## P0/P1/P2 缺口分类

### P0 (本轮必修, 多个 in_progress)
1. Freshness reconcile (#74 AD-4, per spec v2 P0 #2)
2. Activity store 收敛 (#386/#387/#388 Android-App-Integrator)
3. Realtime 漏事件 (#418 Codex-Kuikly-MP audit + 修)
4. Native realtime bridge cleanup (#321 Codex-KMP-Developer)
5. Background realtime notification title/body (#102/#103 API-Integration-Developer)

### P1 (体验)
- Realtime parent updates out of replies (already done)
- Realtime visible when cache is stale
- Map realtime messages to owning channel
- Boundary reply count from live replies

### P2 (业务扩展 / 工程)
- Native realtime app-scope owner refactor (#370/#371)
- Foreground keep-alive notification switch (#113)
- Bugly 集成 (#111 done)

## Owner 拍 (下一步)

- (i) 等 #74 freshness reconcile + #418 audit + #386/#387/#388 store 收敛 + #321 bridge cleanup + #102/#103 notification 一波, 然后我跑 verify diff
- (ii) 分批 verify: #74 先, #386-#388 后
- (iii) 等 owner 拍

我倾向 (i): 一波完整 verify. 不下 fix decision.

PUX 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26