# React vs KMP Alignment: Settings Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Settings

包含: account / sessions / language / billing / server / workspace / admin / appearance / notifications / connected apps / danger actions

## React source of truth

`packages/web/src/components/settings/SettingsPanel.tsx` (~2000 lines): tabbed structure
- Account / Sessions / Language / Billing / Server / Workspace / Admin / Appearance / Notifications / Connected Apps / Danger

## KMP source

- `compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt` (~10000 lines, 巨型文件)
- `SettingsAccountPage.kt` / `SettingsBillingPage.kt` / `SettingsComputersPage.kt` / `SettingsStandalonePageData.kt` / `SettingsStandaloneStore.kt`
- @Android-Settings-Integrator lane

## Feature points inventory (06-23 → 06-26)

(32 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 5)

主要类别:
1. **Route 化拆分** (5 commits in_progress #345/#346/#347)
2. **Server / Workspace** (5 commits): AddMachineDialog flow, route computer detail through store, brutalize computers list, server profile brutal, shared computers
3. **Account / Profile** (8 commits): share read-only field, prefix handle, polish profile, remove avatar upload copy, subtitle, merge auth diagnostics, add reset password legal
4. **API Environment** (5 commits): restore presets, centralize store, move to form page, clarify switcher, honor configured web URLs
5. **Tab 改造** (3 commits): align tab icons 1:1, reorder tabs, add Administration tab
6. **Diagnostics / 清理** (3 commits): remove diagnostics preview, prune profiler detail, merge auth diagnostics
7. **Misc** (3 commits): import json parser, attach diagnostics draft, align billing and danger visuals)

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Account / Profile 基础
- Sessions 列表
- Language / Region (per #78 done, translation display)
- Billing variants parity (per #87 done, 06-24 publish baseline)
- Server Profile declutter (per `feat(settings)` declutter, only Profile section)
- Workspace / Computers 基础 (AddMachineDialog flow)
- Admin tab (per `feat(settings)` add System Channels section)
- Appearance (font size auto-save, per #232 done)
- Notifications toggle default aligned
- Connected Apps parity (per #189 done)
- Danger zone visual (per #232 done)

### ⚠️ 接近对齐 (P1)
- Settings 主 section 路由化拆分 (per #346 in_progress @Android-Developer-4)
- Settings Computers 独立 route (per #345 in_progress @Android-Developer-4)
- Settings route provider/store 统一 (per #347 in_progress @Android-Developer-4)
- Server profile brutal visual aligned
- Account avatar providers aligned
- Account card with React aligned

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Settings 路由化拆分** (#345/#346/#347 @Android-Developer-4 in_progress)
   - Settings Computers detail 改走独立 route 并删除 app-shell 内嵌 detail 链路
   - Settings 主 section 路由化拆分: 梳理哪些 destination 应从 app shell 内嵌页迁出
   - Settings route provider/store 统一: 让 standalone Settings 子页消费共享 store 而不是 payload-only 展示
2. **Owner onboarding** (per `Owner onboarding mobile flow` 06-23 doc P1, 仍未实现)
3. **Billing 完整 parity** (variants done, copy + 错误态待)
4. **Notifications 实机权限** (Android system permission + 系统设置跳转 + 测试通知反馈, 06-23 doc P1)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- API environment switching (per `feat(settings)` add, dev mode UI)
- Account recovery entry points (per #308 future)
- Quiver crash + app diagnostics (per #111 done)
- Diagnostics log preview 移除 (per `fix(settings): remove diagnostics log preview` done)

## P0/P1/P2 缺口分类

### P0 (本轮必修, in_progress)
- Settings 路由化拆分 #345/#346/#347 (@Android-Developer-4)

### P1 (体验 / 视觉)
- Billing copy + 错误态 (06-23 doc P1)
- Notifications 实机权限 + 系统设置跳转 + 测试通知反馈 (06-23 doc P1)

### P2 (业务扩展 / future)
- Owner onboarding mobile flow (06-23 doc P1, 仍 缺)
- Release Notes 独立入口 (06-23 doc P2)
- Announcements modal 显示 + dismiss 持久化 (06-23 doc P2)
- Reminders 入口 (06-23 doc P2)
- Account avatar upload copy 移除 (per `fix(settings): remove account avatar upload copy` done)
- Settings.remove subtitles from members and settings headers (done)

## Owner 拍 (下一步)

- (i) 等 #345/#346/#347 完成 (@Android-Developer-4 owner), 我跑 verify
- (ii) 等 Billing copy + Notifications 实机权限完成 (owner 不明, 待拍), 我跑 verify
- (iii) 等 Owner onboarding mobile flow (06-23 doc P1, 仍未派 owner), 我写 spec
- (iv) owner 显式派

我倾向 (i): 等路由化拆分完成. @Android-Developer-4 是 worktree owner.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26