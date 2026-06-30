# React vs KMP Alignment: Thread Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Thread

包含: 对话流 / Thread (channel/thread) / 消息列表 / composer / 反应 / 提及 / 附件 / 引用 / task 集成

## React source of truth

`packages/web/src/components/thread/ThreadsInbox.tsx` (1 个文件) + `packages/web/src/components/message/` (15+ 文件)
- ChatPanel.tsx (~1000 lines)
- MessageItem.tsx (single message, reactions, attachments, mentions, replies)
- MessageInput.tsx (composer with attachments, mentions, action cards)
- AttachmentCommentsPanel.tsx / ImageLightbox.tsx
- QuotedMessagePermalinkPreview.tsx
- ThreadsInbox.tsx (Activity thread root)

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt` (~6000 lines, 主页面)
+ SharedThreadStore.kt / SharedThreadComposerDraftStore.kt / SharedThreadFilesStore.kt / SharedThreadHeaderContextStore.kt

## Feature points inventory (per main commit log 06-23 → 06-26)

(50+ features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 3)

主要类别:
1. **Slice 拆分** (15 commits): tasks/content/chrome 分离
2. **Identity 绑定** (8 commits): task state / route identity / message identity
3. **Realtime projection** (10 commits): shared domain owner, keep realtime visible
4. **Status 双链路修复** (3 commits): status resolution by member store
5. **Scroll + 渲染** (10 commits): center system message, top align tall focused, scroll-to-top
6. **Task integration** (8 commits): patch response, hydrate from parent channel, project linked tasks

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- 主链路 message list + composer + thread stack
- Message rendering: avatar, sender name, content, reactions, attachments, mentions
- Task integration: 任务状态 patch, hydrate from parent
- Realtime 推送: shared domain owner projection
- Linked tasks projection from shared store
- Center system message, top align focused
- Scroll-to-top with header spinner
- Local send persistence
- Empty reply thread shows empty state (not loading)

### ⚠️ 接近对齐 (P1 / P2)
- Send target diagnostics (06-25 added, per-thread)
- Reply timeline predicate unify
- Channel/thread/dm identity binding (per-route launch)
- Task id repair on status update
- Action chrome out of content slice

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Store 收敛 #386/#387/#388** (Android-App-Integrator in_progress)
   - 数据驱动走 "global store receives realtime event → canonical cache/store update → Activity projection observes same identity update"
   - 当前 KMP 还有 "Activity 页面 standalone route 本地临时 state" → 易漏 realtime
2. **Realtime 漏事件** (@AD-2 in_progress #385/#418)
   - 线程中收不到 realtime 数据
   - Channel 流渲染外部 (thread 数据) — 已修一次 (#417 done) 但根因 #418 在 audit
3. **Avatar pipeline** (#417 done, #225 pre-existing)
   - agent 头像 gravatar → pixel robot 闪烁
   - profile_merge_resolved_gravatar 日志噪音
4. **Reactions 闪** (@AD-2 in_progress)
   - 给某个 thread reactions 先展示然后瞬间消失
5. **Loading 状态问题** (@AD-2 in_progress)
   - 空白 thread 一直显示 loading 而不显示 "No replies"
6. **Message-level translation parity**
   - React `translationStore` provides per-message `TranslationEntry`, request/cache flow, `TranslationIndicator`, translated content rendering, and Show original / Show translation toggle
   - KMP has settings/server translation preferences, but no thread message translation entry cache/render/toggle path yet
7. **Conversation_directory_alias_conflict 日志噪音** (@KMP-专家 in_progress #414)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 占位符 / 工程改造)
- QuotedMessagePermalinkPreview UI 移植 (per #276 spec done, UI 未移植)
- re: comment chip per-msg 渲染
- attachment comments panel UI 等价 (per #276 spec done)
- Selected text quote (Kuikly Compose Text 缺 free selection range API)
- Thread presentation SSOT cleanup (per #406/#414 in_progress)
- Real-time local send anchoring (per "anchor reply sends to bottom")

## P0/P1/P2 缺口分类

### P0 (本轮必修, 多 in_progress)
- **Store 收敛** (Android-App-Integrator #386/#387/#388): 实时性, 多 bugs 根因
- **Status resolution 一致性** (#73 AD-4): spec v2 P0 #1
- **Status surface freshness validation**: observedAt / versions / seq fields have landed per realtime alignment; remaining risk is stale status surfaces consuming old snapshots
- **Realtime 漏事件** (#418 Codex-Kuikly-MP in_progress)
- **Conversation header SSOT** (#406/#414 KMP-专家/Codex-Kuikly-MP)
- **Avatar pipeline**: #417 done, #225 仍 pre-existing
- **Message-level translation parity**: message row translation request/cache/render/toggle not yet implemented in KMP

### P1 (体验 / 视觉)
- Quoted permalink preview UI
- re: comment chip + attachment comments panel
- Selected text quote (替代方案待 owner)
- Scroll-to-top indicator
- Per-row avatar / channel name fix
- System message center (per 06-25 done)
- DM avatar + tap-to-profile fix

### P2 (业务扩展)
- Thread perf optimization (per #AD-1 02:00: profiler proof-driven)
- Thread/Markdown scroll perf (#AD-2 02:00: traceMarkdownSection 33x)
- Reaction sprite atlas (per #397 AD-1)

## Owner 拍 (下一步)

- (i) 等 #386/#387/#388 store 收敛完成 (Android-App-Integrator owner), 再 verify 整体
- (ii) 等 #74 freshness reconcile 完成 (AD-4 owner)
- (iii) 等 #418 React flow 分析完成 (Codex-Kuikly-MP owner), 跟 spec v2 #68 P0 互相对齐
- (iv) owner 显式派

**PUX 不下 fix decision, 不写 code. 等 owner 拍后续 verify 时机 (06-29 / 07-02 跟 #199 节奏)**.

不动 source, 不动主仓 (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26
status: doc 落地, 等 owner 拍
based on: main HEAD `87d41a70`, slock-source HEAD detached `46f889b6f`
