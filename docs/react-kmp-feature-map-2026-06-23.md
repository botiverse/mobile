# React vs KMP 功能大图

日期：2026-06-23
更新：从 2026-06-20 复核版更新 (3 天增量)
任务：#product task #199
负责人：@Product-UX-Designer

## 结论

自 2026-06-20 复核以来 3 天，主仓 `main` 又合入 200+ commit。多数 P0 / P1 缺口继续在收敛，部分 P0 进入 in_review 状态；同时暴露了一个新的内容层缺口。

本版重点更新：

- 已闭合缺口（"已有" → "已实现 + 已验证"）。
- 在 P0 / P1 表里把"待证明"和"已验证"分开。
- 新增一个 P0 缺口：**对话流内容翻译**（React 完整 `translationStore`，KMP 仅有 `preferredTranslationDisplay` 设置但没翻译引擎/缓存/条目）。
- 不再逐项列已闭合的 commit；只标 gap 是否仍在 owner lane / 验证 lane。

## 2026-06-20 → 2026-06-23 增量摘要

### 已闭合 / 进入 in_review 的缺口（按域）

| 功能域 | 上次状态 | 现在状态 | 关键 commit |
| --- | --- | --- | --- |
| Members avatar picker | 仅诊断 + 接口 | ✅ 完整闭环（picker modal + multipart upload + finch pixel + visual case 激活） | `7cc37f1c fix(#57)`、`ac1ff69a fix(#49)`、`235b4857 fix(#49)`、`4db9e903 fix(#225-regression)` |
| Members agent detail profile | 待证明 read-only 默认 + pencil edit | ✅ 完整 profile 重写 + section 简化 + ENV VAR masked | `4b7bd033 fix(#53)` |
| Members agent activity | 待 onResume 修复 + scroll pin | ✅ activity log scroll pin + empty state + log unpinned framing | `6a379593`、`43fc6ef4`、`8977686d` |
| Members status dot | 待 row-end 改 | ✅ | `c3361da6 fix(#50)` |
| Members 三点菜单 | 待 SlockBrutalDropdownMenu 接入 | ✅ | `09a35cc2 feat(#51)` |
| Members agent detail store wiring | 待 ShareAgentDetailStore 接入 | ✅ | `b9ecbc23 feat(#56 phase 2A)`、`a014ee23`、`18158e94`、`d7f0568f`、`c1cf0df4` |
| Members workspace file preview | 待精简 | ✅ | `6a542147 fix(members)` |
| Members folder icon | 缺 | ✅ | `d451ab0c` |
| Members activity route visual case | pending | ✅ activated | `ad383cf6` |
| Members component cases | pending | ✅ activated | `394be34c` |
| Settings Billing variants | 待 parity | ✅ in_review (task #87 closed) | `9bc4eb7e Merge task #87` |
| Settings Danger visuals | 待 parity | ✅ | `e4e8811b fix(settings)` |
| Settings Computer rename | 待 SlockUnifiedDialog | ✅ (后 revert 一次 + 再加, 当前在) | `9e96cef3` + `6328823c fix(#84)` + `af47e173 revert` |
| Settings Computer delete | 待 SlockUnifiedDialog | ✅ | `48aefa11 fix(#77)` |
| Settings Billing portal + compare plans | 待 system browser 接入 | ✅ | `edc2670a fix(#76)` |
| Settings Profile TextField | custom 未统一 | ✅ SlockBrutalTextField | `47bd6fb6` |
| Settings secondary pages | 待去除 intro card | ✅ | `c60dbcc4`、`7b52ccd9 Merge task #211` |
| Settings Appearance auto-save | 待 instant | ✅ | `a0b62e94` |
| Settings Notifications | 待去 PWA install card | ✅ | `c1cd906d` |
| Settings Language/Region — translation display | 待 preferredTranslationDisplay wire | ✅ (#78 closed) | `e18dd168 fix(#78)` |
| Settings Language/Region — workspace translation | 待去 (per React) | ✅ | `257e60b3 fix` |
| Settings form section title | 待去 card border | ✅ | `63526cc2` |
| Home landing brand block | 待 lift | ✅ | `8f430a3a fix(home)` |
| Home landing page font + right side 空 | 待对齐 react | ✅ restore landing centered | `f87864fe fix(home)` |
| Home inactive tasks pane collapse | 待合并 | ✅ | `239621f3` |
| Home realtime announcements → notifications | 待路由 | ✅ | `1ad6fe4d` |
| Home top action row states | 待 parity | ✅ | `bd5d90dd` |
| Home sidebar bounce | 待显式 enable | ✅ | `bd4331e0` |
| Home refresh indicator unify | 待 parity | ✅ | `46402f95` |
| Home refresh affordance on shell | 待 header spinner | ✅ + remove pull | `2f4bff33`、`6385b911`（先加 spinner 后删 pull） |
| Home pull refresh 后无 loading 反馈 | 缺 | ⚠️ P0 仍未闭环 — 在 #首页专修 #255 (`task #255` in_review, owner @Codex-KMP-Developer) | （无 commit 落地） |
| Home menu labels truncation | 待 | ✅ | `f5e12240` |
| Home onResume refresh | 待 | ✅ | `a04af754 fix(home)` |
| Home activity actions | 待 long press | ✅ | `1d22e44d` |
| Home activity load more | 待 | ✅ | `7b7acd27` |
| Home shell server chooser back button | 待 | ✅ | `edfa68b8` |
| Home activity panels refresh | 待 | ✅ | `25c07178 fix(activity)` |
| File preview unsupported center | 待 | ✅ | `944a7476` |
| File preview image pinch zoom | 缺 | ✅ | `9f511f5e fix(preview)` |
| File preview filenames in image | 待 hide | ✅ | `db52e460` |
| File preview download progress | 缺 | ✅ | `9bd103bf` |
| File preview download | 待 defer / unsupported | ✅ | `4437f595`、`cb71a2ff` |
| Thread reply routes parent id | 待 accept | ✅ | `510dff46`、`947ee958`、`93b43052` |
| Thread identity-safe header titles | 待 | ✅ | `b5bf1c50`、`942498b0`、`b783b8dd` |
| Thread message row focus flash | 待 | ✅ | `d68b83a1` |
| Thread loading composer space | 待稳定 | ✅ | `8cfe023f` |
| Thread open member profile from avatar | 待 | ✅ | `0af27439` |
| Thread pending attachments as preview tiles | 待 | ✅ | `726de259` |
| Thread top anchor reply origin content | 待 | ✅ | `25cc18ef` |
| Thread rich message rendering | 待 | ✅ | `8d922c05` |
| Thread compact header actions | 待 | ✅ | `03ea2c34` |
| Thread first reply after stream resolves | 待 | ✅ | `2ab16eef` |
| Thread incomplete assignee chip | 待 hide | ✅ | `6d0b22cf` |
| Thread member detail modal | 待去 | ✅ | `f553668b fix(thread)` |
| Thread attachment preview card reuse | 待 | ✅ | `80a28ffd refactor` |
| Thread attachment comment controls | 待 | ✅ | `44cac5d2` |
| Thread composer suggestions isolation | 待 | ✅ | `03533bda`、`c839f706`、`cd875ae6`、`70ad0d22` |
| Thread composer draft state isolation | 待 | ✅ | `69a1dd5e fix(#319)` |
| Thread files tab state isolation | 待 | ✅ | `05afc7b7` |
| Thread tasks join state isolation | 待 | ✅ | `d6e74e4d` |
| Thread composer (KMP-专家 #319 + Codex #316-318) | 部分待 | ✅ in_review 4 task 全收口 | `69a1dd5e`、`05afc7b7`、`d6e74e4d`、`cb71a2ff` |
| Composer mention avatars (#27) | 待 | ✅ | `7288e771`、`96c6d81a Merge task #27` |
| Composer mention cursor (#26) | 待 | ✅ | `8fa01148`、`98222f8e Merge task #26` |
| Composer disabled mention suggestions | 待 | ✅ | `c003a626` |
| Composer suggestion rows refinement | 待 | ✅ | `febdd091`、`479ee38e Merge task #27 follow-up` |
| Composer newline action menu (#28) | 待 | ✅ | `273d0868`、`faab4fe2 Merge task #28` |
| Composer standalone visual chrome | 待 | ✅ | `8a20737f` |
| Composer disabled send button | 待 | ✅ | `ccc7fcdd` |
| Visual testing billing variants deprecated | 残留 | ✅ removed | `e39f1fc5` |
| Visual testing billing variants activated | 待 | ✅ | `9bc4eb7e Merge task #87` |
| Visual testing composer suggestion cases | pending | ✅ activated | `e0e15a98` |
| Visual testing choose-server screen gaps | 待 register | ✅ | `93f9ec6e` |
| Visual testing component vs screen split | 混乱 | ✅ | `d06ddc81` |
| Visual testing component crop dimensions | 待 preserve | ✅ | `6714dd8e` |
| Visual testing auth screens | 缺 | ✅ captured | `6d831a2e` |
| Modal card chrome | 待 | ✅ | `e5396b99` |
| Realtime recovery `resume` | 待 | ✅ | `fedd5959 fix(shell)` |
| `re:` chip 跨 host | 仅 hostSource-gated | ✅ | （task #276 已落, 无新 commit） |
| React alignment — resolvedBasePayload | 待 | ✅ | `e2a14c0c` |
| MockConversationRepository getReplyThread | 缺 | ✅ | `59b9f36c` |
| MockMemberRepository.uploadAgentAvatar stub | 缺 | ✅ | `cd957ab2` |
| Cache visible markdown rendering (#192) | 待 | ✅ | `a1cb724d` |
| Markdown trace metadata | 待 | ✅ | `b9e4d85f`、`30de28e0`、`b627479d`、`b71b5b1b`、`c69bddb1`、`4143ac4a`、`49e7b702` |
| Markdown plain text fast path | 待 | ✅ | `746f3553`、`65cf9b14` |
| Trace System.nanoTime expect/actual | 待 KMP compile | ✅ | `3a5a5a3a fix(#192)` |
| Slock font weight resolver | 部分 hardcoded | ✅ | `9c6ffbf0` |
| Pixel avatar DrawWithCache | 待 | ✅ | `4143ac4a feat(#195)` |
| MarkdownParseResultCache size | 256 | ✅ 256 → 1024 | `e698e267` |
| Computer picker consume shared store | 待 | ✅ | `9e76193c` |
| Shared computers hide from settings source | 待 | ✅ | `91903eb5` |
| Shared computers in settings | 待 | ✅ | `ef485cf8` |
| Shared computer reload after delete | 待 | ✅ | `ae463bed`、`fa9f2b1e`、`69768b61` |
| Home DM avatar square aspect (#229) | 待 | ✅ | `be850b69 fix(#229)` |
| Home pin menu anchor long-press X (#223) | 待 | ✅ | `34adb209`、`600133ae` |

### 仍在 in_progress / todo 的视觉 parity backlog（来自 task #230 stale 重跑结果）

@Android-Developer-4 在 13:51 完成 #230 10 case 重跑 + 拆 7 个后续 parity fix 任务 (owner @Android-Developer-4)：

- `#231` components.members.agent-lifecycle-actions — Android action menu vs React restart confirm modal 不对齐（pixel 5.08%，RGB 50.31%）
- `#232` components.channel.members.panel — panel/modal 状态 fixture 不一致
- `#233` components.channel.members.add-panel — 同上
- `#234` components.channel.settings.panel — 同上
- `#235` components.members.avatar-management — picker 直接展开 vs change-avatar modal
- `#236` screens.settings.server-danger-modal — dialog 尺寸 / 按钮 / 遮罩 差
- `#237` components.home.create-channel.dialog — 列表内容 / 间距 差

@AD-4 已声明未 claim，等 owner 拍是否做或关。

### 仍在 in_progress 的 Home / Thread / Settings 修复（非视觉 backlog）

- `#244` Profile activity 列表 box 改撑满（owner @Codex-KMP-Developer）
- `#245` onResume 拉数据通用化（owner @Codex-KMP-Developer）
- `#246` 首页 realtime 通知（owner @AD-4）— 13:24 已 in_progress
- `#247` 安卓字体偏下 1px baseline（owner @Codex-KMP-Developer）
- `#248` 字重对齐 React（owner @Codex-KMP-Developer，已 done 在 14:00）
- `#249` RAFT icon 替换后左上角 logo 太小（owner @Codex-KMP-Developer）
- `#250` 顶部 search press/active 态对齐 channel row（owner @KMP-专家）
- `#251` tab 切换后页面无法上下滚动（owner @Codex-KMP-Developer）
- `#252` landing 页右边空 + 字体细（owner @KMP-专家）
- `#253` activity 下拉刷新（owner @Codex-KMP-Developer）
- `#254` onResume 通用 refresh（owner @Codex-KMP-Developer）
- `#255` 顶部 header 轻量 loading 替代 pull refresh（owner @Codex-KMP-Developer）

### 仍未闭环的 P0 视觉 / 行为 gap

1. **screens.home.{index,activity,saved} 新 "Screens Parity" framework pending**（@artin 11:56 截图 #product:6c6eacdf）
   - Android capture 已存在 (real launch + adb-screencap)，但 framework 仍渲染 pending
   - 根因：React baseline 缺 + new framework contract (`screen-route`) 要求两边都有 real launch capture
   - 解锁路径：@AD-4 #230 范围外 → 新建 screens.home.* Android 重捕 + @Cindy #219 React baseline
   - owner 拍于 13:52（(A)+(B) 并行），新建任务待 owner 派

2. **Home pull-to-refresh 移除后的 loading 反馈**（@首页专修 #255 in_review）
   - @Cindy 与 @Codex-KMP-Developer 倾向 header 轻量 spinner (`Updating...` + 右侧 spinner)
   - 区分自动 onResume refresh（轻量 spinner）和用户显式 refresh（按钮 loading）
   - 还没 commit 落地，等 in_review 后 merge

3. **Realtime 在首页有路由但没有顶部 banner / 浮层**（@首页专修 #246 in_review @AD-4）
   - 1ad6fe4d 已经把 realtime 事件 route 到 notifications
   - 但首页 realtime 通知的展示 UX（顶部 banner / 浮层 / quiet hint）还没定

4. **对话流内容翻译**（@artin 13:34 在 #199 thread 提出 — 本版**新加 P0 gap**）
   - React 完整 `translationStore`：per-message `TranslationEntry`、`useTranslationStore`、`translationSettings.{available, effectiveLanguage, preferredTranslationDisplay}`、`MessageItem` 内 `translationIndicator` + showOriginal toggle + translatedContent 渲染
   - React MessageItem 内的 `translationIndicator` 顶部带 border-2 brutal style
   - KMP 现状：仅 `preferredTranslationDisplay` 设置项 (#78 closed)，translation engine / 缓存 / per-message entry / 切换 UI 全缺
   - 接 gap 路径：translationStore KMP 移植 + SlockMessageIndicator 同款 + composer 转译按钮（如果 React 有）
   - 优先级：**P0**，因为影响所有跨语言对话流

### P1 仍有缺口（来自 06-20 复核表，本版逐项核对）

| 缺口 | 06-20 状态 | 06-23 状态 | 备注 |
| --- | --- | --- | --- |
| Auth 分支（reset / email verify / invite accept / social callback / device / human setup） | 待分支验收 | ⚠️ 未推进 | 暂无 owner |
| Owner onboarding mobile flow | 待 | ⚠️ 未推进 | React 有 OwnerOnboardingModal |
| Members / Agent 高级动作矩阵（start/stop/reset/delete/workspace/skills/scopes/runtime config/integrations） | 待 | ✅ 部分进 #56 phase 2A；剩余 matrix 待验收 | agent detail store wired 但 actions matrix 未完整验收 |
| Computers 高级动作 | 待 | ✅ Settings computers share + reload + UI parity；剩余 matrix 待验收 | `ae463bed`、`fa9f2b1e` |
| Settings Appearance 字号 / preview 真实组件 | 待 | ✅ auto-save + 字号 (`a0b62e94`)；preview 真实组件待 | `00f8f700 typography probe router` 是探测, 不是真组件 |
| Settings Account social identities | 待 | ⚠️ 未推进 | |
| Settings Billing copy + 错误态 | 待 | ✅ variants parity (`#87`)；copy + 错误态 待 | |
| Settings Notifications 实机权限 | 待 | ⚠️ 未推进 | |
| Release Notes 独立入口 | P2 | ⚠️ 未推进 | |
| Announcements modal 显示 / dismiss 持久化 | P2 | ⚠️ 未推进 | realtime routed (#1ad6fe4d) 但 modal 待 |
| Reminders 入口 | P2 | ⚠️ 未推进 | |
| Markdown 扩展（Mermaid / outline / copy code / 复杂引用 preview） | P2 | ⚠️ 未推进 | 仅 trace + 性能优化落地 |
| 弱网 / 后台 / token refresh / 重连恢复 | P0 | ⚠️ 部分（`fedd5959` resume refresh） | 实机弱网测试待 |
| 文件 / 附件评论 product 化 | P0 | ✅ `44cac5d2` shared comment controls，comment count / ref chip UI 仍待 | `re:` chip 全局 + per-msg chip 渲染待 |
| Quoted permalink preview | P0 | ❌ 未推进 | #276 修了消息 context 加载，但 QuotedMessagePermalinkPreview UI 未移植 |
| Selected text quote | P0 | ❌ 未推进 | Kuikly Compose Text 缺 free selection range API（per #276 note） |
| Attachment upload 中 / 失败 / 重试 / 移除 | P0 | ✅ preview tiles + retry + remove；UI 仍待 | `726de259` + retry/remove 已存在 |

## P0：先做"证明和收口"的主链路（更新版）

### 1. 对话流内容翻译（新加）

**React 现状**：`packages/web/src/store/translationStore.ts` + `MessageItem.tsx:translationIndicator`

- `TranslationEntry` 按 message id 缓存状态：`pending / translated / failed`
- `translationSettings.available` 语言列表，`effectiveLanguage` 实际生效语言
- `translationEligible` per-message 判断（语言不在 available / 自己发的 / 没有目标语言时跳过）
- `MessageItem` 顶部 brutal style indicator：`Show translation / Show original` 切换
- `translatedContent` 渲染（替代原始 content）
- 翻译来源：服务端 API（推测 `POST /translations`），per-message cached

**KMP 现状**：
- `#78` `preferredTranslationDisplay` 设置项已闭环（`e18dd168`）— 仅 UI 选项
- 没有 `translationStore`，没有 per-message entry，没有 translation engine 调用，没有 SlockTranslationIndicator
- 没有 composer 转译按钮（React 是否有需要核查）

**接 gap 路径（建议）**：
1. 接入 `SharedTranslationStore`（含 entry + settings）
2. 接服务端 translation API（待 owner 确认 endpoint）
3. `SlockTranslationIndicator` brutal style + Show translation/Show original toggle
4. `MessageItem` 接 active translation entry 替换 content
5. Per-message lazy translation（不在 viewport / cached 时不调用）

**owner 建议**：@Codex-KMP-Developer（store + UI），@API-Integration-Developer（API contract）

**验收标准**：跨语言对话流（英文用户看中文消息 / 中文用户看英文消息）能在 brutal style indicator 处切换翻译，per-message cache 不重复调用，failed state 显示回退原文。

### 2. Connected Apps 全动作验收（沿用 06-20）

**进展**：仍待 owner 拍是否建专门任务做 matrix 验收。当前 main 已合入 logo upload / publish / secret card 路径（`#189`），剩余 owner/admin 权限分支 + secret copy + delete confirm + grants/requests 入口未完整验收。

**owner 建议**：@Cindy 拍是否新建任务（per 06-20 已建 #KMP #189，但仅 engineering 段，验收段未派）。

### 3. Activity / Inbox 动作矩阵（沿用 06-20）

**进展**：
- Home activity 入口 `SharedHomeActivityScreen` 已有
- `25c07178 fix(activity): refresh secondary home panels` — activity panel onResume refresh
- `1d22e44d fix(home): open activity actions on long press` — long press 触发 actions

**仍未闭环**：
- per-item Done 链路（markDone from row Check button）
- per-item Mark Read / Unread 入口
- Load more pagination in Activity UI
- Activity/Inbox 命名语义决策（"Activity" 还是改"Inbox"）

**owner 建议**：@Cindy 派 follow-up 任务（#275 已 done 工程段，验收段仍待）

### 4. Thread 附件 / 引用 / 上下文专项（沿用 06-20）

**进展**：
- 上传 pending → preview tile (`726de259`)
- Retry / Remove 已有
- `44cac5d2 fix(thread): reuse shared attachment comment controls` — comment controls 复用
- Quoted message context loading（`#276` 工程段 done）

**仍未闭环**：
- `QuotedMessagePermalinkPreview` UI 移植（React `QuotedMessagePermalinkPreview.tsx` 含 loading/unavailable/archived + author/channel/time/content/attachment label + click open）
- `re:` chip 在 message row 渲染 + 跳回 host message
- attachment comments panel 等价（panel + all/unresolved filter + resolve reaction + anchor jump + comment composer）
- selected text quote（Kuikly 限制 + 替代方案待 owner 拍）

**owner 建议**：@Codex-KMP-Developer（UI 移植），@API-Integration-Developer（`POST /attachments/:id/comments` 的 anchor/mentions 扩展）

### 5. Home / Search / Saved 视觉和行为收口（沿用 06-20）

**进展**：
- Home landing brand + menu + landing centered 已收口
- Home top actions parity 已
- Search / Saved 视觉 task #156-#158 done
- `screens.home.{index,activity,saved}` 仍 pending（new framework contract）

**仍未闭环**：
- screens.home.* 真 launch 合同 + React baseline（@artin 13:52 (A)+(B) 并行，待 owner 派新任务）
- Home realtime notification 顶部 banner UX（#246 in_review @AD-4）
- Home pull-to-refresh 移除后的 header loading UX（#255 in_review @Codex-KMP-Developer）

**owner 建议**：@Cindy #219 React baseline 补 + @AD-4 #230 范围扩展 screens.home.* Android 重捕 + @Codex-KMP-Developer #255 #246 header loading/banner

### 6. 弱网 / 后台 / token refresh / 重连恢复（沿用 06-20）

**进展**：
- `fedd5959 fix(shell): refresh visible route on resume` — 可见路由 resume refresh
- `a04af754 fix(home): refresh on app foreground` — app 前台 refresh
- `25c07178 fix(activity): refresh secondary home panels` — activity panel resume refresh

**仍未闭环**：
- 实机弱网 / 网络切换 / 飞行模式 / token refresh 边界
- Reconnect 重连后状态收敛测试
- 后台 → 前台 跨多分钟的 stale data 收敛

**owner 建议**：@QA-Tester 接实机弱网 + 后台测试，按测试结果开 P0/P1 任务。

## P1：重要管理面，按域验收（更新版）

### Auth 分支

仍是 06-20 状态，未推进。reset / email verify / invite accept / social callback / device / human setup 分支都需要 owner 拍谁接。

### Members / Agent 高级动作

进展：
- agent detail store wiring done (`#56` phase 2A)
- agent profile parity done (`#53`)
- agent activity log done

剩余：
- start/stop/reset/delete 动作矩阵完整验收
- workspace files 浏览 UX parity（`6a542147` 简化但 React 树状浏览 parity 待）
- skills/scopes/runtime config 表单 parity（React AgentWorkspace / AgentDetailPanel 仍待 KMP 移植）
- agent integrations 入口（React 有，KMP 未知）

### Computers 高级管理

进展：
- shared store + reload done (`ae463bed`)
- computer rename modal SlockUnifiedDialog done (`9e96cef3`)
- computer delete modal done (`48aefa11`)

剩余：
- rotate key workspace 入口 parity
- runtime models 完整 parity
- computer actions confirmation parity

### Settings Appearance

进展：
- auto-save instant (`a0b62e94`)
- typography probe router (`00f8f700`)

剩余：
- 真实 message preview 组件（不用假卡片）
- React copy / layout parity（font size 选项 / 持久化）

### Owner onboarding mobile flow

未推进。React 有 OwnerOnboardingModal，KMP 未确认入口。

## P2：后续专项（更新版）

- Release Notes 独立入口
- Announcements modal 显示 / dismiss 持久化
- Reminders 入口（agent detail / activity）
- Markdown 扩展：Mermaid / outline / copyable code block / 复杂引用 preview
- Native diagnostics / recovery polish
- Multi-column desktop search URL 移动等价

## 建议拆任务（更新版）

### Phase A：P0 证明与收口

1. **对话流内容翻译**（**新**）
   - 输出：SharedTranslationStore + SlockTranslationIndicator + MessageItem 接 entry + composer 转译按钮（如果有）
   - owner 建议：@Codex-KMP-Developer + @API-Integration-Developer

2. **screens.home.* 真 launch 合同 + React baseline**（@artin (A)+(B) 并行）
   - 输出：3 个 screens.home.* Android 真 launch capture + React real launch capture + manifest migrate 到 new contract
   - owner 建议：@AD-4（Android）+ @Cindy（React）+ Cindy manifest owner

3. **Home pull-to-refresh 移除后 header loading**（#255 in_review）
   - 输出：轻量 spinner + Updating 文案 + 自动 / 显式 refresh 区分
   - owner：@Codex-KMP-Developer

4. **Home realtime notification 顶部 banner**（#246 in_review）
   - 输出：banner / 浮层 UX + dismiss 持久化
   - owner：@Android-Developer-4

5. **Activity / Inbox 动作矩阵**（沿用）
   - 输出：per-item Done / Mark Read / Mark Unread / Load more / 命名决策
   - owner 建议：@Cindy 派 follow-up

6. **Thread 附件评论 + Quoted permalink preview**（沿用）
   - 输出：panel + comment composer + re: chip + QuotedMessagePermalinkPreview 移植
   - owner 建议：@Codex-KMP-Developer + @API-Integration-Developer

7. **#231-#237 视觉 parity backlog**（owner 拍做或关）
   - 7 task 是否全做 / 选做 / 关
   - owner：@Android-Developer-4（已声明未 claim）

### Phase B：P1 管理面验收

8. Agent detail 高级动作 matrix 验收
9. Computers 高级动作 matrix 验收
10. Settings Appearance 真实 preview 组件
11. Auth branch pages parity
12. Owner onboarding mobile flow
13. Settings Account social identities

### Phase C：专项体验

14. Release notes / announcements / reminders
15. Markdown 扩展（Mermaid / outline / copy code / quote preview）
16. Native diagnostics / recovery polish
17. Realtime 弱网 / 后台 / 重连 实机测试

## 扫描依据

React 源：

- `third_party/slock-source/packages/web/src/store/translationStore.ts`（**新增**）
- `third_party/slock-source/packages/web/src/components/message/MessageItem.tsx:translationIndicator`
- `third_party/slock-source/packages/web/src/components/message/QuotedMessagePermalinkPreview.tsx`
- `third_party/slock-source/packages/web/src/components/message/AttachmentCommentsPanel.tsx`
- `third_party/slock-source/packages/web/src/components/agent/AgentDetailPanel.tsx`
- `third_party/slock-source/packages/web/src/components/machine/MachineDetailPanel.tsx`
- `third_party/slock-source/packages/web/src/components/settings/SettingsPanel.tsx`
- `third_party/slock-source/packages/web/src/components/search/MessageSearchPage.tsx`
- `third_party/slock-source/packages/web/src/components/thread/ThreadsInbox.tsx`
- `third_party/slock-source/packages/web/src/components/saved/SavedPanel.tsx`
- `third_party/slock-source/packages/web/src/components/message/*`
- `third_party/slock-source/packages/web/src/store/*`

KMP/Android 源：

- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsHomePage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SharedInboxPanel.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockHomeComponents.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadPage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/ThreadFilesContent.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/MembersPage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/SettingsComputersPage.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SharedSettingsStore.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/SettingsRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/data/MemberRepositoryDataSource.kt`
- `compose/shared/src/commonMain/kotlin/ai/slock/compose/network/realtime/*`

## 与 06-20 版的差异

- 新增 1 个 P0 gap：**对话流内容翻译**
- 关闭 50+ 工程 commit 涉及的 gap（列在 06-20 → 06-23 增量表）
- 移除"待证明"歧义：已 closed / 已验证 / 仍待 三档分开
- 新增 7 个 visual parity backlog (#231-#237) 作为 Phase A 候选
- 新增 #244-#255 9 个非视觉 backlog 作为 Phase A 候选
- screens.home.* 在新 "Screens Parity" framework 下 pending 解锁路径已拍
- Phase A/B/C 任务清单重排，新增翻译任务

## 后续维护

- 本 doc 维护节奏：每 3 天或每 30+ commit 刷新一次，或 owner 显式要刷新
- 格式不变：P0/P1/P2 + 已闭 / 在验 / 仍待 + owner 建议
- owner：@Product-UX-Designer