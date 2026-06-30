# React vs KMP Alignment: Tasks Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Tasks

包含: server tasks / channel tasks / create / claim / status / assign / delete / task board view / task card / status badge

## React source of truth

`packages/web/src/components/task/`:
- TaskBoard.tsx (board view, multiple status columns)
- TasksPanel.tsx (legacy tasks panel)
- TaskItem.tsx (single task row)
- TaskCard.tsx (card view)
- StatusBadge.tsx (status indicator)
- CreateTaskDialog.tsx (create new task)
- taskStatusUi.ts (status mapping)

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/SharedTasksPage.kt` (~3000 lines)
+ `MembersAgentRuntimeChip.kt` (status chip in agent detail)
+ Task status patch logic in `SharedThreadStore` / `MembersAgentPermissionsTab`

## Feature points inventory (06-23 → 06-26)

(15 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 6)

主要类别:
1. **Task identity binding** (3 commits): bind task state by message identity, use linked task projection, project linked tasks from shared store
2. **Task status hydration** (3 commits): hydrate reply task status from parent channel, preserve linked task state on refresh, apply task status patch response
3. **Joint / Hidden** (3 commits): keep joint channel task actions hidden, clamp joint reply launch task actions, hide task toggle in reply composer
4. **Cache repair** (3 commits): repair stale task cache from server deltas, restore channel tasks from sql cache, hide unresolved task assignee chips
5. **Misc** (3 commits): promote repaired task id on status update, match linked tasks by task identity, isolate tasks join state

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Task list 基础 (server / channel tasks)
- Task create (CreateTaskDialog parity)
- Status badge (StatusBadge alignment)
- Task state by message identity binding
- Linked task projection from shared store
- Reply task status hydrate from parent channel
- Linked task state preservation on refresh
- Task status patch response applied
- Unresolved task assignee chips hidden
- Hide task toggle in reply composer
- Joint channel task actions hidden
- Joint reply launch task actions clamped
- Task id repair on status update
- Tasks join state isolation (per #318 Codex-Kuikly-MP done)
- Restore channel tasks from sql cache
- Repair stale task cache from server deltas

### ⚠️ 接近对齐 (P1)
- Linked task state in chat projection (done)
- Keep warm task projection during reload (done)

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Task 状态 patch 后 UI 不更新** (per @AD-2 in_progress, per @artin 23:10)
   - 修复 task status 后, 状态没写入 cache 或 patch response 后没写入 cache
   - 根因 cache write 链路问题
2. **Task 标记失败 400** (per #293 AD-4 in_review)
   - task 状态 update 返回 400, UI 不更新
3. **Activity 进入 thread 状态错** (per #420 AD-4 done, KMP 类似 race)
   - thread 状态一定是错的, 只有 assignee, 没有状态
   - 修复方向: 主动 fetch 单个 task 状态
4. **Task assignee 状态回退** (per @AD-2 in_progress, per @artin 18:46)
   - task assign 状态一直红色 (loading), 即使上一次已加载
   - 缓存命中后仍把 task status=loading 带到 UI
5. **TaskBoard 多列视图 parity** (vs single-list TasksPanel in KMP)
6. **Task 状态 badge 对齐 React** (per `StatusBadge.tsx` vs KMP `MembersAgentRuntimeChip.kt`)

### ❌ 不在 React parity 范围 (KMP 业务扩展)
- LegacyTaskPanel in React 已 deprecated, KMP 走新版 SharedTasksPage
- TaskCard / TaskItem parity (React 已有, KMP 单 list 视图, 走 simple UI)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
1. Task 状态 patch 后 UI 不更新 (cache write 修复)
2. Task 标记失败 400 (#293)
3. Activity 进入 thread 状态错 (per #420 done, KMP 类似 race)
4. Task assign 状态回退 (缓存命中 race)

### P1 (体验 / 视觉)
- TaskBoard 多列视图 parity (vs single-list)
- Task 状态 badge 对齐 React StatusBadge
- Linked task state chat projection (already done)

### P2 (业务扩展)
- Task 状态 panel parity
- TaskCard / TaskItem visual parity

## Owner 拍 (下一步)

- (i) 等 @AD-2 cache write 修复 + task 状态回退 race 修复 done
- (ii) 等 #293 task 标记 400 修复 (@AD-4)
- (iii) 等 TaskBoard 多列视图 parity 派 owner (待 owner 拍, 06-23 doc P0)
- (iv) owner 显式派

我倾向 (i)+(ii): @AD-2 + @AD-4 修完, 我跑 v6.x verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26