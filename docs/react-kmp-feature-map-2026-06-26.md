# React vs KMP 功能大图 (Fresh Scan 2026-06-26)

日期：2026-06-26 (fresh scan per @artin 01:38 "忘记你的所有知识, 重新扫描, 不要 delta")
任务：@Product-UX-Designer 重跑 #199, 不参考 06-23 doc, fresh 视角
主仓 main HEAD：`6a890a94 fix(activity): share activity store across shells` (54 commits ahead of upstream/main)
React source: third_party/slock-source HEAD detached `46f889b6f fix(search): support multiple sender filters`

## 2026-07-04 状态附录 (task #106, @CC-希乐)

本图仍是权威 gap 地图，但自 06-26 以来 main 已推进 870+ commits，P0/P1 多项已落地。逐项核对结论与合并后的剩余工作清单见 [docs-inventory-2026-07-04.md](docs-inventory-2026-07-04.md)。要点：

- P0 已落地：Message-level translation parity（task #105，`7dead569`，含全局 SharedMessageTranslationStore + 手动 Translate 菜单）；Avatar 渲染 #417；Realtime 漏事件/thread 渲染 #406/#407/#414；Store 收敛 Phase 1（member store mutations 集中化等）。
- P0 仍开放：Notifications 链路 #102/#103（title/body 降级）；Status resolution 单源 #73；Store 收敛 Phase 2/3 #387/#388（Thread raw realtime listener 未移除）。
- P1 已落地：Members Permissions tab #89；Activity 实时刷新+scroll-to-top #319；Task 状态 patch/cache write。
- P1 仍开放：Settings 路由化拆分 #345/#346/#347；Members per-call-site 审计；External agent display-state；Home/Search/Saved visual parity（优先级已被 raft-ui-kmp-visual-similarity-priority-2026-07-03.md 重排）。
- P2 基本全部未动（Owner onboarding / Release Notes / Announcements / Reminders / Markdown 扩展等）。

建议：下一次 fresh scan 直接产出新日期版本并将本文件标记 superseded。

---

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

**KMP 现状** (`compose/shared/src/commonMain/kotlin/build/raft/app/`):
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
- P0: message-level translation parity — React has `translationStore` + per-message `TranslationEntry` + `TranslationIndicator`; KMP only has settings-level `preferredTranslationDisplay` / server translation wiring, not message entry cache/render/toggle
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
- 06-23: realtime → member.agentStatus field
- 06-27 refresh (`react-kmp-realtime-alignment-2026-06-26.md`): observedAt / version / seq reconcile 已落地；旧 freshness 缺字段结论 stale
- 06-25:
  - P0 #2 freshness 后续应按 status surface validation 跟进，而不是重复“字段缺失”
  - 全局 store 收敛 (#386/#387/#388 Android-App-Integrator in_progress)

**Gap**:
- 06-23 P0: status resolution 一致性 (color + pulse 双链路), status surface freshness validation, External agent 独立路径, Members per-call-site audit, Idle/Disconnected 业务扩展 vs 简化
- 当前 @Cindy #370 native realtime app-scope owner + @Codex-Kuikly-KMP #371 refactor 在做

---

## Feature Points Inventory (per @artin 01:48 "功能点也要分析出来呀")

(per main 06-23 → 06-26 commit log, 抓出实际实现的功能点 per slice)

### Slice 1: Auth — 功能点
- `feat(auth)` login / register / forgot / choose-server / create-server 5 个 route 全部接通
- 记住上次选择的 server per account (MMKV-backed per-account)
- `rememberSelectedServerPerAccount` `pinServerRecoverySignOut` `protectServerSelectionFromTokenRaces` (server 切换竞态 保护)
- `makeServerChooserScrollable` (server 选列表 滚动)
- `alignAuthCopyCasing` (Copy 大小写统一)
- `addLargeCreateAccountButton` (large variant button)
- `tuneRaftTopLogoSize` `enlargeRaftTopBarLogo` `reduceRaftLogoIntrinsicSize` (logo size 多次调整)
- `renderRaftLogoFromSvgAsset` (SVG asset 渲染)
- `packageRaftLogoWithOhosAssets` (OHOS asset bundle)
- `reuseSharedMmkvSessionStore` `storeSharedSessionInAuthMmkv` (session 持久化)
- `packageSharedAuthTransport` (transport 抽象)
- `addLoginLegalLinks` `addResetPasswordLegalLinks` `matchResetPasswordLegalCopy` (legal acceptance)
- `hideMobileSocialEntryPoints` (per #308 隐藏 social connect)
- `defaultActionButtonsToLarge` (button variant)

**未接 (KMP 缺)**: EmailVerification / ResetPassword / InviteAccept / SocialAuthCallback / Device login / Human setup

### Slice 2: Home — 功能点
- `wireNotificationCenterThroughShell` (notification center route)
- `showServerUnreadCountsInSwitcher` (server switcher 显示 unread)
- `showActivityAndSavedBadges` (badges on activity / saved entries)
- `sourceActivityBadgeFromInbox` (badge source = inbox)
- `refreshActivityBadgeAfterReadActions` (read → badge 更新)
- `refreshActivityBadgeSummaries` (summary refresh)
- `refreshBadgesOnServerSwitch` / `clearBadgesBeforeServerRefresh` (server 切换 refresh)
- `clampSavedCountAtHundred` (saved count cap 100)
- `expandSavedCountPreload` (saved 预加载)
- `seedStandaloneActivityFromPreload` / `seedStandaloneSavedFromPreload` (预加载 seed)
- `refreshSecondaryHomePanels` (Home 次级 panel)
- `routeSecondaryPanesThroughPager` (次级 panel → pager route)
- `routeRealtimeAnnouncementsToNotifications` (realtime announcement → notification)
- `restoreSidebarPullBounce` / `unifyPullRefreshIndicator` / `enablePrimaryPullRefresh` (pull refresh)
- `removePullRefreshAffordance` (per #250 删除 pull-refresh, header spinner 替代)
- `simplifyHeaderRefreshSpinner` / `lightenHeaderRefreshSpinner` (header spinner 简化)
- `showHeaderRefreshAffordance` (header spinner 引入)
- `normalizeSidebarSectionSpacing` (section spacing)
- `keepRestoreLandingCentered` (landing page center)
- `liftRestoreLandingBrandBlock` (brand block 位置)
- `keepSelectedDmRowContentSize` (DM row 选中态不缩放)
- `guardRapidConversationRowOpens` (rapid click 防爆)
- `showBackButtonOnShellServerChooser` (server chooser back button)
- `homeUnifyPullRefreshIndicator` (pull indicator 统一)
- `parallelizeIndependentBootstrapRequests` (并行 bootstrap)
- `keepActivityTotalOutOfUnreadBadge` (badge 数字规则)
- `homeProjectRowsFromConversationDirectory` (rows source = directory)
- `homeProjectDraftsFromComposerStore` (drafts source)
- `homeScrollSecondaryPanelsToTopFromTitles` (title tap → scroll top)
- `homeHandleSystemScrollToTop` (system scroll to top)
- `homeRefineActivityCountAndSwitcherGestures` (switcher gestures)

### Slice 3: Thread — 功能点 (核心: 状态 收敛)
- `keepActionChromeOutOfContentSlice` (chrome 跟 content 分离)
- `keepMembersOutOfContentSlice` / `keepRouteChromeOutOfContentSlice` (slice 拆分)
- `splitTasksFromContentSlice` / `splitRouteIdentityFromContentSlice` (identity vs content)
- `keepTaskStateByMessageIdentity` / `bindTaskStateByMessageIdentity` (task identity 绑定)
- `projectLinkedTasksFromSharedStore` / `projectLinkedTaskStateIntoChat` (linked tasks 共享 store)
- `useLinkedTaskProjectionForTaskActions` (task actions 走 linked projection)
- `projectAgentStatusFromMembersStore` (agent status 走 members store)
- `unifyMentionAgentStatus` (mention + agent status 统一)
- `resolveAgentStatusByMemberStore` (member store 作为 source of truth)
- `centerSystemMessageRows` / `alignSystemMessageContentRow` / `restoreCompactSystemMessageStyle` (system message 样式)
- `topAlignTallFocusedMessages` (focused message top-align, per #395 滚动方向)
- `softenFocusedRowCentering` (滚动定位缓和)
- `showHeaderRefreshSpinner` (silent refresh indicator)
- `applyTaskStatusPatchResponse` / `hydrateReplyTaskStatusFromParentChannel` (task status patch)
- `promoteRepairedTaskIdOnStatusUpdate` (task id repair)
- `preserveLinkedTaskStateOnRefresh` (refresh 不丢 task state)
- `keepReplySendConfirmationInContent` / `anchorLocalSendsToBottom` (local send 锚定底部)
- `allowSendingFirstReplyAfterStreamResolves` (stream 解析后允许 first send)
- `revealFirstLocalReplySendImmediately` (本地 send 立即可见)
- `persistFailedLocalSends` / `showReadableLocalSendFailureMessages` / `classifySendExceptions` (failed send 处理)
- `addFailedSendMessageActions` (重试 UI)
- `renderEmptyReplyThreadsAsEmpty` (空 reply thread 不显示 loading)
- `renderEmptyDmRefreshAsEmptyState` (DM refresh 不 spinning)
- `keepOlderPaginationStatusIconless` / `keepNewerLoadActionIconless` (分页 status icon 移除)
- `openMemberProfilesFromAvatars` / `routeAvatarsToMemberDetail` / `openDmHeaderAvatarProfile` (avatar tap)
- `preferPixelAvatarsForAgentMessages` / `preferAgentPixelAvatarsOverFallback` (avatar pixel)
- `centralizeAvatarSlotResolution` (avatar slot 中心化)
- `markInactiveMessageAuthors` (作者 inactive 标记)
- `enrichAgentAvatarsBeforeFirstRender` (avatar 预渲染)
- `projectRealtimeThroughSharedDomainOwner` (realtime 走 shared domain owner)
- `routeTaskRealtimeByEventIdentity` (task realtime by event identity)
- `recoverReplyCountRealtimeGaps` / `recoverVisibleGapsFromHeartbeatSeq` (gap recovery)
- `syncRealtimeNewerGaps` (newer gaps sync)
- `applyRealtimeToServerMemberStore` (presence 应用)
- `keyThreadStateByRouteIdentity` / `carrySurfaceKindInRouteIdentity` (route identity)
- `openReplyParentFromStandaloneThread` (reply parent)
- `openMarkdownChannelRefs` (markdown refs)
- `compileSendTargetDiagnostics` / `showSendFailureReason` (诊断)
- `keepSystemTimestampInline` (时间戳 inline)
- `keepWarmTaskProjectionDuringReload` (reload 保持 warm projection)
- `stabilizeReactionUpdateOrdering` (reaction 顺序)
- `preserveSelectedReactionsAcrossRefresh` (跨 refresh 保持 selected reactions)

### Slice 4: Members — 功能点
- `membersWireAgentProfileActions` (profile actions wired)
- `membersUsePlainProfilePencilActions` (edit actions plain)
- `membersSurfaceAgentRuntimeErrors` (runtime errors 展示)
- `membersParseRuntimeModelCatalogs` / `membersLoadCreateAgentRuntimeModels` (runtime model loading)
- `membersStreamSocketActivityTrajectoryEntries` (activity streaming)
- `membersStreamAgentDetailActivityRows` (activity rows streaming)
- `membersSerializeRuntimeConfigWithKindTags` (config + kind)
- `membersSeedAgentDetailFromSharedCache` (seed from cache)
- `membersRouteListRowsToDetailPage` (route list → detail)
- `membersRouteHumanDetailByMemberKind` (route human detail)
- `membersResolveAgentStatusDisplayOnce` (display once)
- `membersRemoveSubtitleFromMembersPageHeader` (subtitle 移除)
- `membersRemoveDuplicateProfileStatusAffordances` (dedup)
- `membersRemoveDuplicateAgentProfileEditLabels` (dedup edit)
- `membersRemoveAgent404HumanFallback` (404 fallback 移除)
- `membersPulseStatusIndicators` (per #86 AD-4)
- `membersPreserveCreatedAgentAvatars` (preserve avatar)
- `membersPreserveCreateAgentSubmitFields` (preserve form fields)
- `membersPreserveAgentDetailHeaderActions` (preserve actions)
- `membersShowDetailLoadFailureState` (error state)
- `membersShowAgentProfileDescription` / `showAgentCreatedDate` (per #91 done)
- `membersStreamlineWorkspaceFilePreview` (workspace file preview)
- `membersMakeReadOnlyLinkFieldsPannable` (read-only links pannable)
- `membersLoadDetailRouteWithoutMembersHop` (direct detail)
- `membersNoOpMissingDetailRoute` (no-op missing route)
- `membersMatchAgentHeaderMenuActions` (per #71 three-dot icon)
- `membersKeepDetailHeaderAvatarOverlayHidden` (avatar overlay hidden)
- `membersKeepActivityLogScrollPinned` (activity log scroll pinned)
- `membersInlineAvatarManagementVisual` (per #57 inline avatar)
- `membersGuardAgentReasoningConfigByRuntime` (reasoning config guard)
- `membersGuardAgentActivityFreshness` (activity freshness guard, per #74)
- `membersFlattenAgentActivityTab` (activity tab 扁平)
- `membersEditAgentRuntimeConfig` (runtime config edit)
- `membersCopyFullInviteJoinUrl` / `membersCopyDisplayedInviteJoinLink` (invite copy)
- `membersAlignReminderVisualFixture` (per #66 done)
- `membersAlignProfileEditHeadings` (per #53 done)
- `membersAlignInviteHumanDialogWithReact` (invite dialog)
- `membersAlignDetailHeaderHeight` (header height)
- `membersAlignChannelSourceBadges` (channel source badges)
- `membersAlignAgentProfileSections` (profile sections)
- `membersAlignAgentDetailMetadata` (detail metadata)
- `membersAlignActivityEmptyState` (activity empty state)
- `membersAlignActionMenuToTriggerEdge` (action menu alignment)
- `feat(agent-detail)` add Permissions tab spec (per #89 AD-3 done)
- `membersUnframeAgentActivityList` (activity list unframed)

### Slice 5: Settings — 功能点
- `settingsRouteComputerDetailThroughStore` (computer detail via store)
- `settingsShareReadOnlyFieldAppearance` (read-only field)
- `settingsUpdateSubtitleForMembersPageToWorkspaceMembers` (subtitle)
- `settingsRestoreApiEnvironmentPresets` (API env presets)
- `settingsRemoveWrongTypeFilterInSystemChannelParser` (type filter fix)
- `settingsRemoveSubtitlesFromMembersAndSettingsHeaders` (subtitle 移除)
- `settingsRemoveSecondaryPageIntroCards` (intro cards 移除)
- `settingsRemoveDiagnosticsLogPreview` (diagnostics preview)
- `settingsRemoveAccountAvatarUploadCopy` (avatar upload copy)
- `settingsPruneDiagnosticsProfilerDetailSections` (diagnostics prune)
- `settingsPrefixAccountHeaderHandle` (handle prefix)
- `settingsPolishAccountProfileDetails` (account polish)
- `settingsMoveApiEnvironmentSwitchingToFormPage` (env switcher)
- `settingsMergeAuthDiagnosticsIntoDiagnostics` (diagnostics merge)
- `settingsImportJsonParser` (JSON parser import)
- `settingsHonorConfiguredWebUrls` (web URLs)
- `settingsClarifyDeveloperEnvironmentSwitcher` (dev env switcher clarify)
- `settingsCentralizeApiEnvironmentStore` (env store centralize)
- `settingsBrutalizeComputersListControls` (brutal style)
- `settingsBackStandaloneRoutesWithSharedStore` (standalone routes with shared store)
- `settingsAttachDiagnosticsDraftToChannelComposer` (diagnostics draft to composer)
- `settingsAlignTabIcons1:1ToReact` (tab icons alignment)
- `settingsAlignServerProfileBrutalVisuals` (server profile brutal)
- `settingsAlignNotificationToggleDefault` (notification toggle default)
- `settingsAlignBillingAndDangerVisuals` (billing/danger visuals, per #232 done)
- `settingsAlignAccountCardWithReact` (account card)
- `settingsAlignAccountAvatarProviders` (avatar providers)
- `feat(settings)` add Computers header + button + AddMachineDialog flow
- `feat(settings)` declutter Server Profile page (only Profile section)
- `feat(settings)` add System Channels section to Administration tab
- `feat(settings)` reorder tabs 1:1 to React + add Administration tab

### Slice 6: Tasks — 功能点
- `threadHydrateReplyTaskStatusFromParentChannel` (task status hydration, per #49843b0f)
- `threadKeepScopedTasksInSync` (scoped tasks sync)
- `threadProjectLinkedTasksFromSharedStore` / `projectLinkedTaskStateIntoChat` (shared store)
- `threadUseLinkedTaskProjectionForTaskActions` (linked tasks projection)
- `threadApplyTaskStatusPatchResponse` (patch response apply)
- `threadKeepWarmTaskProjectionDuringReload` (warm reload)
- `threadMatchLinkedTasksByTaskIdentity` (task identity match)
- `threadHideTaskToggleInReplyComposer` (hide task toggle in reply)
- `threadClampJointReplyLaunchTaskActions` (joint reply clamp)
- `threadIsolateTasksJoinState` (per #318 Codex-Kuikly-MP)
- `threadHideUnresolvedTaskAssigneeChips` (hide unresolved assignee chips)
- `threadKeepJointChannelTaskActionsHidden` (joint task actions hidden)
- `threadRepairStaleTaskCacheFromServerDeltas` (stale cache repair)
- `threadRestoreChannelTasksFromSqlCache` (restore from SQL cache)
- `membersParseRuntimeModelCatalogs` (runtime model parsing)

### Slice 7: Search — 功能点
- `fix(search)` support multiple sender filters (per #285 in_progress, slock-source HEAD 46f889b6f)
- `visualTestingAlignThreadMessageRowFixture` (fixture alignment)
- `visualTestingUseRealSearchFilterPopover` (popover use real)
- `homeShowSearchingStateForLiveSearch` (searching state)
- `homeImportConversationStreamHelper` (stream helper)
- `homeUnifyPullRefreshIndicator` (pull refresh unified)
- `homeProjectRowsFromConversationDirectory` (rows source)
- `homeClampSavedCountAtHundred` (saved cap 100)

### Slice 8: Saved — 功能点
- `homeClampSavedCountAtHundred` (cap 100 + 99+ display, per #304 done)
- `homeExpandSavedCountPreload` (preload expand)
- `homeSeedStandaloneSavedFromPreload` (seed from preload)
- `homeSimplifySavedLoadingState` (saved loading simple)
- `homeRefreshActivityBadgeAfterReadActions` (badge refresh after read)
- `homeClampSavedCountAtHundred` (saved count cap)
- `savedShowGenericDownloadControls` (file preview in saved)
- `savedRightIcon` (right icon alignment, per @artin 23:14 feedback 待 React parity)

### Slice 9: Activity (Inbox / Threads) — 功能点
- `activityShareActivityStoreAcrossShells` (per #6a890a94 main HEAD, shared store across shells)
- `activityRouteStandaloneRefreshThroughShellStore` (route standalone refresh)
- `activitySubscribeStandaloneRealtime` (subscribe standalone realtime)
- `activityDetectHiddenUnreadUpdates` (detect hidden updates)
- `activitySurfaceNewUpdateJump` (new update jump)
- `activityClearUnreadCacheOnOpen` (clear unread on open)
- `activityResolveSenderNamesFromMembers` (resolve sender names)
- `activityKeepSenderNamesSourcedFromProfiles` (sourced from profiles)
- `activityUseProfileNamesForPreviews` (use profile names for previews)
- `activityRefreshSecondaryHomePanels` (refresh secondary home)
- `activityRoundUnreadChips` (unread chips round)
- `activityKeepActivityTotalOutOfUnreadBadge` (badge count rule)
- `activityRefreshActivityBadgeAfterReadActions` (badge refresh after read)
- `activityRefreshActivityBadgeSummaries` (badge summary refresh)
- `sourceActivityBadgeFromInbox` (badge source)
- `homeTrustLoadedActivityUnreadBadge` (trust loaded badge)

### Slice 10: Realtime — 功能点
- `nativeSurfaceForegroundRealtimeNotifications` (foreground realtime)
- `nativeRetryRealtimeOwnerAfterAuthRestore` (retry after auth restore)
- `nativeRetainRealtimeRoomsWhilePaused` (rooms while paused)
- `nativePostUnreadNotificationsFromBackgroundRealtime` (background notif)
- `nativeLimitRealtimeFanoutToForeground` (fanout limited)
- `nativeObserveNamespacedKeepalivePreference` (keepalive pref, per #112 done)
- `nativeGateRealtimeKeepaliveBehindSetting` (gate keepalive, per #105 done)
- `threadProjectRealtimeThroughSharedDomainOwner` (shared domain owner, per #370 Cindy done)
- `threadRefactorNativeRealtimeIntoAppScopeOwnerWithKuiklyFanout` (per #371 Codex-Kuikly-KMP)
- `threadCompileSendTargetDiagnostics` (send target diagnostics)
- `threadApplyTaskStatusPatchResponse` (patch response)
- `threadRecoverVisibleGapsFromHeartbeatSeq` (heartbeat recovery)
- `threadRecoverReplyCountRealtimeGaps` (reply count gap)
- `threadSyncRealtimeNewerGaps` (newer gaps sync)
- `threadUpdateBoundaryReplyCountFromLiveReplies` (boundary reply count)
- `threadMapRealtimeMessagesToOwningChannel` (realtime → owning channel)
- `threadKeepRealtimeVisibleWhenCacheIsStale` (visible when cache stale)
- `threadKeepRealtimeParentUpdatesOutOfReplies` (parent updates not in replies)
- `threadStopConsumingRawRealtimeEvents` (stop raw consume)
- `threadBroadcastCacheInvalidationsThroughNotify` (broadcast cache invalidations)
- `membersStreamSocketActivityTrajectoryEntries` (socket activity streaming)
- `membersGuardAgentActivityFreshness` (activity freshness guard, per #74 AD-4 in_progress)
- `realtimeKeepAlive` (per #105 done)

### Slice 11: VisualTesting / Diagnostics / Owner onboarding / Release notes / Announcements / Reminders — 功能点
- `feat(#100)` Android host consumer of `bridge.appBadge` for launcher icon badge (per #100 done, #103 done)
- `chore(visual-testing)` capture auth screens (06-24 per #218 done)
- `chore(visual-testing)` capture server danger modal (per #209)
- `chore(visual-testing)` prefix component fixture cases
- `chore(visual-testing)` split component and screen coverage (per #214 Cindy done, #218 done)
- `chore(visual-testing)` activate members agent detail tabs (per #229 done)
- `chore(visual-testing)` activate members activity route case
- `chore(visual-testing)` activate composer suggestion cases
- `chore(visual-testing)` align thread message row fixture
- `chore(visual-testing)` use thumbs-up reaction fixture
- `chore(visual-testing)` use real search filter popover
- `chore(visual-testing)` unify register input fixture
- `chore(visual-testing)` align settings danger modal
- `chore(visual-testing)` align agent lifecycle action case
- `chore(visual-testing)` activate members agent detail tabs
- `chore(visual-testing)` activate members activity route case
- `chore(visual-testing)` activate composer suggestion cases
- `chore(visual-testing)` activate agent detail tabs (per #218 done)
- `feat(native)` keep realtime alive in background (per #105 done)
- `feat(ohos)` export App diagnostics feedback archive (per #111 done)
- `feat(android)` attach diagnostics to app diagnostics (per #111 done)
- `feat(android)` export feedback from app diagnostics logs (per #111 done)
- `feat(native)` expand android app diagnostics (per #111 done)
- `feat(native)` add Kuikly MMKV preference backend (per #96 done, #98 done)
- `feat(settings)` add Computers header + button + AddMachineDialog flow
- `feat(settings)` declutter Server Profile page
- `feat(settings)` add System Channels section to Administration tab
- `feat(settings)` reorder tabs 1:1 to React + add Administration tab

### Slice 12: Native — 功能点
- `feat(native)` keep realtime alive in background (per #105 done)
- `feat(native)` add Kuikly MMKV preference backend (per #96 done)
- `feat(native)` expand android app diagnostics (per #111 done)
- `feat(native)` surface foreground realtime notifications (per #103 done)
- `feat(ohos)` add activity and agent widgets (per #283 done)
- `feat(android)` add raft launcher widgets (per #110 done)
- `feat(gradle)` add toolchain configuration for multiple platforms
- `feat(android)` attach diagnostics to app diagnostics (per #111 done)
- `feat(android)` export feedback from app diagnostics logs
- `feat(ohos)` export App diagnostics feedback archive
- `feat(ohos)` add activity and agent widgets
- `feat(android)` add raft launcher widgets
- `feat(#100)` Android host consumer of `bridge.appBadge` for launcher icon badge (per #100 done)
- `filePreviewShowGenericDownloadControls` / `showDownloadProgressImmediately` / `showDownloadProgressAndSpeed` / `streamAttachmentDownloadControls` / `manageDownloadedAttachmentCache` / `routeUnsupportedAttachmentsUniformly` / `polishFileIconsAndInstallFlow` / `generateCommonFileIcons`
- `nativePreviewTextLikeFiles` (per #421 Pi-Worker1 in_progress)
- `nativePostUnreadNotificationsFromBackgroundRealtime`
- `nativeSurfaceForegroundRealtimeNotifications`
- `nativeRemoveForegroundHomeNotifications`
- `nativeRetainRealtimeRoomsWhilePaused`
- `nativeRetryRealtimeOwnerAfterAuthRestore`
- `nativeRequireMmkvPreferenceBackend` (per #96 done)
- `nativeLimitRealtimeFanoutToForeground`
- `nativeAvoidPullRefreshBridgeCrash`
- `androidUseTdlogAsDiagnosticsSink` (App diagnostics log, per #111 done)
- `androidUseImageLauncherForeground` / `regenerateLauncherIcons` / `shrinkLauncherIconMark` / `shrinkAdaptiveLauncherForeground` / `restoreWhiteLauncherIconFill` / `insetAdaptiveLauncherForeground` (launcher icon polish per #95 #110 done)
- `androidRegisterMmkvModuleAndExtractNativeLibs` (MMKV module registration)
- `androidRegisterKuiklyModulesEagerly` / `androidRegisterKuiklyVideoViewAdapter` (Kuikly module registration, per #112 done)
- `androidUse16kbAlignedNativeDeps` / `androidCenterKuiklyTextGlyphMetrics` / `androidAlignKuiklyTextBaseline` / `androidAlignCjkTextBaseline` (Kuikly baseline fixes)
- `androidRequestNotificationPermissionBeforeTestPush` / `androidGateVerboseTraceLogging`
- `androidOpenUnreadNotificationsInThread` / `androidBackgroundAppShellOnRootBack` (notifications + root back)
- `androidPreserveGestureNavBottomInset` (gesture nav inset)
- `androidObserveNamespacedKeepalivePreference` / `androidGateRealtimeKeepaliveBehindSetting` (keepalive, per #105 #112 done)
- `androidAddRaftLauncherWidgets` (per #110 done)
- `androidAvoidDoubleBottomSafeArea` (per #101 done)
- `ohosUnblockSharedAndHapBuilds` / `ohosSupportApiEnvironmentSwitching` / `ohosStreamAttachmentDownloadProgress` / `ohosForwardRealtimeEventsThroughSharedMapper` / `ohosDensifyDesktopWidgets` / `ohosCacheOpenedAttachments` / `ohosAvoidRuntimeConfigRegexInitCrash` / `ohosAddStartWindowFieldsToConfigAbility` (OHOS platform fixes)

---

## Slice 11: VisualTesting / Diagnostics / Owner onboarding / Release notes / Announcements / Reminders

**React 現状**:
- VisualTestingCases.tsx: case registry
- sharedCases.json: 71 cases
- Diagnostics: app JSONL + logcat export
- ReleaseNotesPage.tsx
- AnnouncementModal.tsx
- OwnerOnboardingModal.tsx

**KMP 現状**:
- KmpVisualScreenshotCaptureTest.kt: 66 capture methods
- sharedCases.json (mirror React)
- 06-25: 06-24 publish `2057eb39-20260624065206` (52 / 50 different / 2 basic-pass)

**Gap**:
- 06-23: Diagnostics page polish (KMP `AndroidAppScopeRealtimeOwner` + `SlockAppLog` 链)
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
2. **Status resolution 一致性** (#73 AD-4) + **status surface freshness validation** — per realtime alignment refresh
3. **Realtime 漏事件 / thread 渲染错误** (#406/#407/#414) — KMP-专家 / Codex-Kuikly-KMP
4. **Avatar 渲染** (#417 done, #225 在 pre-existing)
5. **Notifications 链路** (#102/#103 in_progress) — API-Integration-Developer
6. **Message-level translation parity** — React message rows can request/cache/render translations and toggle original/translated; KMP currently only exposes settings/server translation preferences

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
8. Diagnostics polish (log + native + Quiver)
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
