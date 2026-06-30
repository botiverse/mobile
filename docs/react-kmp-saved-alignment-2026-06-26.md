# React vs KMP Alignment: Saved Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Saved

包含: saved messages list / load more / unsave / open original / copy link / copy markdown / right icon / context menu

## React source of truth

`packages/web/src/components/saved/SavedPanel.tsx`:
- 列表 + load more
- Row click: open original message
- 右键菜单: Copy Link / Copy Markdown / Remove from Saved

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/SharedHomeSearchSavedStore.kt` (saved 部分)
`SharedSavedRepository`

## Feature points inventory (06-23 → 06-26)

(7 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 8)

主要类别:
1. **Count cap** (1 commit): `homeClampSavedCountAtHundred` (per #304 done 06-25, display "99+")
2. **Preload** (1 commit): `homeExpandSavedCountPreload` (saved 预加载)
3. **Loading state** (2 commits): `homeSimplifySavedLoadingState`, `homeSeedStandaloneSavedFromPreload`
4. **Badge refresh** (1 commit): `homeRefreshActivityBadgeAfterReadActions`
5. **File preview** (1 commit): `savedShowGenericDownloadControls`
6. **Right icon + context menu** (per @artin 23:14 缺)

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Saved 列表 基础
- Load more
- Unsave
- Open original message
- Saved count cap 100 + 99+ display (per #304 done 06-25)
- Saved preload (per `homeExpandSavedCountPreload`)
- Loading state (per `homeSimplifySavedLoadingState`)
- Standalone saved from preload (per `homeSeedStandaloneSavedFromPreload`)

### ⚠️ 接近对齐 (P1)
- File preview generic download controls (per `savedShowGenericDownloadControls`)

### ❌ 仍未对齐 (06-26 仍 P0/P1)
1. **Right icon 对齐 React** (per @artin 23:14)
   - React saved row 右侧有 right icon (per screenshot)
   - KMP 缺
2. **Context menu (右键菜单)** (per @artin 23:14)
   - React: Copy Link / Copy Markdown / Remove from Saved
   - KMP 缺
3. **下拉刷新对齐 Activity** (per @AD-2 #284 todo 06-25)
   - React saved 下拉刷新 pattern 跟 Activity 一致
   - KMP 不参与下拉, header 不参与下拉, 右侧还能展示 loading

### ❌ 不在 React parity 范围 (KMP 业务扩展)
- Saved 走 unified row source (per `homeProjectRowsFromConversationDirectory`)
- Saved count refresh 后 keep (per @Home done)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
1. Right icon 对齐 React (per @artin 23:14)
2. Context menu (Copy Link / Copy Markdown / Remove from Saved) (per @artin 23:14)

### P1 (体验 / 视觉)
- 下拉刷新对齐 Activity (per #284 todo)

### P2 (业务扩展)
- Saved unified row source (done)

## Owner 拍 (下一步)

- (i) 等 owner 拍 Right icon + context menu 谁接 (owner 待拍, AD lane)
- (ii) 等 #284 下拉刷新对齐 Activity (per @AD-2 todo) done
- (iii) owner 显式派

我倾向 (i)+(ii): AD 修完, 我跑 visual parity verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26