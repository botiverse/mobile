# React vs KMP 功能大图

日期：2026-06-19
更新：2026-06-20 复核版
任务：#product task #199
负责人：@Product-UX-Designer

## 结论

@artin 追问是对的：上一版把很多“已有基础实现，但还没做完整 parity 验收/边界确认”的项写成了“没有”，表达过粗。

这版先纠正口径：

- KMP/Android 现在不是“只有壳”，也不是缺一堆大页面。Home、Thread、Tasks、Members、Settings、Search、Saved、Activity、Computers、Connected Apps、Appearance 等多数主入口和数据链路都已经有。
- 真正要区分的是三类缺口：
  1. **已实现，但需要 React 全动作/状态/视觉验收**：例如 Activity、Computers、Agent detail、Appearance、Settings Admin。
  2. **已实现主入口，但高级动作或边界还没证明齐**：例如 Connected Apps 的 OAuth client/logo/publish/secret/权限分支，Thread 附件评论/引用/上下文打开。
  3. **确实可能还没产品化**：例如 owner onboarding、部分 auth 分支、release notes、announcements/reminders 的独立入口。

所以后续不建议再开一个“追平 React”大任务，而是把下面每个域拆成“已有证据 + 具体缺口 + 验收标准”。

## 2026-06-20 复核更正

| 功能域 | 现在确认 KMP 已有 | 具体还缺/未证明 | 文档修正 |
| --- | --- | --- | --- |
| Connected Apps | Settings 已有 Connected Apps 入口；包含 Marketplace / Installed / My Apps / OAuth client 相关 UI；数据层有 marketplace/install/uninstall/client/logo/publish 等链路；`SharedSettingsStore` 有 install/uninstall intent | 需要逐项验收权限分支、OAuth client create/edit/delete 完整性、client secret 一次显示和复制风险、logo upload/delete、publish request、grants/requests、错误/空态/加载态、移动视觉 parity | 不再说“未见完整页面”，改为“页面和主链路已有，高级动作和状态矩阵待验收” |
| Activity / Inbox | Home 入口文案为 Activity；`SharedHomeActivityScreen` / `SharedInboxPanel` 已有；支持 Activity header、filter、列表、open thread anchor、unread/mentions/empty/error 等基础 | 需要证明 React Threads Inbox 的 mark read/unread、mark all read、done/undone、follow/unfollow、mentions filter、followed threads 语义是否全部可达；还要定名 Activity 是否等价 Inbox | 不再说“Activity/Inbox 未统一所以缺入口”，改为“Activity 已存在，缺动作矩阵和命名语义验收” |
| Files / Attachments | `ThreadFilesContent`、files API、thread files store、download/image preview 相关链路已有 | 仍要核对上传中/失败/重试/移除、attachment comments、selected text quote、message permalink/context、QuotedMessagePermalinkPreview 对应移动替代 | 不再说“文件列表/下载不清楚”，改为“文件/预览/下载已有，评论/引用/上下文仍是风险” |
| Members / Agent detail | Members screen、agent detail tabs、agent activity parser/repository/cache、create agent coordinator、agent actions 数据链路都有 | 需要按 React AgentDetailPanel 验收 start/stop/reset/delete、workspace files、skills/scopes、integrations、runtime config、activity 分页/刷新、敏感字段过滤 | 不再说“agent 高级面可能没有”，改为“高级面大部分链路存在，需矩阵验收” |
| Computers | `SettingsComputersPage` 和 repository/API 已覆盖 list/detail/add/rename/delete/rotate key/workspaces/runtime models 等 | 需要验收 React MachineDetailPanel 的动作可达性、确认弹窗、错误态、workspace 删除、runtime models 展示和权限分支 | 不再说“只是 API 有”，改为“页面和数据已有，动作/状态 parity 待验收” |
| Settings Appearance | Settings 已有 Appearance destination，包含 message font size / preview 方向 | 需要验收字体大小持久化、默认值映射、preview 是否用真实 message 组件、与 React copy/布局一致 | 不再说“KMP 未列 Appearance”，改为“入口已有，需细节验收” |
| Search / Saved / Home | 近期已经多轮按 React 修：DM rows、Search filters/sort/empty/shared input、Saved/Home 入口等 | 剩余以截图和行为回归收口，不再口头猜测 | 保留为 P0 ongoing visual/action pass |

## Connected Apps 逐项复核结论

结论先放前面：KMP `Connected Apps` 不是缺页面，也不是缺主链路。Settings 里已经有 Connected Apps 入口、三段 tab、client CRUD 方向、marketplace/install/uninstall/publish/logo/secret 数据链路。真正缺的是 React 高风险动作的交互闭环和安全确认。

React Connected Apps 的用户可见能力来自 `SettingsPanel.tsx` 的 `IntegrationsSection`：

- 非 owner/admin：显示 `Only server owners and admins can manage connected apps.`，不是只暴露错误 code。
- Marketplace：包含 built-in app band、search、category filter、listing card、listing detail modal。
- Listing detail：展示 publisher、homepage domain、callback domain、disclosure，并在详情里 install/uninstall。
- Built-in app：card 可打开详情，详情里可 `Open` homepage。
- Installed：marketplace app 可 uninstall；server-local private app 可跳到 edit。
- My Apps：registered app 可 edit/delete；private/rejected 状态可 request publish。
- Register/Edit app modal：覆盖 logo、name、homepage、client id、return URL、agent manifest URL、description、publish category、declared data access。
- Client secret：create 后只显示一次，并提供 Copy 按钮。
- Logo：编辑状态下通过文件选择器上传，支持 png/jpeg/gif/webp，并可 reset。
- Delete registered app / uninstall marketplace app：都走 `ConfirmDialog`，文案说明 OAuth client、secret、grants、access revoke 影响。

KMP 当前确认已有：

- `SettingsConnectedAppsContent` 已有 `Marketplace / Installed / My Apps` segmented tabs。
- `SettingsConnectedAppsBuiltInCard`、`SettingsConnectedAppsMarketplaceCard`、`SettingsConnectedAppsInstalledCard`、`SettingsConnectedAppEditorCard`、`SettingsConnectedAppClientsCard` 已存在。
- `SettingsRepositoryDataSource` 已接 `/integrations/clients`、`/integrations/built-in`、`/integrations/marketplace`、install/uninstall、client create/update/delete、logo delete/upload、request-publish。
- `SharedSettingsStore` 已有 register/update/logo clear/delete/publish/install/uninstall action state。
- Create 后已有 `oneTimeClientSecret` 卡片。

KMP 当前明确缺口：

1. **Marketplace 缺 search/category filter 和详情层**
   现在 KMP Marketplace 基本是列表 + install/uninstall button。React 有搜索、分类、listing detail、disclosure、domain 信息。移动端可以做成详情页或弹窗，但不能只在列表上直接执行所有动作。

2. **Built-in app 缺详情和 Open homepage**
   KMP built-in card 现在是 info row，不能进入 React 等价的 built-in detail，也没有 `Open` homepage 动作。

3. **Client secret 缺 Copy 动作**
   KMP 一次显示 secret 已有，但 `SettingsConnectedAppSecretCard` 只有文本展示，没有 copy affordance。需要增加 Copy，同时明确不写入 diagnostics/log。

4. **Logo upload host picker 未接通**
   repository 已有 upload API，但 `SharedSettingsPlatformCapabilities.pickConnectedAppLogoFile()` 默认返回 `settings_connected_app_logo_picker_unavailable`。如果 Android/OHOS host 没覆盖该能力，页面上的 Upload/Change 会实际不可用。

5. **Delete / Uninstall 缺安全确认**
   KMP `SettingsConnectedAppClientsCard` 里的 Delete、marketplace Uninstall 目前是直接 action row。React 这里必须 ConfirmDialog，并说明删除 client secret/grants 或 revoke app access 的后果。这是安全/误触风险，不只是视觉差异。

6. **Permission copy 需要人话化**
   repository 会返回 `server_admin_required`，页面也有 NoPermission 分支；但需要对齐 React 的 owner/admin 文案，不应只显示错误 code。

7. **Action 状态要按对象收敛**
   install/uninstall/delete/logo/publish 的 loading/error 必须留在当前 row/对象上，失败不能吞错误，也不能刷新成成功态。

本轮不把 `grants/requests 独立 UI` 列为确定缺口：当前 React Connected Apps UI 里没有看到独立入口，不能凭旧文档继续猜测。

已创建实现任务：#KMP task #189。

## Activity / Inbox 逐项复核结论

结论先放前面：按 @artin 的理解处理，KMP `Activity` 与 React `/inbox` 视为同一产品能力，只是 React URL 仍叫 `/inbox`。这点不是缺口。

React `/inbox` 的用户可见能力来自 `ThreadsInbox.tsx` + `inboxStore.ts`：

- Header 标题也是 `Activity`，URL/path 叫 `/inbox`。
- Filter：All / Unread / Mentions。
- `Mark all read`：调用 `/channels/inbox/read-all`。
- Row click：先 `markRead(item)`，再按 channel/dm/thread 打开目标；thread 使用 `openThread(parentChannelId, parentMessageId, focusMessageId)`。
- Row 右侧 Check：不是打开，而是 `markDone(item)`；thread 调 `/channels/threads/done`，channel/dm 调 `/channels/inbox/done`。
- Context menu：Mark as Read / Mark as Unread + Done。
- 分页：滚到底部继续 `loadInbox`，offset 基于当前 items。
- Mentions filter 语义：历史 mentions 也可见，不只 unread mentions。

KMP 当前确认已有：

- `SharedHomeActivityScreen` / `SharedInboxPanel` 是真实页面，不是 placeholder。
- Header 标题 `Activity`，统计 `active/unread`。
- Filter 已有 All / Unread / Mentions，并直接传 `InboxFilter.wireValue` 给 `/channels/inbox?filter=...`。
- `Mark all read` 已有，调用 `RealReadReceiptRepository.markInboxReadAll()`，底层是 `/channels/inbox/read-all`。
- Row 可打开 channel/dm/thread；thread identity/open anchor 近期已修复并有测试覆盖。
- Empty/loading/error 基础状态已有。

KMP 当前明确缺口：

1. **Row 右侧 Check 按钮语义错误**
   现在 `SharedActivityRow` 右侧 Check 调的是同一个 `onClick`，实际效果是打开 item。React 这里是 `Mark as Done`。这属于功能缺口，不是视觉差异。

2. **Activity 没有 per-item Done 动作链路**
   KMP 底层 `ConversationRepository` 已有 thread `markThreadDone(threadChannelId)`，但 Activity 页面没有接入；对 channel/dm 的 `/channels/inbox/done` 在 KMP data layer 没看到对应 repository 方法。需要新增 Activity/Inbox action repository 或扩展 `InboxRepository`。

3. **Activity 没有 per-item Mark as Read / Mark as Unread 入口**
   KMP `ReadReceiptRepository` 已有 `markChannelReadAll(channelId)` 和 `markChannelUnread(channelId)`，但 Activity row 没有长按/菜单入口。React 右键菜单提供这两个动作。

4. **Activity 打开 row 是否同步清 unread 未在 Activity 层显式实现**
   React row click 先 `markRead(item)` 再打开。KMP Activity 只是 `onOpenMessage(item.toHomeMessageOpenRequest())`。后续进入 Thread/Channel 可能有 read receipt，但 Activity 本身没有等价的乐观更新/回滚逻辑，需要端到端验证；建议和 per-item read 动作一起修。

5. **Activity UI 未接入 load more**
   `InboxRepository` 有 `loadMore(...)`，但 `SharedActivityList` 只渲染 `snapshot.items`，没有滚动到底部触发 offset pagination，也没有 loading-more footer。React `/inbox` 有无限加载。

KMP 可暂不照搬的 React 桌面/网页细节：

- Header 双击跳下一个 unread：这是 desktop mouse affordance，移动端可以不做。
- Context menu 的触发方式：移动端可以用长按或 row overflow，不必照搬右键。
- New update sticky badge：可作为后续体验项，不阻塞本次 P0 parity。

因此 Activity/Inbox 的复核结论是：**核心页面、filter、mark all read、打开 thread 已有；缺的是 row 级 triage 动作和分页。** 这需要新建实现任务。

## 功能总图

| 功能域 | React 当前能力 | KMP/Android 当前状态 | 具体缺口判断 | 建议优先级 |
| --- | --- | --- | --- | --- |
| Auth 登录注册 | login/register/forgot/reset/email verify/social callback/invite accept/device login/human login setup/server selector | login/register/forgot/auth diagnostics/server gate/choose server/create server 已有 | reset/email verify/invite accept/social callback/device/human setup 是否完整产品化仍需分支验收 | P1 |
| Server 选择/创建 | last server redirect、server selector、create server、community join/agreement、invalid server fallback | choose server/create server 已在修并接近 React；community join/agreement 未见完整移动入口 | server 基础可用；community/invite 分支可能缺 | P1 |
| Home/Sidebar | left rail + sidebar；Search/Activity/Saved；channels/joint channels/DM；sort、server switch、notifications、create channel | mobile Home shell 已有 Search/Activity/Saved/channels/joint/DM/sort/server switch/notification/create channel | 基础齐；剩余是 visual parity 和移动栈行为，不搬 desktop rail | P0 ongoing |
| Channel/DM Thread | channel/dm route、message list、composer、thread overlay、profile overlay、message permalink/context | Thread 主链路、composer、thread stack、actions、saved、tasks、attachments、files 数据链路已有 | 附件评论、引用、上下文/permalink、文本选择、翻译等增强需专项验收 | P0/P1 |
| Message composer | text、attachments、as task、send state、action cards、mentions/autocomplete | composer as-task/disabled send 已修；action cards/coordination 边界已有 | mention/autocomplete、selected text quote、attachment comment、上传失败态需核对 | P1 |
| Tasks | server tasks、channel tasks、create/claim/unclaim/status/delete、task thread | SharedTasksScreen + NetworkSlockTasksApi 已接；thread open 已有 | 需要与 React task board 的过滤、创建、状态动作、空态/权限态做完整核对 | P0/P1 |
| Members | members root、agent/human detail、create agent、agent status/start/stop/reset/delete/activity/workspace/scopes/integrations | SharedMembersScreen、RealMemberRepository、agent activity、create agent coordinator 已有 | 高级动作/工作区/skills/scopes/integrations/runtime config 需要矩阵验收 | P1 |
| Computers | computers list/detail、add/rename/delete/rotate key/workspaces/runtime models/computer actions | SettingsComputersPage + API/repository 已有 | 动作完整性、确认/错误/权限状态、runtime models 与 workspace 行为待验收 | P1 |
| Search | full message search、filters、sort、entity results、desktop master-detail open slot、URL query | SharedHomeSearchPanel + RealSearchRepository 已有；近期已按 React 修 filters/sort/shared input/empty | mobile 核心可用；剩余用同宽截图和打开结果行为验收 | P0 ongoing |
| Saved | saved messages list/load more/unsave/open original | SharedHomeSavedPanel + RealSavedRepository 已有 | 核对 React SavedPanel 视觉、unsave/open original、load more、空态/错误态 | P0/P1 |
| Activity / Inbox / Threads inbox | followed threads inbox、unread/all/mentions filter、mark read/unread、mark all read、done/undone/follow/unfollow | Home 入口为 Activity；SharedHomeActivityScreen/SharedInboxPanel 已有 | 不是缺页面，而是缺动作矩阵证明和 Activity/Inbox 命名语义决策 | P0 |
| Settings Account | profile/display name/avatar/password/social identities/sign out | Account/Profile/sign out 有 | social identities/link provider/avatar upload 需核对 | P1 |
| Settings Language/Region | language、translation display、timezone、time format | Language & Region 数据和更新链路已有 | UI 覆盖、保存/错误状态、默认值映射需验收 | P1 |
| Settings Appearance | message font size + preview | Appearance destination 已有，方向是 message font size / preview | 字号持久化、preview 真实组件、React copy/布局 parity 待验收 | P1 |
| Settings Notifications | web push enable/disable/test、server mute、browser permission/PWA | KMP 有 notification preference/server mute/permission/settings/test notification | Android 通知权限、系统设置跳转、测试通知反馈需实机验收 | P1 |
| Settings Server/Admin | profile、pending invites、join links、onboarding、translation、sidebar order、roles/remove、leave/delete | Server profile/invites/join links/onboarding/translation/sidebar order/member role/remove/leave/delete 数据和 UI 代码已有 | 管理面基础接近；需按 React admin tab 做验收扫描 | P0/P1 |
| Settings Billing | plan/usage/limits/subscription | SettingsBillingPage + SettingsBillingSnapshot 已有 | plan/trial/downgrade/usage copy 和错误态需核对 | P1 |
| Connected Apps | marketplace/installed/my apps/OAuth client CRUD/logo/publish/install/uninstall/grants/requests | Connected Apps 页面和 marketplace/installed/my apps/client 相关链路已有 | 高级动作、权限、secret/logo/publish/grants/requests、状态矩阵待验收 | P0 |
| Release Notes | release notes routed page | KMP About/Diagnostics 有替代信息，未确认独立 Release Notes 入口 | 独立入口和内容源可能缺 | P2 |
| Owner onboarding | owner setup modal：server settings、join link、invites、notifications | 未见明显移动 owner onboarding modal | 新 server/owner 首次体验可能缺 | P1 |
| Announcements | active announcement modal/dismiss | KMP HomeDataSource 接 announcements/reminders | 需核对移动是否弹出、dismiss 是否持久化 | P1 |
| Reminders | agent reminders section / announcements-reminders API | AnnouncementsRemindersApi 有；页面入口不清 | 需要按 agent/detail 或 activity 场景验收 | P2 |
| Markdown/messages rendering | markdown, code block copy, mermaid, outline, quoted permalink preview | KMP 有 SlockMarkdown | Mermaid/outline/copy/code/quote preview 覆盖需 markdown 专项核对 | P2 |
| Files/attachments | channel files panel、attachment url/download、image lightbox、attachment comments | ThreadFilesContent、FilesMessagesApi、download/image preview 相关链路已有 | attachment comments、quote/context、上传失败/重试等仍是 P0/P1 风险 | P0/P1 |
| Realtime/recovery | socket connect/reconnect, route events, status reconcile, browser recovery | KMP App Shell 接 realtimeConnect/realtime event mapper/auth refresh/home/thread/settings refresh | 需要弱网、后台、token refresh、重连恢复测试 | P0 |
| PWA/Web-only | install prompt、browser site settings、visual viewport quirks、dev tools panel | Android 不应原样实现 | 映射到 native 通知/诊断/恢复即可 | 不搬 |

## P0：先做“证明和收口”的主链路

### 1. Connected Apps 全动作验收

React 对应：`SettingsPanel.tsx` 的 Integrations/Connected Apps 相关 section。

KMP 已确认：

- Settings 里已有 `Connected Apps` 目的地。
- 页面已有 Marketplace / Installed / My Apps / OAuth client 方向。
- 数据层有 clients、built-in、marketplace、install/uninstall、logo upload/delete、publish request 等链路。
- `SharedSettingsStore` 已有 install/uninstall intent。

真正要做的不是“从零补页面”，而是验收：

- Marketplace / Installed / My Apps 三段在 owner/admin/member 权限下是否一致。
- install/uninstall 成功、失败、loading、无权限状态。
- OAuth client create/edit/delete 是否全链路可达。
- client secret 是否只显示一次，copy 行为是否避免泄露。
- logo upload/delete、publish request、grants/requests 是否有移动入口。
- 空态、错误态、网络重试、权限文案是否符合移动端。

### 2. Activity / Inbox 动作矩阵

React 对应：`ThreadsInbox.tsx`、`inboxStore.ts`、`threadStore.ts`。

KMP 已确认：

- Home 入口叫 Activity。
- `SharedHomeActivityScreen` / `SharedInboxPanel` 已有 header、filter、list、empty/error/loading。
- 数据模型有 unread、mentions、latest activity、thread open anchor 等。

需要决策和验收：

- 移动端是否继续叫 `Activity`，还是改为 `Inbox`。
- 如果叫 Activity，它是否只等价 React Threads Inbox，还是包含更广 activity feed。
- All / Unread / Mentions filter 是否完整。
- mark read/unread、mark all read、done/undone、follow/unfollow 是否有明确入口。
- followed threads、channel/dm/thread/task 类型的打开行为是否都用同一 contract。

### 3. Thread 附件、引用、上下文专项

React 对应：`MessageInput.tsx`、Message item 组件、Channel Files、Attachment Comments、Quoted Message Preview、selection quote。

KMP 已确认：

- Thread 主链路、composer、message list、tasks/actions/saved 均已有。
- `ThreadFilesContent` 和 files API/store 已有。
- 附件下载、图片预览相关链路已经出现。

仍需核对/补齐：

- 上传附件、上传中、上传失败、重试、移除。
- 图片预览/lightbox 的移动体验。
- 文件列表/下载的权限、错误、空态。
- attachment comments。
- 选中文本 quote / reply 引用。
- message permalink/context 打开原消息。

这是消息体验主链路，不建议放到 P2。

### 4. Home / Search / Saved 视觉和行为收口

这些近期已经多轮修正，下一步不再口头猜测：

- 用 React + Android 同宽截图对 Home DM、Search、Saved、Activity 做 focused diff。
- Search filters 保持 enabled black-border clickable chip，这是最新 Product/UX 规则。
- Saved 重点验收 unsave、open original、load more、空态/错误态。

## P1：重要管理面，按域验收

### Auth 分支

KMP 已有 login/register/forgot/auth diagnostics/server gate。后续按分支核对：

- reset password。
- email verification。
- invite accept。
- social callback。
- device login / human login setup 是否需要移动 MVP。

### Members / Agent 高级详情

已有 Members、Agent detail、activity log 数据链路。下一步按 React AgentDetailPanel 验收：

- start/stop/reset/delete。
- activity log 分页/刷新。
- workspace files 浏览。
- skills/scopes/runtime config。
- agent integrations。
- 敏感字段过滤和错误态。

### Computers 高级管理

已有 SettingsComputersPage 和 API/repository。下一步验收：

- add/rename/delete/rotate key。
- workspace list/delete。
- runtime models。
- computer actions。
- loading/error/no permission/confirmation。

### Settings Appearance

已有入口。下一步验收：

- Message font size 保存、恢复、默认值映射。
- 真实 message preview，而不是假卡片。
- 和 React Appearance copy/布局一致。

### Owner onboarding / 新 server 首次体验

这里仍是“可能确实缺”的项。React 有 OwnerOnboardingModal，KMP 还未确认移动对应入口。

建议移动 MVP：

- 新 server 创建后进入轻量 onboarding。
- 覆盖命名、邀请/链接、通知权限、完成/跳过。
- 不照搬 desktop modal，只保留移动栈流程。

## P2：后续专项

- Release Notes 独立入口。
- Announcements modal 显示与 dismiss。
- Reminders/agent reminders 页面。
- Markdown 扩展：Mermaid、outline、copyable code block、复杂引用预览。
- PWA/install/dev tools/browser recovery：Android 不照搬，只保留 native 诊断/恢复策略。
- Desktop-only multi-column search URL：移动端不需要原样实现，但搜索结果打开详情要有清晰返回栈。

## 建议拆任务

### Phase A：P0 证明与收口

1. **Connected Apps 全动作/状态验收**
   - 输出：Marketplace / Installed / My Apps / OAuth client / logo / publish / permission matrix。

2. **Activity / Inbox parity matrix**
   - 输出：命名决策 + filter + mark read/unread + mark all + done/follow/open thread 验收表。

3. **Thread attachment/quote/context audit**
   - 输出：上传/下载/preview/comments/quote/permalink/context 的“已有/缺失/需补”清单。

4. **Home/Search/Saved/Activity focused visual pass**
   - 输出：同宽截图差异表，避免继续凭印象判断。

### Phase B：P1 管理面验收

5. Agent detail advanced actions audit。
6. Computers advanced actions audit。
7. Settings Appearance parity。
8. Auth branch pages。
9. Owner onboarding mobile flow。

### Phase C：专项体验

10. Release notes / announcements / reminders。
11. Markdown extended rendering parity。
12. Native diagnostics/recovery polish。

## 扫描依据

React 源：

- `third_party/slock-source/packages/web/src/App.tsx`
- `third_party/slock-source/packages/web/src/components/layout/MainLayout.tsx`
- `third_party/slock-source/packages/web/src/components/layout/Sidebar.tsx`
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
