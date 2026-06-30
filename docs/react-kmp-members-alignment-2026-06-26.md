# React vs KMP Alignment: Members Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Members

包含: members list / agent detail / human detail / create agent / agent status indicator / permissions / activity / dms / reminders / workspace / apps

## React source of truth

`packages/web/src/components/agent/AgentDetailPanel.tsx` (~1200 lines, 7 tabs)
`packages/web/src/components/member/HumanDetailPanel.tsx` (~600 lines, single tab for human)
`packages/web/src/components/agent/CreateAgentDialog.tsx` / `PixelAvatar.tsx`
`packages/web/src/components/member/` / `ConfirmDialog` / `StatusBadge` / `StatusDot`

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/MembersPage.kt` (~6000 lines)
+ `SharedAgentDetailStore.kt` (per #56 phase 2A AD-1)
+ `SharedCreateAgentDialog.kt` / `SharedInviteHumanDialog.kt`
+ `AgentScopeSet` data class (per AD-3 #89 spec)

## Feature points inventory (06-23 → 06-26)

(43 features per main commit log, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 4)

主要类别:
1. **Profile 对齐** (8 commits): runtime config edit, profile sections, description, created date
2. **Permissions tab** (5 commits, per AD-3 #89 done): scope set + load/save/reset
3. **Agent status indicator** (5 commits, per AD-3/AD-4 #86/#90 done): size + pulse + avatar overflow
4. **Activity 实时** (4 commits): trajectory streaming, freshness guard, scroll pinned
5. **Avatar pipeline** (4 commits): inline avatar management, prefer pixel, preserve created avatar
6. **Runtime config** (3 commits): parse model catalogs, serialize kind tags, guard reasoning by runtime
7. **Routing** (3 commits): route list → detail, human detail by member kind, no-op missing
8. **Header actions** (4 commits): preserve header actions, three-dot menu match, dropdown position
9. **Invite + Profile 304** (3 commits): 304 fallback 移除, copy invite join URL
10. **Subtitle / Sub-actions 清理** (4 commits): subtitle 移除, dedup affordances

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Members list (people + agents 列表)
- Agent detail tabs: profile / permissions / dms / reminders / workspace / apps / activity
- Status indicator: 11dp size + pulse + avatar overflow (per #86/#90 done)
- Agent profile sections (display name + description + created date per #91 done)
- Runtime config editing
- Avatar pipeline (pixel preferred, fallback deterministic)
- Permissions tab form (#89 AD-3 done)
- Header dropdown alignment (3-line icon + position per #71 done)
- Profile edit pencil actions
- Agent detail activity streaming
- Avatar management visual (per #57 inline)
- Agent reasoning config by runtime guard
- Detail route by member kind (human vs agent)
- No-op missing detail route

### ⚠️ 接近对齐 (P1)
- Profile 304 fallback (已经移除 ✓)
- Read-only link fields pannable
- Detail header height alignment
- Channel source badges alignment
- Action menu to trigger edge alignment
- Reminder visual fixture alignment (per #66 done)
- Invite human dialog alignment (per #80 done)
- Profile edit headings alignment (per #53 done)
- Activity empty state alignment

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Status resolution 一致性** (#73 AD-4 in_progress, per spec v2 #68 P0 #1)
   - 真 bug: KMP color 用 `member.agentStatus` (snapshot), pulse 用 `membersResolvedAgentStatus` (summary). 同一 dot 双 source. 修法: 抽 `resolveMemberDisplayState(member, summary)` 跟 React `resolveAgentDisplayState` 同构.
2. **Freshness / surface validation** (per `react-kmp-realtime-alignment-2026-06-26.md`)
   - `agentActivityObservedAt` / `agentActivityVersions` / `agentActivitySeq` 已落地，旧 spec v2 的“字段缺失”结论已 stale
   - 剩余风险是 Home / thread rows / member detail / profile 等 status surface 是否都消费 member/agent-detail presence path，避免 stale snapshot 覆盖新 socket push
3. **Activity list 实时刷新** (#93 AD-4 in_review)
   - agent detail activity 列表不实时
4. **External agent display-state** (P1 #3, per spec v2)
   - React `bg-brutal-cyan` 路径 KMP 缺
   - `MemberDisplayState.isExternal` + `ExternalAgentStatus` mock 初始
5. **screens.members.human.profile visual case** (06-25 register pending, PUX spec lane + AD-3 Android capture)
   - sharedCases.json case added (main `5b295a0c`)
   - React provider spec handler added (slock-source `dd023dcba`)
   - fixture file 缺
   - Android capture test 缺
6. **Create agent dialog runtime model 拉不到** (#92 AD-3 in_review)
7. **Profile 底部按钮** (#91 AD-3 in_review per @artin 12:57)
   - screenshot 显示还有多按钮没接

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 占位符)
- External agent display-state 是 KMP MVP 不需要, owner 拍 (#308 future OAuth 同样)
- Per-call-site size + token audit (per spec v2 P1 #4) — per-call-site 调整, 不全局
- Idle / Disconnected 业务扩展 vs 简化 (per spec v2 P2 #5, owner 拍)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
1. Status resolution 一致性 (#73 AD-4)
2. Freshness / status surface validation
3. Activity list 实时刷新 (#93 AD-4)
4. External agent display-state (per spec v2 P1 #3, owner 拍 是否 KMP MVP 需要)
5. screens.members.human.profile visual case 落地 (#89 spec 落地后 fixture + Android capture)
6. Create agent dialog runtime model (#92 AD-3)
7. Profile 底部按钮 (#91 AD-3)

### P1 (体验 / 视觉)
- Per-call-site size + token audit (spec v2 P1 #4)
- Idle / Disconnected 业务扩展 vs 简化 (spec v2 P2 #5 owner 拍)
- Agent detail header avatar overlay hidden (#417 done 但需 verify)
- Detail header height alignment

### P2 (业务扩展 / cleanup)
- External agent 路径 (KMP MVP 不需要, owner 拍)
- Square shape 删除 (cleanup, 死代码)

## Owner 拍 (下一步)

- (i) 等 AD-4 #73 落地, 并按 realtime alignment 做 status surface freshness verify，跑 v6.x verify diff 报 thread
- (ii) 等 AD-3 #91 + #92 落地, 我跑对应 visual parity verify
- (iii) 等 owner 拍 (External agent 是否 KMP MVP 需要, Idle/Disconnected 简化 vs 业务扩展)
- (iv) 我跑 screens.members.human.profile React capture (等 AD-3 fixture 落地)
- (v) owner 显式派

我倾向 (i)+(ii)+(iv) 并行: AD-4 / AD-3 / AD-3 fixture 并行, 我等三方完一起跑 verify.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26
