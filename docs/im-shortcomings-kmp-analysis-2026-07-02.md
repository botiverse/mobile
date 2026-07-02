# IM 不到位分析：KMP/shared runtime 视角

作者：KMP-专家
日期：2026-07-02

## 背景

Slock 已经具备频道、线程、任务、成员、附件、实时同步、通知与多端 Kuikly 渲染等能力，但从 IM 产品的第一性体验看，当前更像“协作工作台里有消息流”，还没有完全达到“可靠、顺手、低心智负担的即时通信工具”。这个差距不只在 UI，还在 shared 状态模型、实时一致性、离线恢复、通知语义和跨端验证闭环里。

下面从 KMP/shared runtime 角度列出主要不足。

## 1. 会话身份和会话内容边界仍然偏重

近期我们已经持续把 Thread/Home/Inbox 的 launch payload 变窄，但系统里仍有不少地方把 `ConversationSummary`、header、last message、权限、未读、成员状态、任务摘要等信息混在一起流转。对 IM 来说，这会带来几个问题：

- 打开一个会话时，路由身份、首屏 header、历史消息、权限和实时补丁的生命周期不一致，但代码里容易被当成同一份“会话对象”。
- 轻量跳转也可能携带过期 display state，导致首屏闪旧标题、旧头像、旧 unread、旧权限提示。
- 不同入口复用同一宽模型时，Search/Saved/Activity/Home/Notification 的打开语义很难做到完全一致。

IM 的基础应该是：route 只传身份，页面按身份订阅 SSOT；header、access、timeline、draft、tasks、presence 都是独立投影。现在方向是对的，但边界还没有全部收敛。

## 2. 消息到达、已读、未读、提及的语义还不够强

实时事件、历史拉取、本地缓存、前后台恢复都会改变消息状态。当前 shared store 能处理多数路径，但 IM 体验要求更高：

- 用户看到一条消息、系统标记已读、服务端确认已读，这三件事的因果链还不够显式。
- 前后台切换、socket gap、server seq 落后时，用户很难知道未读数字是准确的、延迟的，还是正在修正。
- mention、reply、thread unread、channel unread 混在列表上时，移动端缺少足够稳定的解释层。

这类问题的关键不是再加一个 badge，而是要把 read receipt / unread source / seq reconciliation 做成可观测状态，让 UI 能明确表达“已同步”“正在补齐”“可能过期”。

## 3. 线程和回复模型对普通 IM 用户仍偏复杂

Slock 的线程能力很强，但移动端呈现上还没有完全把“主会话”和“回复线程”的心智负担降下来：

- ReplyThread、Thread、task-linked thread、channel thread 在数据模型上相近，但用户操作入口和返回路径不总是清晰。
- 回复引用、父消息、子线程 unread、跳转 anchor 等能力已经有很多补丁，但还缺少统一的“回复上下文条”体验。
- 线程内再处理任务、附件、成员设置时，IM 主线容易被工作台能力打断。

这意味着我们现在更适合重度协作用户，而不是轻量聊天用户。后续应把“我在和谁说话”“我回复的是哪条”“我怎么回到上下文”作为 IM 首屏核心信息，而不是附属状态。

## 4. Composer 还没有达到高频聊天输入的顺滑度

IM 的输入框是最高频交互。当前 Composer 已经承载文本、Markdown、附件、引用、任务、命令、换行菜单等能力，但移动端体验仍有短板：

- 键盘弹出、底部安全区、附件栏、引用栏之间的布局关系复杂，容易产生跳动。
- 多行输入、光标定位、RichText/native view 触摸分发、overlay 菜单之间仍有平台差异。
- 发送失败、重试、草稿持久化、跨入口恢复虽然有机制，但用户感知还不够直接。

从 IM 产品看，Composer 应先保证“输入、编辑、发送、失败恢复”极稳，再承载高级协作能力。技术上需要把输入态、附件态、引用态、发送队列态拆得更清楚，减少 route/page 级别的临时状态。

## 5. 附件和媒体预览还偏“文件系统”，不够“聊天媒体”

近期已补了多图 gallery preview、下载入口、缓存依赖 Kuikly image cache 等能力，但整体仍偏文件预览：

- 图片、视频、文本、未知文件的状态语言不统一。
- 同一消息内多图浏览已经改善，但跨消息连续浏览、保存、转发、复制链接等 IM 常用动作还不完整。
- 下载、缓存、失败、权限、过期 URL 的状态对用户不够透明。

IM 里的附件不是单纯文件，它是消息内容的一部分。后续应该围绕“消息媒体组”建模，而不是每次从单个 attachment 入口临时拼 preview payload。

## 6. 通知与前后台实时策略仍缺少统一产品语义

我们已经修过 killed app push、foreground banner 规格、Android/OHOS keepalive、长后台 reconnect 等问题。这说明现状仍有结构性挑战：

- foreground、background、killed、server switch、session expired、socket reconnect 之间缺少一套统一用户语义。
- 系统通知、应用内通知、Home badge、Thread unread、Activity feed 之间容易重复或互相打架。
- 多端同时在线时，哪个端负责提示、哪个端负责标已读，还没有充分产品化。

IM 的通知不是纯 native 能力，而是消息生命周期的一部分。shared 层需要有统一的 notification intent / presentation policy，native 只负责最终投递形态。

## 7. Presence 和活动状态噪音过多

Presence、agent activity、member update 对协作很重要，但 IM 里它们应该是低噪音背景信号。之前 Home 大服务器滚动 jank、presence fanout、隐藏 Activity refresh 等问题说明：

- 我们对“哪些实时事件值得驱动 UI”还不够克制。
- 成员状态更新容易污染会话列表、成员列表、Thread 渲染性能。
- 大服务器下，presence 的收益和成本没有按视口、活跃会话、订阅范围细分。

后续应把 presence 视为按需投影：列表只需要少量可见成员状态，Thread 只需要当前可见作者/参与者状态，后台不应为了 presence 刷 durable cache。

## 8. 离线与弱网恢复还没有成为一等体验

shared store 已有本地缓存、SQLDelight、fallback cache、realtime seq 等基础，但 IM 用户需要更明确的弱网体验：

- 消息发送 pending / failed / retry 状态需要更完整。
- 本地已输入、已发送未确认、服务端已确认之间的消息 ID 合并应更可见、更可测。
- gap sync、分页历史、缓存首屏和实时补丁之间的冲突解决还缺少统一文档和端到端测试。

现在很多恢复逻辑分散在 store/repository/realtime owner 里，能工作但难解释。IM 应该有专门的 “message delivery state machine” 文档和测试矩阵。

## 9. 跨端一致性验证仍偏事后补洞

Android、iOS、OHOS、Web/React、Kuikly shared 之间的差异经常通过具体 bug 暴露：RichText、overlay touch、image cache、badge 渲染、keyboard、system back、font、diagnostics 等。对 IM 来说，这会直接破坏信任感。

不足主要是：

- 视觉 fixture 覆盖在补强，但还没有覆盖完整 IM 核心路径。
- connected tests 偏场景验证，缺少跨端同一 message state 的金标准 fixture。
- React 与 KMP 的行为 parity 多靠人工 audit，不够自动化。

建议把 IM 核心路径固化为跨端契约：会话打开、收到消息、回复、已读、发送失败、附件预览、通知点击、后台恢复。每条路径都应有 shared reducer test + Android connected visual/probe + OHOS/iOS 至少一项烟测策略。

## 10. 产品层级上，IM 主线容易被协作功能抢占

Slock 的差异化在 agent/task/channel 协作，但 IM 基础体验不能被这些能力稀释。当前一些页面把任务、成员、系统事件、附件、搜索、设置入口同时塞进聊天上下文，导致：

- 新用户难判断主操作是什么。
- 高频聊天动作路径不够短。
- 任务/agent 能力的状态变化会影响普通消息流稳定性。

更合理的方向是：消息流保持克制，协作能力作为上下文增强浮现；不要让每个消息 row 都承担过多业务分支。

## 建议的优先级

1. 收敛会话 launch / pageData：route 只传身份，所有 IM product state 从 shared store 投影。
2. 建立 message delivery/read/unread 状态机：覆盖发送、确认、失败、已读、seq gap、恢复。
3. 强化 Composer 基础体验：键盘、光标、草稿、发送失败恢复优先于高级菜单。
4. 把附件按“消息媒体组”建模：统一图片/视频/文件预览和缓存状态。
5. 统一通知产品语义：foreground/background/killed/server switch/session expired 不再各自为政。
6. 降低 presence/activity 对主消息流的扰动：按视口和页面可见性订阅投影。
7. 建立 IM 核心路径跨端 fixture：不要等用户截图才发现平台差异。

## 结论

当前 Slock 的 IM 能力不是“没有”，而是很多基础能力已经存在但边界偏重、状态偏散、跨端验证偏后置。下一阶段最重要的不是继续堆更多聊天功能，而是把消息生命周期、会话身份、输入发送、通知恢复这几条主链路做薄、做稳、做可观测。只有这些链路稳定后，agent/task/workflow 的协作增强才不会反过来拖累 IM 体验。
