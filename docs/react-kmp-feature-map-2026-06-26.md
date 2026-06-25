# React vs KMP 功能大图 (Fresh Scan 2026-06-26)

日期：2026-06-26 (fresh scan per @artin 01:38 "忘记你的所有知识, 重新扫描, 不要 delta")
任务：@Product-UX-Designer 重跑 #199, 不参考 06-23 doc, fresh 视角
主仓 main HEAD：`6a890a94 fix(activity): share activity store across shells` (54 commits ahead of upstream/main)
React source: third_party/slock-source HEAD detached `46f889b6f fix(search): support multiple sender filters`

## TL;DR

- 12 slice 拆片扫描 (per 09:38 plan)
- 整体: 主仓 54 commits ahead of upstream, ~50+ in-flight tasks across slices
- 主要 gap 类型: (a) 已实现但需 React parity verify, (b) 业务扩展 vs React 简化, (c) 工程改造中 (shared store 收敛), (d) Dead code/placeholder 待清理
- 实际 delta 跟 06-23 doc 相比: tasks 编号推进 (e.g. #199 → #271, #221 → #311 等), 但 surface 拆片结构保持一致

---

## Slice 1: Auth

**React 现状** (`packages/web/src/components/auth/`):
- LoginPage.tsx (217 lines): email/password + Google/GitHub social + Legal acceptance checkbox
- RegisterPage.tsx (139 lines): name/email/password + social + legal
- ForgotPasswordPage.tsx (94 lines): email → reset link
- ResetPasswordPage.tsx (124 lines): token + new password
- EmailVerificationPage.tsx (172 lines): verify code from email
- InviteAcceptPage.tsx (308 lines): token + register with invite
- ServerSelector.tsx (172 lines): last server / create / join / community
- SocialAuthCallbackPage.tsx (179 lines): OAuth provider callback

**KMP 现状** (`compose/shared/src/commonMain/kotlin/ai/slock/compose/`):
- AuthComponents.kt / AuthPages.kt / LoginContract.kt — 单文件 vs React 9 files
- 主要: login / register / forgot / auth diagnostics / server gate / choose server / create server
- 缺 (per `screens.auth.*` 走 capture): register / verify / invite / social callback / device login / human setup / reset

**Gap**:
- 06-23: 9 React routes vs KMP 5 routes (login / register / forgot / choose-server / create-server). 当前 @Cindy 已隐藏 Google/GitHub connect buttons (#308 future OAuth 任务)
- EmailVerification / ResetPassword / InviteAccept / SocialAuthCallback — KMP 没接, MVP 不需要, owner 拍
- Legal acceptance checkbox — React 必有, KMP 有但小, parity check 待

---

## Slice 2: Home

**React 现状**:
- Sidebar.tsx (~3000 lines): Home shell, channels/DM/joint, search/activity/saved entry
- MainLayout.tsx (~2400 lines): routes, navigation
- MessageSearchPage.tsx / SavedPanel.tsx / ThreadsInbox.tsx (Activity)

**KMP 现状**:
- SlockHomeComponents.kt / SlockAppShell.kt / SharedInboxPanel.kt
- 已知: search / activity / saved 都接 (per #221 / #230 history)
- 06-25 issue: pull-refresh 移除 + header spinner (#255 #244), realtime notifications (#246), mark read round-trip (#250)

**Gap**:
- 06-23: 基础齐, visual parity + mobile behavior (回主页 path, fresh load cache). 当前 owner 在 #244-#255 集中返修
- Home server switch 不刷新 unread (#315 done), DM row 选中态 (#250 done), saved 下拉刷新 (#284)
- @AD-2 在 #396 实现 Thread/Home API parallel loading

---

## Slice 3: Thread

**React 现状**:
- ChatPanel.tsx (~1000+ lines): message list, composer, attachments, mentions
- MessageItem.tsx: single message rendering, reactions, attachments, mentions, replies
- MessageInput.tsx: composer with attachments, mentions
- AttachmentCommentsPanel.tsx / ImageLightbox.tsx
- QuotedMessagePermalinkPreview.tsx
- ThreadsInbox.tsx (Activity thread root)

**KMP 现状**:
- ThreadPage.kt (~6000 lines): 主页面
- SharedThreadStore.kt / SharedThreadComposerDraftStore.kt / SharedThreadFilesStore.kt
- 06-23: 主链路 + composer + thread stack + actions + saved + tasks + attachments + files
- 06-25: 仍有问题 (avatars, store 收敛, realtime 漏事件, reactions 闪, loading 状态, route guard fallback)

**Gap**:
- P0: store 收敛 (Phase 1/2/3 tasks #386/#387/#388 Android-App-Integrator owner) — 实时性, conversation_directory_alias_conflict 噪音, profile_merge_resolved_gravatar 噪音
- P0: thread 渲染外部数据 (@artin 22:57 bug) — 修后 channel 流渲染 thread 数据 (#417 done)
- P1: QuotedMessagePermalinkPreview / re: comment chip / selected text quote (per 06-23 doc P1)
- P1: per-row avatar / channel name (@artin 23:07 bug)
- Owner 在 #378/#396/#418 集中返修 (parallel loading, status/data flow analysis, scroll-to-top)

---

## Slice 4: Members

**React 现状**:
- AgentDetailPanel.tsx (~1200+ lines): tabs profile/permissions/dms/reminders/workspace/apps/activity
- HumanDetailPanel.tsx (~600 lines): tabs profile (single tab for human)
- MembersList / ChannelMembersList
- CreateAgentDialog.tsx / PixelAvatar.tsx
- ConfirmDialog / StatusBadge / StatusDot

**KMP 现状**:
- MembersPage.kt (~6000 lines): 主页面
- SharedAgentDetailStore.kt (per #56 phase 2A AD-1)
- SharedCreateAgentDialog.kt / SharedInviteHumanDialog.kt
- AgentScopeSet data class (per AD-3 #89 spec)

**Gap**:
- 06-23: 高级动作矩阵 (start/stop/reset/delete / workspace / skills / scopes / integrations / runtime config) 待验收
- 06-25: 
  - #89 Permissions tab 06-25 完成 (AD-3 local main `51e9de2f`)
  - #73 status resolution 同源 (AD-4 owner)
  - #74 freshness reconcile (AD-4)
  - #86/#90 status indicator (size + pulse + avatar overflow, AD-3/AD-4 done)
  - #91 profile 底部按钮 (AD-3 in_review)
  - #92 runtime 拉不到模型列表 (AD-3 in_review)
  - #93 activity list 不实时 (AD-4 in_review)
  - screens.members.human.profile — 06-25 register (pending), PUX spec lane + AD-3 impl + AD-3 Android capture
  - P1 External agent display-state (#73 sub-task)
  - #67 Agent DMs tab / #66 Reminders tab (AD-3 done)

---

## Slice 5: Settings

**React 現状**:
- SettingsPanel.tsx (~2000+ lines): tabbed structure
- Account / Sessions / Language / Billing / Server / Workspace / Admin / Appearance / Notifications / Connected Apps / Danger

**KMP 現状**:
- SettingsHomePage.kt (~10000 lines): 主页面 (巨型文件)
- SettingsAccountPage.kt / SettingsBillingPage.kt / SettingsComputersPage.kt / SettingsStandalonePageData.kt / SettingsStandaloneStore.kt
- @Android-Settings-Integrator lane

**Gap**:
- 06-23: Account/Sessions/Language/Billing/Server/Workspace/Danger 已有
- 06-25: 
  - SettingsAppearance font size auto-save (done in v5 push #232/#233)
  - Billing variants parity (#87 done)
  - Settings Computers 改独立 route (#345 owner AD-4)
  - Settings 主 section 路由化拆分 (#346 owner AD-4)
  - Settings route provider/store 统一 (#347 owner AD-4)
  - Danger visual (#232 done)
- Connected Apps parity: per #189 done, secret copy / grants/requests / publish 待验收
- Owner onboarding mobile flow 缺

---

## Slice 6: Tasks

**React 現状**:
- TaskBoard.tsx / TasksPanel.tsx / TaskItem.tsx / TaskCard.tsx / CreateTaskDialog.tsx / StatusBadge.tsx / taskStatusUi.ts
- LegacyTaskPanel.tsx

**KMP 現状**:
- SharedTasksPage.kt (~3000 lines): board / list / create / status / assign

**Gap**:
- 06-23: 基础齐, status / assign / claim 待 React parity verify
- 06-25:
  - task 状态 patch 后 UI 不更新 (@AD-2 lane, cache write)
  - 任务状态回退到原始 unassigned (@AD-2 lane)
  - activity 进入 thread 状态错 (@AD-4 done #420)
  - task 标记失败 400 (@AD-4 in_review #293)
  - selected tab styles (Tasks Page task #250 done)

---

## Slice 7: Search

**React 現状**:
- MessageSearchPage.tsx (~2000 lines): message search + entity + filter + sort + load more
- searchComposition.ts / searchEntities.ts / searchGrouping.ts / searchRetry.ts

**KMP 現状**:
- SharedHomeSearchSavedStore.kt (含 search + saved + activity 三合一)
- SharedSearchRepository

**Gap**:
- 06-23: 基础齐, multi-select from filter 待 (#285 in_progress)
  - @KMP-专家 owner
  - Saved 右键菜单 (#259 缺)
- 06-25:
  - components.home.search 系列 visual testing 重制 (@AD-2 / @AD-4)

---

## Slice 8: Saved

**React 現状**:
- SavedPanel.tsx (in components/saved/)
- 跟 search / activity 共享 inbox-style UI

**KMP 現状**:
- SharedHomeSearchSavedStore.kt (saved 部分)

**Gap**:
- 06-23: list / load more / unsave / open. 缺 Copy Link / Copy Markdown 长按菜单
- 06-25:
  - right icon / 右键菜单 对齐 React (#259 缺)
  - 下拉刷新对齐 Activity (#284 todo @AD-4)
  - saved limit 100 + 99+ display (per #304 done)

---

## Slice 9: Activity (Inbox / Threads)

**React 現状**:
- ThreadsInbox.tsx (~1500 lines): inbox + filter + mark read/unread + done/undone + follow/unfollow + open thread

**KMP 現状**:
- SharedInboxPanel.kt (~2000 lines)
- SharedActivityStore.kt (per 6a890a94 `share activity store across shells`)

**Gap**:
- 06-23: 基础齐, per-item Done / Mark Read / Mark Unread / Load more 待 React parity
- 06-25:
  - 多个 bug: avatar 渲染, realtime 漏, status 不实时, channel→thread 渲染外部数据
  - 实时刷新 (after @AD-2 / @AD-4 返修)
  - scroll-to-top with ↑ x updates (#319 in_progress @Codex-KMP-Developer)

---

## Slice 10: Realtime

**React 現状**:
- agentStore.ts: agentActivityObservedAt / agentActivityVersions / agentActivitySeq
- updateActivity() serverSeq reconcile
- resolveAgentDisplayState() for status display

**KMP 現状**:
- RealtimeEventMapper.kt: wire `slockRealtimeEvent` → Activity
- SharedHomeStore.kt / ChannelRepositoryDataSource.kt
- 06-23: realtime → member.agentStatus field, 但没 observedAt / version / seq reconcile
- 06-25: 
  - P0 #2 freshness reconcile (task #74 AD-4 in_progress per spec v2)
  - 全局 store 收敛 (#386/#387/#388 Android-App-Integrator in_progress)

**Gap**:
- 06-23 P0: status resolution 一致性 (color + pulse 双链路), freshness reconcile, External agent 独立路径, Members per-call-site audit, Idle/Disconnected 业务扩展 vs 简化
- 当前 @Cindy #370 native realtime app-scope owner + @Codex-Kuikly-KMP #371 refactor 在做

---

## Slice 11: VisualTesting / Diagnostics / Owner onboarding / Release notes / Announcements / Reminders

**React 現状**:
- VisualTestingCases.tsx: case registry
- sharedCases.json: 71 cases
- Diagnostics: bugly + logcat export
- ReleaseNotesPage.tsx
- AnnouncementModal.tsx
- OwnerOnboardingModal.tsx

**KMP 現状**:
- KmpVisualScreenshotCaptureTest.kt: 66 capture methods
- sharedCases.json (mirror React)
- 06-25: 06-24 publish `2057eb39-20260624065206` (52 / 50 different / 2 basic-pass)

**Gap**:
- 06-23: Diagnostics page polish (KMP `AndroidAppScopeRealtimeOwner` + `SlockDiagnosticsLogger` 链)
- 06-25:
  - P0 status resolution (per spec v2 #68)
  - P0 freshness reconcile (per spec v2 #68)
  - screens.members.human.profile register (06-25 done, pending impl)
  - File preview MP4/text (#421-#425 Pi-Worker1)
  - Owner onboarding mobile flow
  - Release Notes independent entry
  - Announcements modal display + dismiss persistence
  - Reminders entry

---

## Slice 12: Native (appBadge / notification / widget / file preview)

**React 現状**: N/A (React is web, no native)

**KMP 現状**:
- AndroidAppScopeRealtimeOwner.kt: foreground realtime notifications
- AndroidUnreadNotificationPoster.kt: per-message notification posting
- bridge.appBadge: launcher icon number badge
- SlockFilePreview.kt: file preview panel
- File preview text/markdown/code (#421/#422 Pi-Worker1)
- Widget implementation (HarmonyOS small components, #283)

**Gap**:
- 06-23: Notification title/body 退化成数字 (per #102 #103 in_progress)
- 06-25:
  - 文件预览显示空白直到图片回来 (per #111 in_progress)
  - 文件预览下载进度/速度显示 (per #377 done)
  - HarmonyOS 小组件信息量 (per #382 todo)
  - Foreground keep-alive notification switch (per #113 todo)
  - KuiklyMMKV integration (per #96 done)

---

## P0/P1/P2 优先级总结 (Fresh 视角)

### P0 (本轮必修)
1. **Store 收敛** (Android-App-Integrator #386/#387/#388) — 实时性 / data projection 一致性, 多 bugs 根因
2. **Status resolution 一致性** (#73 AD-4) + **Freshness reconcile** (#74 AD-4) — per spec v2 #68
3. **Realtime 漏事件 / thread 渲染错误** (#406/#407/#414) — KMP-专家 / Codex-Kuikly-KMP
4. **Avatar 渲染** (#417 done, #225 在 pre-existing)
5. **Notifications 链路** (#102/#103 in_progress) — API-Integration-Developer

### P1 (体验 / 视觉)
1. Home / Search / Saved visual parity (#285/#286 done + ongoing)
2. Members Permissions tab (已 #89 done, 等 Android re-capture)
3. Members per-call-site audit (spec v2 P1 #4)
4. External agent display-state (spec v2 P1 #3)
5. Settings routes 路由化拆分 (#345/#346/#347 in_progress)
6. Activity 实时刷新 + scroll-to-top (#319 in_progress)
7. Task 状态 patch / cache write (multiple in_progress)

### P2 (后续 / 业务扩展)
1. Owner onboarding mobile flow
2. Release Notes independent entry
3. Announcements modal + dismiss persistence
4. Reminders entry
5. Reaction sprite atlas (#397 AD-1)
6. Tab swipe gesture (#112 AD-2)
7. Idle/Disconnected 业务扩展 vs 简化 (owner 拍)
8. Diagnostics polish (log + native + Bugly)
9. Markdown 扩展 (Mermaid / outline / copy code)
10. Native diagnostics file logging (#215 Codex-Kuikly-MP)

---

## 趋势 (跟 06-23 doc 比较)

| 维度 | 06-23 | 06-26 |
|---|---|---|
| 主仓 main HEAD | `6fda237c` | `6a890a94` (+54 commit) |
| 任务 board | #1-#285 | #1-#425 (+140 tasks) |
| Visual testing publish | run `4437f595` | run `2057eb39` |
| Owner gating | 21 项 | 30+ 项 |
| P0 真 bug | 2 (status 双链路 / freshness) | 5+ (store 收敛 + 实时性 + avatar + notification) |
| P1 体验 | 8 | 12 |
| P2 业务扩展 | 6 | 10 |

**整体观察**: 03 天 delta, 主仓 54 commit ahead. 任务数 +140, 但实际 surface 结构保持稳定. 主要 P0 shift: 从 "verify visual parity" → "fix runtime architecture (store 收敛 / realtime / avatar pipeline)". P1 跟 06-23 类似, 都是 visual + UX polish. P2 增加了一些新功能 (sprite atlas / swipe gesture / widget).

---

## Owner 拍 (下次刷新)

- (i) 我跑 06-29 同样 fresh scan (不参考本次 doc, 当新 scan)
- (ii) 我跟上次 06-23 一样, 加增量 delta (per @Cindy review 经验)
- (iii) Owner 显式拍

我倾向 (i): fresh scan 每次都重做, 防止 PUX 自我锚定过去结论. 30+ commit 刷新一次足够 (06-26 / 06-29 / 07-02 ...).

---

owner: @Product-UX-Designer
date: 2026-06-26 (fresh scan)
status: 扫描完成, 等 owner 派下次刷新节奏
based on: main HEAD `6a890a94`, slock-source HEAD detached `46f889b6f`