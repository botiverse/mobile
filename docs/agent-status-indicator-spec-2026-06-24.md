# Agent Status Indicator 现状与对齐 Spec (v2)

日期：2026-06-24 (v2 修订)
任务：#members专修 task #68
触发：@artin 19:55 "agent status indicator 是订阅的 members store 吗，status 严格对齐 react 吗，请分析写一个 spec"
review：@Android-Developer-4 task #72
负责人：@Product-UX-Designer (spec lane)

## v2 修订 (vs v1 2026-06-23)

v1 spec 经 @Android-Developer-4 review (task #72) 后修订：

1. **删 "External 进 AgentStatus enum" P0** — v1 把 external agent 当 activity enum 一员是错的。React `StatusDot external=true` 是 component-level override，外部 agent 走独立 `AgentDisplayState.isExternal` + `fetchExternalAgentStatus` 路径。KMP 不污染 enum。
2. **删 "30s UI stale indicator" P1** — v1 写错了。React `agentStore` 的 `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 是 state precedence (防 stale REST 覆盖新 socket push)，不是 UI 30s 斜线标记。KMP 应该按 React 数据结构对齐 reconcile，不是 UI 时间戳。
3. **Idle / Disconnected framing 改** — v1 把这两个当作 "React parity 缺口"。React `AgentActivity` 只有 5 状态，Idle/Disconnected 是 KMP wire fallback。要么简化合并、要么 owner 拍 "KMP 业务扩展"。不再用 React parity 名义强推 BrutalIdle。
4. **新增 P0: status resolution 一致性** — v1 漏掉的真 bug: KMP color 用 `member.agentStatus.membersStatusColor` (snapshot)，pulse 用 `member.membersResolvedAgentStatus(summary...)` (summary-driven)。同一个 status dot 颜色和脉冲走不同 source，必须统一到单一 resolved status。
5. **Square shape 移到 cleanup** — v1 列为 P2 parity task，实际是死代码，不应混 parity。
6. **size 改 per-call-site audit** — v1 写全局 AvatarSize 9 → 11dp 太激进；React `StatusDot size` 三档 (sm/md/lg) 是 per-call-site 选择的。KMP 应 per-call-site audit Members 实际渲染点。

## TL;DR (v2)

- **真 P0 #1**: status resolution 一致性 — color + pulse 都走 `resolvedStatus`，补测试覆盖 summary.activity 优先级。
- **真 P0 #2**: activity freshness reconcile — `agentActivityObservedAt / agentActivityVersions / agentActivitySeq` 对齐 React (state precedence，不是 UI stale)。
- **P1 #3**: External agent display-state — 独立路径，不污染 `AgentStatus` enum。
- **P1 #4**: Members per-call-site size + token audit — 不改全局 AvatarSize。
- **P2 #5**: Idle / Disconnected 业务扩展 vs 简化 — owner 拍。
- **Cleanup #6**: Square shape 死代码 + token 命名统一。

## 1. 现状

### 1.1 React `AgentDisplayState` 单一 resolution (关键参考)

文件：`third_party/slock-source/packages/web/src/store/agentStore.ts:154-185`

```typescript
export function resolveAgentDisplayState(
  agent: Pick<Agent, "status"> | null | undefined,
  activityState: AgentActivityState | null | undefined,
  isExternal?: boolean,
): AgentDisplayState {
  if (isExternal) {
    return {
      activity: "online",     // <-- 注意 external 不走 status 判定
      activityDetail: "",
      activityText: "External",
      isOnline: false,
      isExternal: true,
    };
  }
  const activity = activityState?.activity
    ?? (agent?.status === "active" ? "online" : "offline");
  const activityDetail = activityState?.activityDetail
    ?? (agent?.status === "stopped" ? "Stopped" : "");
  return {
    activity,
    activityDetail,
    activityText: getActivityText(activity, activityDetail),
    isOnline: activity !== "offline",
  };
}
```

**关键设计**：
1. **External 是独立分支** — 不是 `AgentActivity` enum 一员，是 `AgentDisplayState.isExternal` boolean。
2. **activity 优先级**: `activityState.activity` (realtime) > `agent.status === "active" ? "online" : "offline"` (snapshot)
3. **activityDetail 优先级**: `activityState.activityDetail` > `agent.status === "stopped" ? "Stopped" : ""`
4. **isOnline**: `activity !== "offline"`（无 intermediate 状态）
5. **activityText**: 走 `getActivityText(activity, detail)` 统一格式化（"Working: <detail>" / "Error: <error_message>" / "Stopped: won't receive..."）

### 1.2 React freshness reconcile 字段

文件：`agentStore.ts:189-219`

```typescript
agentActivityObservedAt: Record<string, number>;  // 上次观察 timestamp
agentActivityVersions: Record<string, number>;    // 单调递增 mutation counter
agentActivitySeq: Record<string, number>;         // 上次 server seq
```

配合 `updateActivity(agentId, activity, activityDetail, serverSeq, timestamp)`:
- serverSeq > 当前 seq → 应用 + 递增 seq
- serverSeq < 当前 seq → 丢弃（防 stale 覆盖）
- serverSeq === undefined → 旧 server 兼容，总是应用

socket.connect 时 `resetActivitySeq()` 清掉，让 reconnect 后 snapshot 重新建立基线。

### 1.3 KMP `AgentStatus` 7 状态 (现状)

文件：`compose/shared/src/commonMain/kotlin/ai/slock/compose/model/SlockModels.kt:118-150`

```kotlin
enum class AgentStatus {
    Online, Working, Thinking, Idle, Disconnected, Offline, Error;
    // ...
    companion object {
        fun fromWireValue(value: String?): AgentStatus? =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "online", "active", "available", "connected" -> Online
                "working", "running", "busy" -> Working
                "thinking" -> Thinking
                "idle", "away" -> Idle
                "disconnected", "not_connected", "notconnected" -> Disconnected
                "offline", "inactive" -> Offline
                "error", "failed", "failure" -> Error
                else -> null
            }
    }
}
```

7 状态 vs React 5 状态。KMP 多 `Idle` (away/away) + `Disconnected` (not_connected) 两个 wire fallback。

### 1.4 KMP `membersStatusColor` + `membersResolvedAgentStatus` 双链路 (P0 真 bug)

文件：`MembersPage.kt:3997-4007` + `MembersPage.kt:4039-4043`

```kotlin
// 颜色: 用 member.agentStatus (snapshot, 不走 summary)
private fun AgentStatus?.membersStatusColor(presence: Presence): Color = ...

// Pulse: 用 member.membersResolvedAgentStatus (summary 优先)
private fun AgentStatus?.membersStatusPulsing(): Boolean = ...

// 渲染: MembersAvatar + MembersAgentStatusIndicator 都传这两个
fun MembersAvatar(...) {
    color = member.agentStatus.membersStatusColor(member.presence)  // snapshot
    pulsing = member.agentStatus.membersStatusPulsing()              // snapshot
}

fun MembersAgentStatusIndicator(member, summary) {
    color = membersResolvedAgentStatus(summary?.status, summary?.activity)?.let { ... }
    statusPulsing = member.membersResolvedAgentStatus(summary?.status, summary?.activity).membersStatusPulsing()
}
```

**Bug**: `MembersAvatar` (列表 row / sidebar / activity log) 用 `member.agentStatus` (snapshot) 取 color + pulse，但 `summary.activity` (realtime) 变了时，pulse 走 `membersResolvedAgentStatus`，color 不走。结果: dot 颜色跟 snapshot 走，pulse 跟 realtime 走 → 不一致。

### 1.5 KMP 订阅链路 (已知)

- `RealtimeEventMapper.kt:540-545` 把 wire `activity` event 解析成 `AgentStatus` → `agentStatus` 字段
- `SharedHomeStore.kt:1413` 接收 realtime push → 增量更新 `conversation.agentStatus`
- `ChannelRepositoryDataSource.kt:554-590` 合并 `agentStatus` 到 `MemberProfile`
- `MembersPage` 通过 `membersResolvedAgentStatus(summary.status, summary.activity)` 读

**缺**: 没有 `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` reconcile 字段。socket reconnect 后 REST snapshot 可能覆盖新 realtime state。

### 1.6 KMP `SlockStatusIndicator` 渲染

文件：`SlockStatusIndicator.kt:1-100`

- Canvas 自绘 circle / square
- AvatarSize 9dp / InlineSize 10dp / ServerUnreadSize 10dp
- BorderColor = BorderBlack, BorderWidth = Thin
- Pulsing 时 alpha 0.28f ring (1150ms infinite)
- `SlockStatusIndicatorShape.Square` 是死代码分支 (无 call site)

### 1.7 React `StatusDot` 渲染

文件：`StatusDot.tsx:55-79`

- Tailwind `bg-brutal-*` + `border border-black` + `animate-pulse`
- Size: sm 8dp / md 10dp / lg 11dp
- `external` prop: 强制 `bg-brutal-cyan`
- `pulse` prop: 加 `animate-pulse`（独立于 activity mapping）

## 2. 差距 (v2)

### 2.1 状态机差距

| 状态 | React `AgentActivity` | KMP `AgentStatus` | 评价 |
|---|---|---|---|
| online | ✅ | ✅ | 对齐 |
| thinking | ✅ | ✅ | 对齐 |
| working | ✅ | ✅ | 对齐 |
| error | ✅ | ✅ | 对齐 |
| offline | ✅ | ✅ | 对齐 |
| idle (KMP only) | ❌ | ✅ (Idle + `away` wire alias) | ⚠️ **简化候选**: KMP 可合并 `Idle` → `Online` (semantic: agent idle = online 但未活动) 或保留为 "KMP 业务扩展" |
| disconnected (KMP only) | ❌ | ✅ (Disconnected + `not_connected` wire alias) | ⚠️ **简化候选**: KMP 可合并 `Disconnected` → `Offline` |

### 2.2 真 P0 #1: status resolution 不一致 (color + pulse 双链路)

**位置**: `MembersPage.kt:3997-4007` `MembersAvatar` / `MembersAgentStatusIndicator`

**症状**:
- `MembersAvatar` (列表 row / sidebar / channel members) color 走 `member.agentStatus.membersStatusColor` (snapshot)
- `MembersAgentStatusIndicator` (agent detail header) color 走 `membersResolvedAgentStatus(summary?.status, summary?.activity)` (summary 优先)
- pulse 在不同位置走不同 source
- 结果: realtime `activity` event 推 working → dot pulse 变 (走 summary), 但 color 不变 (走 snapshot)

**期望 (per React `resolveAgentDisplayState`)**:
- KMP 应该有单一 `resolveMemberDisplayState(member, summary)` 函数
- 返回 `AgentDisplayState { activity, activityDetail, activityText, isOnline }`
- 所有调用点都走这个函数取 color + pulse + text

### 2.3 真 P0 #2: 缺 activity freshness reconcile (React parity)

**位置**: `SharedHomeStore` / `ChannelRepositoryDataSource` / `MemberRepositoryDataSource`

**症状**:
- 没有 `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 字段
- REST snapshot (`loadAgents()`) 可能 race socket push (`agent:activity`)
- socket reconnect 后 REST snapshot 覆盖新 socket push
- realtime seq 错乱时不会拒绝 stale

**期望 (per React `agentStore`)**:
- KMP 加 `agentActivityObservedAt: Map<agentId, Long>`
- 加 `agentActivityVersions: Map<agentId, Long>` (单调递增)
- 加 `agentActivitySeq: Map<agentId, Long>` (server seq)
- wire activity push 携带 `serverSeq` (新 wire 协议), old server 兼容
- socket.connect 时 `resetActivitySeq()`
- reconcile 逻辑: serverSeq > 当前 seq 应用, < 丢弃

### 2.4 P1 #3: External agent display-state 路径缺失

**位置**: `MembersPage.kt` (整个 Members entry)

**症状**: KMP 没看到 `agent.external` / `runtime` 区分, `AgentStatus` enum 没 `External`。React:
- `StatusDot external=true` → 强制 `bg-brutal-cyan`
- `AgentStatusBadge` external 分支: 5min 内 lime, 30min 内 cyan, waiting_for_login gray
- `fetchExternalAgentStatus(agentId)` API

**期望 (per React)**:
- KMP 不污染 `AgentStatus` enum
- 加独立 `MemberDisplayState.isExternal: Boolean` 字段
- external agent 不走 `AgentStatus`, 走 `ExternalAgentStatus` (mock 初始, 后续接 API)
- `MembersAvatar` / `MembersAgentStatusIndicator` 渲染时检查 `isExternal`, 走独立 tone 路径

### 2.5 P1 #4: Members per-call-site size + token audit

**位置**: `MembersPage.kt` 多个调用点

**症状**: 
- v1 建议全局 AvatarSize 9 → 11dp 太激进
- React StatusDot 三档 (sm/md/lg) 是 per-call-site 选择
- KMP AvatarSize 9dp 在某处 (detail summary) 显式传 12dp 覆盖, 说明 call site 已有差异化
- 应该 per-call-site audit, 看每个渲染点用 React 对应 surface 的 size 是否对

**期望**:
- 列出 MembersPage 所有 `MembersAvatar` / `MembersAgentStatusIndicator` 调用点
- 对照 React 同 surface (sidebar / detail / activity log / channel members / mention hover card / agent list row)
- 确认 size + token 名 + pulse 行为

### 2.6 P2 #5: Idle / Disconnected 业务扩展 vs 简化 (owner 拍)

**两种 framing**:
- **(a) 简化**: KMP Idle → Online + Disconnected → Offline, 跟 React 5 状态对齐. Wire 解析: `idle/away` 走 Online, `disconnected/not_connected` 走 Offline. **优点**: 跟 React parity, 减少状态. **缺点**: wire 中间态丢失 (e.g. agent 临时 "disconnected" 但 server 仍 "active" 显示成 "online", 反之亦然)
- **(b) 业务扩展**: KMP 保留 7 状态, Idle/Disconnected 跟 Online/Offline 视觉一致 (lime/gray) 但语义不同. **优点**: 业务更精细. **缺点**: 用户视觉无法区分

PUX 倾 (a) 简化, 但 owner (@artin / @Cindy) 拍.

### 2.7 Cleanup #6: Square shape 死代码 + token 命名

- `SlockStatusIndicatorShape.Square` 是 enum 分支, 当前没 call site. 删除.
- Token 命名统一 (AccentLime → BrutalLime 等) per #187 Token hardcode cleanup 路线, 不应该混 parity task.

## 3. 接 gap 路径 (v2)

### P0 (本轮必修)

1. **Status resolution 一致性** (`resolveMemberDisplayState`)
   - 文件: `MembersPage.kt:3997-4007` + 调用点
   - 抽 `private fun MemberProfile.resolveMemberDisplayState(summary?): AgentDisplayState`
   - 返回 `AgentDisplayState(activity: AgentStatus, activityDetail: String, activityText: String, isOnline: Boolean)`
   - `MembersAvatar` + `MembersAgentStatusIndicator` 全部改用这个, color + pulse 都从 `displayState` 取
   - 测试: `MembersAgentStatusIndicatorTest` 覆盖 summary.activity 优先级 (realtime 优先于 snapshot)
   - owner: @Android-Developer-1/2/3

2. **Activity freshness reconcile** (state precedence)
   - 文件: `SharedHomeStore.kt:1413` + `ChannelRepositoryDataSource.kt:554-590`
   - 加 `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 字段
   - 配合 realtime mapper 检查 wire `serverSeq` + `timestamp`
   - socket.connect 时 reset seq
   - 测试: stale socket push 不覆盖新 REST snapshot, 反之亦然
   - owner: @Codex-Kuikly-KMP专家 (per #68 review boundary 他接)

### P1 (体验 / 业务扩展)

3. **External agent display-state 独立路径**
   - 文件: `SlockModels.kt` + `MembersPage.kt`
   - 加 `MemberDisplayState.isExternal: Boolean`
   - 不污染 `AgentStatus` enum
   - external agent 不走 `AgentStatus`, 走独立 `ExternalAgentStatus` (mock 初始)
   - owner: @Android-Developer-1/2/3 + @API-Integration-Developer (外部 status API)

4. **Members per-call-site size + token audit**
   - 文件: `MembersPage.kt` 全 Members 调用点
   - 列渲染点 → 对照 React 同 surface → 修 size + token 名
   - 不改全局 AvatarSize token (per-call-site)
   - owner: @Android-Developer-1/2/3

### P2 (owner 拍 + 简化候选)

5. **Idle / Disconnected 简化**
   - 选项 (a) KMP 简化 7 → 5 状态 (Idle → Online, Disconnected → Offline)
   - 选项 (b) 保留 7 状态但加 BrutalIdle 中间色
   - owner 拍
   - owner: @Android-Developer-1/2/3 (实现, 待 owner 拍)

### Cleanup (低优先级)

6. **Square shape 删除 + Token 命名统一**
   - 删除 `SlockStatusIndicatorShape.Square` 分支
   - 走 #187 Token hardcode cleanup (单独任务, 不混 parity)
   - owner: @Android-Developer-1/2/3

## 4. 验收标准 (v2)

### 4.1 status resolution 一致性 (P0 #1)

| 场景 | 期望 |
|---|---|
| summary.activity = "working" + member.agentStatus = "Online" | dot 黄色 + pulse (跟 realtime 一致) |
| summary.activity = "thinking" + member.agentStatus = "Working" | dot 黄色 + pulse (thinking 优先) |
| summary.activity = null + member.agentStatus = "Online" | dot 绿色 + 无 pulse |
| summary.activity = "online" + member.agentStatus = "Offline" | dot 绿色 (realtime 覆盖 snapshot) |

测试: `MembersAvatar` 和 `MembersAgentStatusIndicator` 在以上 4 种场景下 color + pulse 必须一致。

### 4.2 Activity freshness reconcile (P0 #2)

| 场景 | 期望 |
|---|---|
| REST `/agents` 返回 stale + socket push serverSeq > REST | socket push 应用 |
| REST `/agents` 返回新 + socket push serverSeq < REST | REST 优先 |
| socket reconnect + seq 残留 + 新 socket push seq=1 | 新 push 应用 (seq reset) |
| 旧 server (无 serverSeq) + 新 socket push | push 应用 (兼容) |

测试: `SharedHomeStoreTest.agentActivitySeqReconcile` 覆盖以上 4 种。

### 4.3 External agent display-state (P1 #3)

| 场景 | 期望 |
|---|---|
| `agent.external = true` + managed runtime | `isExternal=true`, 走独立 cyan/lime/gray tone (per setup/lastActivityAt) |
| `agent.external = false` | 正常 `AgentStatus` 流程 |

### 4.4 Members per-call-site audit (P1 #4)

| KMP 调用点 | React 同 surface | size 期望 | token 期望 |
|---|---|---|---|
| `MembersAvatar` (列表 row) | Sidebar `AgentListRow` | md 10dp | BrutalLime |
| `MembersAvatar` (头像叠加) | MessageItem avatar overlay | lg 11dp | BrutalLime |
| `MembersAvatar` (inline text) | MentionHoverCard | sm 8dp | BrutalLime |
| `MembersAgentStatusIndicator` (agent detail) | AgentDetailPanel | md 10dp | BrutalLime/Yellow/Orange/Gray |
| `MembersAgentActivityRow` | AgentActivityLog | sm 8dp | BrutalLime/Yellow/Orange/Gray |

## 5. Owner 建议 (v2)

| 任务 | Owner |
|---|---|
| Status resolution 一致性 (P0 #1) | @Android-Developer-1/2/3 |
| Activity freshness reconcile (P0 #2) | @Codex-Kuikly-KMP专家 |
| External agent display-state (P1 #3) | @Android-Developer-1/2/3 + @API-Integration-Developer |
| Members per-call-site audit (P1 #4) | @Android-Developer-1/2/3 |
| Idle/Disconnected 简化 (P2 #5) | @Android-Developer-1/2/3 (待 owner 拍) |
| Square shape 删除 + Token 命名 (Cleanup #6) | @Android-Developer-1/2/3 |

## 6. 扫描依据

React 源 (v2 新增):
- `third_party/slock-source/packages/web/src/store/agentStore.ts:154-185` (resolveAgentDisplayState)
- `third_party/slock-source/packages/web/src/store/agentStore.ts:189-219` (freshness reconcile 字段)
- `third_party/slock-source/packages/web/src/store/agentStore.ts:734-749` (useAgentDisplayState hook)
- `third_party/slock-source/packages/web/src/components/agent/AgentDetailPanel.tsx:468` (isExternalAgent runtime check)
- `third_party/slock-source/packages/web/src/components/agent/AgentDetailPanel.tsx:506-650` (external setup tab logic)

KMP 源 (v2 新增):
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt:3997-4007` (双链路 bug)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt:4039-4043` (resolved status)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt:1413` (realtime 接收)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ChannelRepositoryDataSource.kt:554-590` (agentStatus 增量更新)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockStatusIndicator.kt` (Canvas 自绘)

## 7. 后续维护

- 本 spec 维护节奏: 跟 `docs/react-kmp-feature-map-2026-06-23.md` 一致, 每 3 天或每 30+ commit 刷一次
- status resolution 一致性 (P0 #1) 是 hidden bug, 影响所有 Members 渲染入口
- activity freshness reconcile (P0 #2) 是 React parity 必做, 跟 KMP shared store 重构撞, 走 @Codex-Kuikly-KMP专家 lane
- external agent / per-call-site audit / Idle/Disconnected 都是次级, owner 拍优先级
- PUX stay product lane, 不下 fix decision, 不写 code. spec 是产品分析输出.

owner: @Product-UX-Designer
date: 2026-06-24 (v2)
status: revised after @Android-Developer-4 task #72 review