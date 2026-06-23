# Agent Status Indicator 现状与对齐 Spec

日期：2026-06-23
任务：#members专修 task #68
触发：@artin "我们现在的 agent status indicator 是订阅的 members store 吗，然后我们的 status 是严格对齐 react 的不，请分析写一个 spec"
负责人：@Product-UX-Designer (spec lane)

## TL;DR

- **订阅链路**: KMP 不是订阅 `SharedMembersStore`（那是成员列表 load status），而是通过 `SharedAgentDetailStore` 订阅 realtime + summary snapshot（`MemberProfile.agentStatus` 字段 + `AgentDetailSnapshot.{status, activity, activityDetail}` 三元组）。`RealtimeEventMapper.kt:540` 把 wire `activity` event 解析成 `AgentStatus` 并推到 `SharedHomeStore` / `ChannelRepositoryDataSource` 增量更新。
- **状态机**: KMP 有 **7 状态**（`Online / Working / Thinking / Idle / Disconnected / Offline / Error`），React 有 **5 状态**（`online / thinking / working / error / offline`）。KMP 多 `Idle` 和 `Disconnected` 两个 fallback 状态。
- **颜色映射**: KMP 颜色对 React 的 5 状态子集（Online→lime, Working/Thinking→yellow pulse, Error→orange, Offline→gray）**完全对齐**。但 KMP `Idle` 也走 lime、`Disconnected` 走 gray — 跟 React 不存在的状态被映射成视觉近似的颜色，会让 React 用户觉得 "agent Idle 跟 Online 都是绿点" 或 "agent Disconnected 跟 Offline 都是灰点" — 语义丢失。
- **Pulsing**: KMP `Working + Thinking` 走 pulse 跟 React 一致。
- **视觉**: KMP `SlockStatusIndicator` 用 Canvas 自绘 circle / square，size 9-10dp + BorderBlack `SlockStroke.Thin` + Optional pulse alpha ring；React `StatusDot` 是 Tailwind `bg-brutal-lime / bg-brutal-yellow / bg-brutal-orange / bg-gray-400` + `border border-black` + `animate-pulse`。整体 brutal style 一致，颜色 token 名 `BrutalLime / BrandYellow / AccentOrange / EmptyStateGray` 应该跟 React `bg-brutal-lime / bg-brutal-yellow / bg-brutal-orange / bg-gray-400` 同色值（待抽样验证）。
- **缺口**: 主要在 (a) Idle / Disconnected 状态语义对齐 (b) subscription 是否真覆盖所有入口 (sidebar / home DM row / agent detail / activity log / agent list row / mention hover card) (c) external agents (SHA-V0-014) React 有 cyan，KMP 没看到对应路径。

## 1. 现状

### 1.1 KMP `AgentStatus` 状态机

文件：`compose/shared/src/commonMain/kotlin/ai/slock/compose/model/SlockModels.kt:118-150`

```kotlin
enum class AgentStatus {
    Online, Working, Thinking, Idle, Disconnected, Offline, Error;

    val label: String
    val presence: Presence
    // Active: Online, Working, Thinking
    // Away: Idle
    // Offline: Disconnected, Offline, Error

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

7 状态 + 4 wire alias 集合 (`active / available / connected / running / busy / not_connected / inactive / failed / failure`)。

### 1.2 KMP `AgentStatus → Color` 映射

文件：`MembersPage.kt:3997-4007`

```kotlin
private fun AgentStatus?.membersStatusColor(presence: Presence): Color =
    when (this) {
        AgentStatus.Online -> SlockColor.AccentLime
        AgentStatus.Working -> SlockColor.BrandYellow
        AgentStatus.Thinking -> SlockColor.BrandYellow
        AgentStatus.Idle -> SlockColor.AccentLime     // ⚠️ React 不存在, 映射成 Online 绿
        AgentStatus.Disconnected,
        AgentStatus.Offline -> SlockColor.EmptyStateGray
        AgentStatus.Error -> SlockColor.AccentOrange
        null -> presence.membersPresenceColor
    }

private fun AgentStatus?.membersStatusPulsing(): Boolean =
    this == AgentStatus.Working || this == AgentStatus.Thinking
```

### 1.3 KMP `SlockStatusIndicator` 渲染

文件：`SlockStatusIndicator.kt:1-100`

```kotlin
internal enum class SlockStatusIndicatorShape { Circle, Square }

internal object SlockStatusIndicatorTokens {
    val Shape = Circle
    val AvatarSize: Dp = 9.dp        // 头像叠加
    val InlineSize: Dp = 10.dp       // 行内
    val ServerUnreadSize: Dp = 10.dp
    val BorderWidth = SlockStroke.Thin
    val BorderColor = SlockColor.BorderBlack
}

@Composable
internal fun SlockStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = InlineSize,
    shape: Shape = Circle,
    borderColor: Color = BorderBlack,
    borderWidth: Dp = BorderWidth,
    pulsing: Boolean = false
)
```

Canvas 自绘:
- Circle / Square 两种 shape
- Size 9-10dp
- BorderColor = BorderBlack, BorderWidth = Thin
- Pulsing 时加 alpha 0.28f ring（不向外扩展，避免 layout shift）
- Size 默认 10dp (InlineSize)
- Avatar overlay 用 9dp

### 1.4 React `AgentActivity` 状态机

文件：`third_party/slock-source/packages/web/src/components/ui/StatusDot.tsx:1-80` + `utils/activity.ts:1-50`

```typescript
// AgentActivity type comes from @slock-ai/shared, runtime state
type AgentActivity = "online" | "thinking" | "working" | "error" | "offline";

export function getActivityDotClass(activity: AgentActivity): string {
  switch (activity) {
    case "online":   return "bg-brutal-lime";
    case "thinking": return "bg-brutal-yellow animate-pulse";
    case "working":  return "bg-brutal-yellow animate-pulse";
    case "error":    return "bg-brutal-orange";
    case "offline":
    default:         return "bg-gray-400";
  }
}

export function getActivityText(activity, detail): string {
  switch (activity) {
    case "online":   return "Online";
    case "thinking": return "Thinking…";
    case "working":  return detail || "Working…";
    case "error":    return errorDetail ? `Error: ${errorDetail}` : "Error";
    case "offline":  return detail === "Stopped" ? "Stopped — won't receive messages until restarted" : "Offline";
    default:         return activity;
  }
}
```

5 状态 + Stopped detail 特例。颜色用 Tailwind `bg-brutal-*` token。

### 1.5 React `StatusDot` 渲染

文件：`StatusDot.tsx:55-79`

```typescript
const SIZE_CLASS = { sm: "size-2", md: "size-2.5", lg: "size-[11px]" };

function StatusDot({ activity, tone, external=false, size="md", pulse=false, ... }) {
  const colorClass = external ? "bg-brutal-cyan"
                       : activity ? getActivityDotClass(activity)
                                  : tone ?? "bg-gray-400";
  return (
    <span className={`inline-block shrink-0 rounded-full border border-black ${SIZE_CLASS[size]} ${colorClass} ${pulse ? "animate-pulse" : ""} ${className ?? ""}`} />
  );
}
```

Sizes:
- `sm` = 8dp (size-2)
- `md` = 10dp (size-2.5)
- `lg` = 11dp (size-[11px])

External agents (SHA-V0-014) → `bg-brutal-cyan` (无 managed liveness 绿/红)

### 1.6 订阅链路对比

| 入口 | React | KMP |
|---|---|---|
| Sidebar | StatusDot via `getActivityDotClass(activity)` | ? (待查 SidebarPanel 引用) |
| ChatPanel | StatusDot in MessageItem avatar overlay | ? |
| AgentDetailPanel | StatusDot (md / lg) | `MembersAgentStatusIndicator` in `MembersPage.kt:3950-3995` |
| MachineDetailPanel | StatusDot | ? |
| MentionHoverCard | StatusDot (sm) | ? |
| AgentListRow | StatusDot | `MembersAgentListRow` in MembersPage |
| AgentActivityLog | StatusDot (sm) | `MembersAgentActivityRow` (SlockStatusIndicator per entry) |
| ChannelMembers | StatusDot | `MembersAgentLifecycleAction` cell |

**KMP 订阅入口确认**:
- `RealtimeEventMapper.kt:540-545` 把 wire `activity` 解析成 `AgentStatus` 推给 `agentStatus` 字段
- 推到 `SharedHomeStore.kt:1413` → `ChannelRepositoryDataSource.kt:554-590` → `MemberProfile.agentStatus`
- **Members 入口 (`MembersPage`) 用 `member.membersResolvedAgentStatus(summary?.status, summary?.activity)`**:
  - 先 `fromWireValue(summary?.activity)` (从 AgentDetailSnapshot, realtime-driven)
  - 再 fallback `fromWireValue(summary?.status)`
  - 再 fallback `member.agentStatus` (snapshot)
- 所以: 订阅链是 **realtime → AgentDetailStore → MemberProfile.agentStatus field → MembersPage 读 summary 优先 → statusColor**

## 2. 差距

### 2.1 状态机差距

| 状态 | React | KMP | 评价 |
|---|---|---|---|
| online | ✅ lime | ✅ AccentLime | 对齐 |
| thinking | ✅ yellow + pulse | ✅ BrandYellow + pulse | 对齐 |
| working | ✅ yellow + pulse | ✅ BrandYellow + pulse | 对齐 |
| error | ✅ orange | ✅ AccentOrange | 对齐 |
| offline | ✅ gray-400 | ✅ EmptyStateGray | 对齐 |
| idle (KMP only) | ❌ | ⚠️ AccentLime (跟 Online 同色) | **语义丢失**: React 没 idle, 但 KMP 把 idle 渲染成跟 online 一样的绿色, 用户无法区分 |
| disconnected (KMP only) | ❌ | ⚠️ EmptyStateGray (跟 Offline 同色) | **语义丢失**: KMP 把 disconnected 跟 offline 渲染一样, 但 `Offline` 在 React 是 server-side 不可用, `Disconnected` 在 KMP 是 client-side socket 断开 (per `agentStatus.presence = Presence.Offline` 二者一致) |

### 2.2 视觉差距

| 维度 | React | KMP | 评价 |
|---|---|---|---|
| Shape | 圆 (rounded-full) | 圆 (Canvas circle) 或 Square | ⚠️ KMP 多 Square shape (ServerUnreadSize 那块), React 不用 |
| Size (sm) | 8dp | 8-10dp | 接近 |
| Size (md / Inline) | 10dp | 10dp | 对齐 |
| Size (lg / Avatar) | 11dp | 9dp | ⚠️ KMP AvatarSize 9dp vs React lg 11dp |
| Border | `border border-black` | `SlockStroke.Thin` BorderBlack | 接近 (需抽样验证 SlockStroke.Thin == 1dp) |
| Pulse | `animate-pulse` CSS | Canvas 0.28f alpha ring (1150ms infinite) | 实现路径不同, 视觉接近 |
| Color tokens | `bg-brutal-lime / bg-brutal-yellow / bg-brutal-orange / bg-gray-400` | `AccentLime / BrandYellow / AccentOrange / EmptyStateGray` | ⚠️ **token 名不对应**, 色值需抽样对 (per spec v4.1.1 应走 BrutalLime / BrutalYellow / BrutalOrange / BorderBlack) |
| External agents | `bg-brutal-cyan` (SHA-V0-014) | ❌ KMP 没看到对应路径 | **缺**: KMP agent 没 external 概念, 缺 cyan 路径 |

### 2.3 订阅链路差距

| 路径 | React | KMP | 评价 |
|---|---|---|---|
| Realtime push → status | ✅ socket event → ActivityStore | ✅ RealtimeEventMapper → SharedHomeStore / ChannelRepositoryDataSource → MemberProfile.agentStatus | 对齐 |
| Activity snapshot priority | ✅ activity > status > default | ✅ summary.activity > summary.status > agentStatus | 对齐 |
| Initial load 拉 snapshot | ✅ fetch | ✅ fetch | 对齐 |
| Realtime 断线后 stale | ✅ stale flag | ⚠️ KMP 没显式 stale 标记, 只 fallback 到 snapshot | 可能导致 disconnected 状态不及时反映 |
| StatusDetail (per state 详情) | ✅ getActivityText 显示 "Thinking…" / "Working…" / "Error: detail" | ⚠️ KMP 只用 `label` (单字 "Thinking"), 没 detail 透传 | **缺**: 视觉上丢失 detail 信息 |

### 2.4 缺口总结 (跟 spec v4.1.1 + 上次 PRD 路线对齐)

1. **Idle / Disconnected 语义丢失** — KMP 多 2 状态但映射同色, 用户无法区分
2. **External agent 缺 cyan 路径** — React `bg-brutal-cyan` 没 KMP 对应
3. **Detail 文本丢失** — `getActivityText(activity, detail)` 走 "Error: xxx" / "Working: xxx" / "Stopped: xxx", KMP 只 `label` 单字
4. **Color token 名不对应** — KMP `AccentLime / BrandYellow / AccentOrange / EmptyStateGray` vs React `bg-brutal-lime / bg-brutal-yellow / bg-brutal-orange / bg-gray-400`, 应走 `BrutalLime / BrutalYellow / BrutalOrange / BorderBlack` 统一 (per spec v4.1.1)
5. **Square shape 不必要** — KMP `SlockStatusIndicatorShape.Square` (ServerUnreadSize 那块), React 不用, 可能是早期 hardcode
6. **AvatarSize 9dp vs React lg 11dp** — 头像叠加视觉差 2dp

## 3. 接 gap 路径（建议）

### 3.1 P0 (本轮视觉 parity 必须)

1. **Idle / Disconnected 颜色差异化**
   - KMP 增 1 个中间色 `SlockColor.BrutalIdle` (例如 `bg-brutal-blue-200` 或 React 类似的 `bg-blue-300`) — `Idle` 走这个
   - `Disconnected` 保留 `EmptyStateGray` 跟 `Offline` 视觉一致 (因为 React 没区分)
   - 或: 移除 `Idle` + `Disconnected` 状态, 直接走 `Online` / `Offline` (简化但需业务 owner 拍)

2. **External agent 走 cyan**
   - KMP `AgentStatus` 加 `external` 字段 (Boolean) 或新枚举值
   - Wire value `external` / `unmanaged` 解析为 External
   - Color 走 `SlockColor.AccentCyan` (per BrutalCyan / spec v4.1.1)

3. **Color token 统一到 Brutal 系列**
   - `AccentLime → BrutalLime`
   - `BrandYellow → BrutalYellow`
   - `AccentOrange → BrutalOrange`
   - `EmptyStateGray → EmptyStateGray` (保留, React `bg-gray-400` 也是 gray 系列)
   - `BorderBlack` 已统一, 无需改
   - 走 `SlockBrutalColor` 系列 API 而不是 `SlockColor.AccentXxx` (per #187 Token hardcode cleanup and CI gate)

4. **AvatarSize 9 → 11 dp**
   - `SlockStatusIndicatorTokens.AvatarSize` 改 11dp, 跟 React `lg = 11dp` 对齐

### 3.2 P1 (体验改进)

5. **Detail 文本透传**
   - `AgentStatus.label` 改成 `displayText(detail: String?)` 类似 React `getActivityText(activity, detail)`
   - Members detail / activity log / agent list row 显示完整 "Working: <detail>" / "Error: <error_message>"

6. **Stale 标记**
   - Realtime 断线超过 N 秒, 加 `stale: Boolean` 标记
   - Members view 显示 stale 时 indicator 加斜线 (per React `loading-state` 待查) 或改色

### 3.3 P2 (清理)

7. **移除 Square shape** — ServerUnreadSize 那块应该用 unread count 数字而不是 square dot (per React)

8. **membersResolvedAgentStatus 走 MembersStore 订阅**
   - 当前 `MembersPage.kt:4039-4043` 是 `MemberProfile.membersResolvedAgentStatus(summary?.status, summary?.activity)`
   - 把 summary 改成从 `SharedAgentDetailStore.state.map { ... }` 订阅 (已经走 SharedAgentDetailStore, 但要从 MembersPage 内嵌的 mock summary 切到 store)
   - 避免重复订阅

## 4. 验收标准

### 4.1 颜色映射验收

| 状态 | KMP 渲染 | React 渲染 | 一致 |
|---|---|---|---|
| Online | BrutalLime circle | bg-brutal-lime rounded-full | ✅ |
| Working | BrutalYellow circle + pulse | bg-brutal-yellow animate-pulse rounded-full | ✅ |
| Thinking | BrutalYellow circle + pulse | bg-brutal-yellow animate-pulse rounded-full | ✅ |
| Idle | BrutalIdle (新) circle | (React 不存在) | ⚠️ KMP 引入新色 |
| Error | BrutalOrange circle | bg-brutal-orange rounded-full | ✅ |
| Offline | EmptyStateGray circle | bg-gray-400 rounded-full | ✅ |
| Disconnected | EmptyStateGray circle (跟 Offline 同色, 但语义不同) | (React 不存在) | ⚠️ 接受同色但应加 tooltip "Disconnected" |
| External | BrutalCyan circle | bg-brutal-cyan rounded-full | ✅ (新加) |

### 4.2 Size 验收

| 位置 | KMP | React | 一致 |
|---|---|---|---|
| 头像叠加 | 11dp | 11dp (lg) | ✅ |
| 行内 (header / sidebar) | 10dp | 10dp (md) | ✅ |
| 内联 (inline text) | 8dp | 8dp (sm) | ✅ (KMP 需加 sm variant) |

### 4.3 订阅链路验收

1. Realtime 收到 `activity` 事件 → MemberProfile.agentStatus 在 200ms 内更新 → MembersPage 渲染新颜色
2. Realtime 断线 30s → MembersPage 显示 disconnected (EmptyStateGray) 或 stale indicator
3. 重连后 → status 从 realtime 恢复, 不 fallback snapshot
4. Initial load 显示 "Online" (snapshot), realtime 5s 内推 working → 自动切黄色

### 4.4 Detail 文本验收

1. Working + detail = "Capturing deterministic Activity tab state" → 显示 "Working: Capturing deterministic..." (省略号截断)
2. Error + detail = "Connection refused" → 显示 "Error: Connection refused"
3. Offline + detail = "Stopped" → 显示 "Stopped — won't receive messages until restarted"

## 5. Owner 建议

| 任务 | Owner |
|---|---|
| Idle / Disconnected 颜色差异化 | @Android-Developer-1/2/3 + PUX 跟 token 选择 |
| External agent cyan 路径 | @API-Integration-Developer (wire) + @Android-Developer-1/2/3 (UI) |
| Color token 统一到 Brutal 系列 | @Codex-KMP-Developer (per #187 Token hardcode cleanup) |
| AvatarSize 9 → 11 dp | @Android-Developer-1/2/3 |
| Detail 文本透传 | @Android-Developer-1/2/3 + @Codex-KMP-Developer |
| Stale 标记 | @Codex-Kuikly-KMP专家 (realtime + shared state) |
| 移除 Square shape | @Android-Developer-1/2/3 |
| membersResolvedAgentStatus 走 Store 订阅 | @Codex-KMP-Developer (refactor) |

## 6. 扫描依据

React 源:
- `third_party/slock-source/packages/web/src/components/ui/StatusDot.tsx`
- `third_party/slock-source/packages/web/src/utils/activity.ts`
- `third_party/slock-source/packages/web/src/components/agent/AgentDetailPanel.tsx`
- `third_party/slock-source/packages/web/src/components/agent/AgentWorkspace.tsx`
- `third_party/slock-source/packages/web/src/components/agent/AgentActivityLog.tsx`

KMP 源:
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/model/SlockModels.kt:118-150` (AgentStatus enum)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt:3997-4057` (statusColor / statusPulsing / membersResolvedAgentStatus)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockStatusIndicator.kt` (Canvas 自绘)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/realtime/RealtimeEventMapper.kt:540` (wire → AgentStatus)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedHomeStore.kt:1413` (realtime push 接收)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/ChannelRepositoryDataSource.kt:554-590` (MemberProfile.agentStatus 增量更新)
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberAgentParser.kt:659` (snapshot AgentStatus)

## 7. 后续维护

- 本 spec 维护节奏: 跟 `docs/react-kmp-feature-map-2026-06-23.md` 一致, 每 3 天或每 30+ commit 刷一次
- 颜色 token 走 `SlockBrutalColor` 系列 (per #187), 不允许 inline `Color(0xFF...)`
- 新增 agent 状态走 enum + `fromWireValue` 双轨, 同步更新 React AgentActivity 镜像
- PUX stay product lane, 不下 fix decision, 不写 code. spec 是产品分析输出.

owner: @Product-UX-Designer
date: 2026-06-23
status: ready for owner review