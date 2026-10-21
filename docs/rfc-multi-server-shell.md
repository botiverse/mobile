# RFC: 多 Server 多窗口（Android 多 Activity）技术方案

- Status: Draft — task #234, awaiting artin review
- Author: CC-希乐; inputs: Android-Developer-4 (phases/MVP), Cindy (product
  boundary), KMP-专家 (hard constraints)
- Audience: 本文写给不熟悉 Android 的读者，Android 概念都会先解释。

## 0. 一句话结论

**可行。** "多开窗口"这层 Android 已经免费给了；真正要做的是把进程里
6 个"只装一份当前 server"的全局状态点改成"按 server / 按窗口分桶"。
量级是**周级工程**（最大头是 realtime 连接层），不是天级小改。

## 1. 背景概念（Android 一页速成）

- **Activity ≈ 一个窗口/一屏**。我们的 app 是"一页一 Activity"：
  首页 shell、每个 thread、设置页……各是一个 Activity 实例，叠成返回栈。
  系统的分屏/最近任务操作的就是 Activity。
- **进程层**：所有 Activity 跑在同一个 app 进程里。进程里可以放不依赖
  任何窗口的东西——我们的 websocket、数据 store、HTTP client 都在这层
  （所谓"进程单例"）。窗口关了它们还在（进程活着的话）。
- 所以"两个窗口开两个 server"的本质是：**窗口层多开（容易）+ 进程层
  的状态从"一份"变"按 server 分桶"（工程量所在）**。

## 2. 现状（代码核实，file:line 可查）

### 2.1 窗口层：几乎白送

- `RaftLegacyHostActivity` 无特殊 launchMode（=standard），本来就多实例
  并存（shell→thread→members 叠栈），系统不阻止再开一个
  （RaftLegacyHostActivity.kt:1058-1076，AndroidManifest.xml:33-38）。
- 缺的只是：两条**独立任务栈**（让两个 server 窗口在最近任务里是两张
  卡片、返回栈互不纠缠）——加 `documentLaunchMode`/taskAffinity 即可，
  外加最近任务的标题/图标按 server 区分。小改。

### 2.2 进程层：六面墙（两个 server 会互相打架的点）

| # | 全局状态 | 位置 | 打架方式 |
|---|---|---|---|
| 1 | 单 websocket | AndroidAppScopeRealtimeOwner.kt:35,407-415 | 连 server B 会掐掉 server A 的连接 |
| 2 | 单 owned session | RaftRealtimeAppScopePolicy.kt:45-111 | 换 server 被当作 session change，清 cursor 重基线 |
| 3 | 单 store provider | RaftSharedStoreProvider.kt:705-723 | 全进程 memoize 一份，所有页面共享其子 store |
| 4 | Home store 单"当前 server" | SharedHomeStore.kt:310-317,408-415 | SelectServer 原地换；realtime 事件按单一可见 server 过滤丢弃 |
| 5 | Activity/未读 store 单 owner | SharedActivityStore.kt:203 | server B 的窗口会驱逐 server A 的 inbox |
| 6 | 持久化 selectedServerId 单值 | SharedHomeStore.kt:648-654 | 两窗口竞写互相覆盖 |

已经安全、不用动的：事件总线每条事件自带 serverId（AndroidAppScope-
RealtimeHostContext.kt:88-97）、realtime cursor 持久化按 server 分键
（RealtimeCursorPolicy.kt:183-190）、auth/DB/HTTP 天然共享。

额外发现：`SlockKuiklyFontAdapter.hostDisplayMetrics` 是进程级
@Volatile（RaftLegacyHostActivity.kt:1422）——分屏两个不同尺寸窗口会互相
覆写渲染度量。**即使不做多 server，分屏本身已有此 bug**，需一并修。

## 3. 目标架构

```
┌─ 窗口层（Activity，可多开）─────────────────────┐
│  Shell 窗口 A            Shell 窗口 B            │
│  shellInstanceId=a1      shellInstanceId=b1      │
│  serverId=S1             serverId=S2             │
└───────────┬──────────────────────┬───────────────┘
            │ 按 (shellInstanceId, serverId) 认领
┌─ 进程层（引擎室，按 serverId 分桶）──────────────┐
│  RealtimeSession[S1]     RealtimeSession[S2]     │
│  HomeStore[S1]           HomeStore[S2]           │
│  ActivityStore[S1]       ActivityStore[S2]       │
│  ── 共享底座：auth session / server list / DB /  │
│     HTTP client / 事件总线（事件自带 serverId）   │
└──────────────────────────────────────────────────┘
```

硬约束（KMP-专家/AD4 定，防走样）：

1. **route/pageData 只传 identity**（serverId / shellInstanceId /
   intent），不传 Home/Thread 内容快照；
2. **所有外部事件入口统一规则**：parse serverId → resolve 目标
   shellInstanceId → 投递；resolve 不到才走产品策略（新开窗口或落默认
   窗口）。**禁止"发给当前前台 shell"这种隐式兜底**——notification、
   realtime、foreground banner、deep link 全部同一口径。

产品边界（Cindy 定）：第一版只做**同账号、多 server、多窗口**。
多账号/多 session 不做（会把 auth/keychain/push/诊断全拖进 profile
隔离，复杂度翻倍），另起任务。

## 4. 分期实施

### Phase 1 — 状态审计落表（≈2-3 人日）
第 2.2 节的 6+1 点已是审计主体；补扫关键词（selectedServer /
currentShell / childShellId / foreground banner pending / notification
open / realtime join / local write-through），每个命中点按三列归类
（process shared / server scoped / shellInstance scoped）+ action +
test gate。产出 = 拆任务底稿。

### Phase 2 — store 分桶（≈1 周）
- 墙 3/4/5：store provider 与 Home/Activity store 改为按
  (shellInstanceId, serverId) 实例化的 registry；页面从"拿全局单例"改
  为"按自己的 shellInstanceId 认领"。
- 墙 6：持久化的 selectedServerId 降级为"最后使用的 server"（仅冷启动
  默认值）；运行时"当前 server"归各 shell 实例自己。
- 顺手修 hostDisplayMetrics 按窗口分桶。

### Phase 3 — realtime 多 session（≈1 周，最大头）
- 墙 1/2：AndroidAppScopeRealtimeOwner 从单 runtime 改为
  `Map<ServerId, RealtimeSession>`（连接、cursor、joined channels 各自
  独立）。第一版**每 server 一条连接**（简单可靠，AD4 口径）；连接复用
  /引用计数是后续优化，不进第一版。
- 事件路由：总线事件已带 serverId，消费端从"单 Home store 过滤"改为
  "路由到对应 server 的 store"。

### Phase 4 — 窗口壳与系统集成（≈2-3 人日）
- server switcher / server 行加"在新窗口打开"入口；
  documentLaunchMode + 最近任务标题/图标按 server 区分；
- notification/deep link 按第 3 节入口规则接线（命中已开窗口就投递，
  没有则按产品策略）。

依赖顺序：Phase 2 → 3 → 4（1 与 2 可并行起步）。iOS 不动
（shared 侧分桶自然惠及未来的 iPad 多窗，但本 RFC 只做
Android 落地）。

## 5. MVP 验收（AD4 的"四个不串线"）

同一账号，Android 分屏同时开 server A / server B 两个窗口：

1. **状态不串**：两边 Home/Thread/selectedTab/返回栈互不影响；
2. **数据不串**：auth/DB 共享，但各自的当前 server 投影和本地写入
   各归各；
3. **事件不串**：A 的 realtime 消息/foreground banner 只出现在 A 窗口；
   notification 点开落到匹配 server 的窗口；
4. **系统不串**：最近任务两张卡片可区分 server，分屏拖动不崩不糊
   （hostDisplayMetrics 修复的验证点）。

## 6. 风险与回退

- 最大风险在 Phase 3（连接层重构）：用 feature flag 包住"多 session
  模式"，默认关；单窗口路径回归绿了再开。
- Phase 2 的 store 实例化会触碰大量"从全局拿 store"的调用点——机械但
  面广，靠现有 1640+ 测试门 + 视觉基线兜底。
- 每 server 一条 websocket 增加后台耗电/服务端连接数：第一版接受
  （窗口关闭即断开该 server 的 session），复用优化留给后续。
