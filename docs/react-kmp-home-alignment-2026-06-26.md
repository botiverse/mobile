# React vs KMP Alignment: Home Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Home

包含: home shell / channels / DM / joint / server switch / search / activity / saved / notifications / pull-refresh / scroll-to-top

## React source of truth

`packages/web/src/components/layout/Sidebar.tsx` (~3000 lines): Home shell
`packages/web/src/components/layout/MainLayout.tsx` (~2400 lines): routes, navigation
`packages/web/src/components/search/MessageSearchPage.tsx` (~2000 lines)
`packages/web/src/components/saved/SavedPanel.tsx`
`packages/web/src/components/thread/ThreadsInbox.tsx` (Activity)

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/SlockHomeComponents.kt`
`compose/shared/src/commonMain/kotlin/build/raft/app/SlockAppShell.kt`
`compose/shared/src/commonMain/kotlin/build/raft/app/SharedInboxPanel.kt`
`compose/shared/src/commonMain/kotlin/build/raft/app/SharedHomeStore.kt`

## Feature points inventory (06-23 → 06-26)

(47 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 2)

主要类别:
1. **Badge 链路** (8 commits): trust loaded unread, refresh after read, badge summary, refresh secondary home, source from inbox, keep activity total out of unread badge, show server unread counts in switcher, show activity and saved badges
2. **Pull-refresh 生命周期** (8 commits): unify indicator, enable primary, show header affordance, remove affordance (per #250 done), simplify header spinner, lighten spinner, restore sidebar bounce, stop idle animation
3. **Server switch / Refresh** (4 commits): refresh badges on switch, clear badges before refresh, refresh on app foreground, route secondary panes through pager
4. **Realtime routing** (3 commits): realtime announcements → notifications, refresh secondary home panels, source dm agent status from members
5. **Scroll + Layout** (5 commits): scroll secondary panels to top from titles, handle system scroll to top, normalize sidebar section spacing, keep selected dm row content size, lift restore landing brand block
6. **Saved** (3 commits): clamp saved count at hundred, expand saved count preload, seed standalone saved from preload, simplify saved loading state
7. **Activity (from home)** (3 commits): seed standalone activity from preload, source activity badge from inbox, refine activity count and switcher gestures
8. **Search + Notifications** (4 commits): show searching state for live search, wire notification center through shell, surface offline machine notifications, suppress foreground unread notifications
9. **Server chooser** (3 commits): show back button on shell server chooser, simplify header refresh spinner, unify pull refresh indicator
10. **Misc** (4 commits): parallelize independent bootstrap requests, keep restore landing centered, guard rapid conversation row opens, lighten header refresh spinner

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Home shell + channels + DM + joint + server switch (基础)
- Search entry / Activity entry / Saved entry (3 大金刚)
- Pull-refresh 移除 + header spinner 替代 (per #250 done @Cindy)
- Header refresh spinner (per @Cindy @AD-2 #255 in_progress)
- Notification center (per `wireNotificationCenterThroughShell`)
- Server chooser scrollable + back button
- Sidebar pull bounce / scroll-to-top
- Badge 总数 (activity total out of unread badge)
- Saved count cap 100 + 99+ display (per #304 done)
- Server unread counts in switcher
- Bootstrap requests parallel
- DM row 选中态不缩放 (per @AD-2 done 06-25)

### ⚠️ 接近对齐 (P1)
- Render base menu foreground black
- Align top action row states
- Align row menu icons
- Anchor section sort menu
- Keep silent refresh out of pull indicator (per pull-affordance 移除)

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Foreground notification suppression race** (@AD-2 in_progress #246 done 06-23 但后续 bug)
   - mark all read 后 badge 不更新 (per @AD-2 done #250 部分, 仍有 race per @artin 05:18)
2. **Home 滚动卡背景刷新动画每帧重组** (@AD-1 done 06-26)
   - Home RCProfiler 1327 次重组, 1327 次 → 修后稳定
   - 根因: 缓存命中后仍把 contentRefreshing=true 带到 UI
3. **Server chooser scrollable** (done)
4. **Saved 右键菜单** (per @artin 23:14 right icon + context menu 缺)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- Home scroll to top 时 ↑ x updates indicator (per @AD-2 #319 in_progress)
- 切 server 时首页 unread 不刷新 (per @AD-2 done #315)
- Activity 进入 thread 后状态错 (#93 AD-4 in_review)
- DM 选中态不缩放 (per @AD-2 done 06-25)
- Saved 下拉刷新对齐 Activity (#284 todo)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
- Foreground notification suppression race (per #246 in_progress, 后加 #250)
- Home 滚动卡刷新动画重组 (@AD-1 done 06-26)
- Saved 右键菜单对齐 React
- Activity unread badge staleness (部分 done by @AD-2)

### P1 (体验 / 视觉)
- ↑ x updates indicator (per #319 in_progress)
- Server chooser UX (done)
- Saved 下拉刷新对齐 (#284 todo)
- Saved count cap 100 (#304 done)

### P2 (业务扩展)
- Activity scroll to top indicator
- Activity unread badge staleness race

## Owner 拍 (下一步)

- (i) 等 @AD-1 Home 滚动卡重组修复 done + #319 scroll-to-top indicator done, 我跑 verify
- (ii) 等 @AD-2 mark all read race 修复 done, 我跑 v6.x verify
- (iii) 等 Saved 右键菜单对齐 React done (owner 待拍), 我跑 visual parity
- (iv) owner 显式派

我倾向 (i)+(ii) 并行: AD-1 + AD-2 修完, 我跑 verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26