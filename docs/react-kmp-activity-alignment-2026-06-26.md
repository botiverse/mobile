# React vs KMP Alignment: Activity Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Activity (Inbox / Threads)

包含: inbox 列表 / filter (all / unread / mentions) / mark read/unread / mark all read / done/undone / follow/unfollow / open thread

## React source of truth

`packages/web/src/components/thread/ThreadsInbox.tsx` (~1500 lines):
- inbox 列表 (all + unread + mentions filter)
- `Mark all read` → `/channels/inbox/read-all`
- Row click → markRead + open channel/dm/thread
- Row right `Check` → markDone (NOT open)
- Right-click menu: Mark as Read / Mark as Unread + Done
- 分页: scroll to bottom → loadMore (offset-based)
- Mentions filter: 历史 mentions, 不只 unread

## KMP source

- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedInboxPanel.kt` (~2000 lines)
- `compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedActivityStore.kt` (per `fix(activity): share activity store across shells` 06-25)
- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedHomeStore.kt`

## Feature points inventory (06-23 → 06-26)

(16 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 9)

主要类别:
1. **Store 收敛** (3 commits): share activity store across shells, route standalone refresh through shell store, subscribe standalone realtime
2. **Unread badge 链路** (4 commits): trust loaded unread, refresh after read, badge summary, refresh secondary home
3. **Sender name 链路** (3 commits): resolve sender names from members, sourced from profiles, use profile names for previews
4. **Empty state** (2 commits): empty dm refresh as empty, empty reply threads as empty
5. **Realtime activity** (3 commits): detect hidden unread, surface new update jump, clear unread cache on open
6. **Misc** (1 commit): round unread chips

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Activity/Inbox 列表基础 (all / unread / mentions filter)
- Mark all read (per `/channels/inbox/read-all`)
- Open channel/dm/thread from row
- Round unread chips (per `fix(activity): round unread chips`)
- Refresh badge after read actions
- Activity store share across shells (06-25 main `6a890a94`)
- Subscribe standalone realtime
- Sender names resolve from members
- Profile names for previews
- Empty dm refresh as empty state
- Clear unread cache on open
- Refresh secondary home panels
- Source activity badge from inbox

### ⚠️ 接近对齐 (P1)
- Round unread chips (done)
- Route standalone refresh through shell store (done)
- Resolve sender names from members (done)
- Detect hidden unread updates (done)
- Surface new update jump (done)

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Store 收敛 Phase 2 / 3** (#387/#388 Android-App-Integrator in_progress)
   - 当前 Activity 还是 standalone route + shell store 共享 (Phase 1 done)
   - Phase 2 / 3 待: 全局 store canonical thread cache/store identity + Thread routes select content from canonical thread store
2. **Per-item Done 动作** (06-23 doc P0, 仍未闭环)
   - React Row 右侧 Check 触发 markDone (not open)
   - KMP 当前 KMP 共享 `markThreadDone` 但 Activity UI 未接
3. **Per-item Mark as Read / Unread 入口** (06-23 doc P0, 仍未闭环)
   - React 右键菜单
   - KMP 未接长按/菜单
4. **Load more pagination in Activity UI** (06-23 doc P0)
   - React 滚到底 → loadMore offset-based
   - KMP `InboxRepository.loadMore` 存在, 但 Activity UI 未接
5. **Activity/Inbox 旧快照覆盖新状态竞态** (@AD-2 in_progress per #420 done by AD-4, KMP 有相同 bug)
   - Mark all read 后, 旧 response 晚到覆盖本地已读 → unread 清掉又闪回来
   - KMP 也有一模一样的 bug (per @artin 02:00)
   - 修复方向: 缓存命中 + background refresh 不展示刷新 affordance
6. **Activity unread badge staleness** (@AD-2 in_progress #250 done 06-23)
   - Mark all read 后 badge 不更新 (per @artin 05:18)
   - 修复: 显式 onResume refresh

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- Activity store 走 shared domain owner (per #370 Cindy done by Android-App-Integrator Phase 1)
- Activity 数据驱动 vs standalone state (#386/#387/#388 工程改造)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
1. Store 收敛 Phase 2 / 3 (#387/#388 Android-App-Integrator)
2. Per-item Done 动作
3. Per-item Mark as Read / Unread 入口 (长按菜单)
4. Load more pagination in Activity UI
5. Activity/Inbox 旧快照覆盖新状态竞态 (@AD-2 in_progress)
6. Activity unread badge staleness (per @AD-2 done #250 部分, 仍有 race)

### P1 (体验)
- React 跟 KMP Activity 列表加载 indicator 一致 (per "现在在 activity 滚动到下面一部分了, 然后 activity 有更新了" @AD-2 #319 in_progress)
- ↑ x updates indicator (per #319 in_progress)

### P2 (业务扩展)
- Activity/Notifications unified timeline (per @Cindy native realtime app-scope owner #370)
- App diagnostic event forwarding (per #111 done)

## Owner 拍 (下一步)

- (i) 等 #387/#388 store 收敛完成, 我跑 verify diff
- (ii) 等 per-item Done / Mark Read 入口实现 + UI 接 (owner 不明, 可能 AD-2 / AD-3)
- (iii) 等 Activity/Inbox 旧快照竞态修复 (@AD-2 in_progress)
- (iv) owner 显式派

我倾向 (i)+(iii) 并行: Android-App-Integrator 修 store, AD-2 修竞态, 我等两边完一起跑 verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26