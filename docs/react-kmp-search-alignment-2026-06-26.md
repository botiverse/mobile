# React vs KMP Alignment: Search Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Search

包含: message search / entity search (channels, humans, agents) / filter (sender / channel / time) / sort / load more / empty / loading

## React source of truth

`packages/web/src/components/search/`:
- MessageSearchPage.tsx (~2000 lines): message search + filter chips + result cards + load more + entity search
- searchComposition.ts / searchEntities.ts / searchGrouping.ts / searchRetry.ts

## KMP source

- `compose/shared/src/commonMain/kotlin/build/raft/app/SharedHomeSearchSavedStore.kt` (含 search + saved + activity 三合一)
- `SharedSearchRepository` (search query logic)

## Feature points inventory (06-23 → 06-26)

(8 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 7)

主要类别:
1. **Multi-filter** (1 commit): `fix(search)` support multiple sender filters (per #285 in_progress, slock-source HEAD 46f889b6f)
2. **Popover fixture** (1 commit): `fix(visual-testing)` use real search filter popover
3. **Live search state** (1 commit): `homeShowSearchingStateForLiveSearch` (searching state)
4. **Conversation stream helper** (1 commit): `homeImportConversationStreamHelper`
5. **Pull refresh unified** (1 commit): `homeUnifyPullRefreshIndicator`
6. **Rows source** (1 commit): `homeProjectRowsFromConversationDirectory`
7. **Saved cap** (1 commit): `homeClampSavedCountAtHundred`
8. **Visual testing reformat** (per @AD-2 / @AD-4 in_progress 06-25)

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Search 基础 (message search + result list + load more)
- Filter chip (FROM / Channel / Any Time / Relevant)
- Sort options
- Empty state
- Searching state indicator (per `homeShowSearchingStateForLiveSearch`)
- Saved count cap 100 (#304 done)

### ⚠️ 接近对齐 (P1)
- Multi-sender filter (per `fix(search)` done in slock-source, KMP routing owner @KMP-专家 per #285 in_progress)
- Search filter popover (visual fixture aligned)
- Conversation stream helper imported

### ❌ 仍未对齐 (06-26 仍 P0/P1)
1. **Multi-select from filter** (per @KMP-专家 #285 in_progress, API 也要适配)
   - React search 支持多选 from
   - KMP 当前 single-select
   - owner 拍是否 CHANNEL 也要多选
2. **Search 系列 visual testing 重制** (per @AD-2 / @AD-4 in_progress 06-25)
   - 当前 visual capture 是 pre-#285 build, 没覆盖 multi-filter
   - 重制后确认视觉 parity
3. **Search input 接入 unified 组件** (per @AD-2 #272 in_progress)
   - 当前 search input 没接入统一 input 组件
4. **Search popover 右侧避让屏幕** (per @AD-2 #272 done 06-25)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- Multi-sender filter 需要 API 也适配 (per @KMP-专家 反馈)
- Saved / Search 走 unified row source (per `homeProjectRowsFromConversationDirectory`)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
1. Multi-select from filter (#285 @KMP-专家 in_progress)
2. Search 系列 visual testing 重制 (@AD-2 / @AD-4 in_progress)
3. Search input 接入 unified 组件 (#272 @AD-2 in_progress)

### P1 (体验 / 视觉)
- Search popover 右侧避让屏幕 (#272 done 06-25)
- Search filter popover 真实 fixture 对齐 (done)

### P2 (业务扩展)
- Multi-sender API 适配 (per @KMP-专家, server-side 拍)
- Saved / Search unified row source (done partially)

## Owner 拍 (下一步)

- (i) 等 @KMP-专家 #285 multi-select from filter 落地 + API 适配 done
- (ii) 等 @AD-2 / @AD-4 search 系列 visual testing 重制 done
- (iii) 等 Search input 接入 unified 组件 (#272 @AD-2) done
- (iv) owner 显式拍

我倾向 (i)+(iii) 并行: @KMP-专家 + @AD-2 修完, 我跑 v6.x verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26