# React vs KMP Alignment: VisualTesting Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: VisualTesting / Diagnostics / Owner onboarding / Release notes / Announcements / Reminders

包含: visual case registry / capture harness / diff / report / site / gh-pages publish / diagnostics page / Quiver diagnostics integration / announcements / reminders

## React source of truth

`packages/web/src/visual-testing/VisualTestingCases.tsx`: case registry (66+ cases mapped to fixtures)
`packages/web/tests/visual-testing/react-capture.spec.ts`: Playwright capture harness (62+ tests)
`packages/web/tests/e2e/fixtures/`: seed state fixtures
`packages/web/src/components/SettingsDiagnosticsPage.tsx` (Diagnostics, if exists)
`packages/web/src/components/AnnouncementModal.tsx`
`packages/web/src/components/owner/OwnerOnboardingModal.tsx` (per 06-23 doc, may exist)
`packages/web/src/components/ReleaseNotesPage.tsx`

## KMP source

- `compose/androidApp/src/androidTest/java/build/raft/app/visual/KmpVisualScreenshotCaptureTest.kt` (connected capture methods via `am instrument`)
- `compose/shared/src/visualFixtures/kotlin/build/raft/app/VisualFixturePages.kt` (KMP visual `@Page` registrations, compiled only with `-PslockVisualFixtures=true` / `SLOCK_VISUAL_FIXTURES=true`)
- `compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt` (Diagnostics 部分)
- `third_party/slock-source/packages/visual-testing/shared/sharedCases.json` (canonical case manifest)
- 06-24 publish run `2057eb39-20260624065206`: 52 / 50 different / 2 basic-pass

## Feature points inventory (06-23 → 06-26)

(24 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 11)

主要类别:
1. **Visual testing capture** (15 commits): capture auth screens, capture server danger modal, prefix component fixture cases, split component and screen coverage, activate members agent detail tabs, activate members activity route case, activate composer suggestion cases, align thread message row fixture, use thumbs-up reaction fixture, use real search filter popover, unify register input fixture, align settings danger modal, align agent lifecycle action case
2. **Quiver diagnostics integration** (5 commits): `feat(ohos)` export App diagnostics feedback archive, `feat(android)` attach diagnostics to app diagnostics, `feat(android)` export feedback from app diagnostics logs, `feat(native)` expand android app diagnostics, `chore(diagnostics)` trace composer draft projection
3. **Native Android diagnostics** (4 commits): `androidUseTdlogAsDiagnosticsSink` (App diagnostics log), `fix(diagnostics): remove network probe`, `fix(settings): remove diagnostics log preview`, `fix(settings): prune diagnostics profiler detail sections`

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Visual testing capture pipeline (Android connectedTest + React Playwright)
- Visual fixture page build gating: production debug/release builds omit `compose_*_visual` registrations; Android visual captures must pass `-PslockVisualFixtures=true`
- 71 cases in sharedCases.json (per 06-23 baseline, 06-24 publish)
- 52/52 React provider baseline 06-24 (`2057eb39-20260624065206`)
- 56/61 Android captures 06-24 PUX 跑
- Quiver crash + app diagnostics (per #111 done, Android + OHOS + diagnostics zip)
- Diagnostics log preview 移除 (per `fix(settings): remove diagnostics log preview` done)
- Diagnostics profiler detail sections pruned

### ⚠️ 接近对齐 (P1)
- sharedCases.json case 结构 (P1, partial coverage)
- screens.members.human.profile register pending (06-25 done by PUX)

### ❌ 仍未对齐 (06-26 仍 P0/P1)
1. **screens.members.human.profile visual case** (06-25 register pending, PUX spec lane + AD-3 fixture + Android capture)
   - sharedCases.json case added (main `5b295a0c`)
   - React provider spec handler added (slock-source `dd023dcba`)
   - fixture file 缺 (AD-3 lane)
   - Android capture test 缺 (AD-3 lane)
2. **Files list visual testing** (per @artin 02:08)
   - Files 列表 (文件消息列表) 没 visual testing
   - owner 拍谁来加 case
3. **Settings visual parity backlog** (#218/#231-#237 done/in_progress)
   - 06-23 拍过 backlog, 06-25 部分 done
4. **Owner onboarding mobile flow** (06-23 doc P1, 仍未实现)
5. **Release Notes 独立入口** (06-23 doc P2, 仍未实现)
6. **Announcements modal 显示 + dismiss 持久化** (06-23 doc P2, 仍未实现)
7. **Reminders 入口** (06-23 doc P2, 仍未实现)
8. **Native diagnostics file logging** (per #215 spec by Codex-Kuikly-MP, todo)

### ❌ 不在 React parity 范围 (KMP 业务扩展 / 工程改造)
- File preview text/markdown/code (#421-#425 Pi-Worker1 todo)
- Reaction sprite atlas (#397 AD-1 todo)

## P0/P1/P2 缺口分类

### P0 (本轮必修)
- screens.members.human.profile fixture + Android capture (PUX spec lane + AD-3 lane)
- Files list visual testing (per @artin 02:08)
- Settings visual parity backlog done 部分

### P1 (体验 / 视觉)
- Files visual testing
- Owner onboarding mobile flow (06-23 doc P1)

### P2 (业务扩展)
- Release Notes 独立入口 (06-23 doc P2)
- Announcements modal 显示 + dismiss 持久化 (06-23 doc P2)
- Reminders 入口 (06-23 doc P2)
- Native diagnostics file logging (#215)
- File preview text/markdown/code (#421-#425)

## Owner 拍 (下一步)

- (i) 等 screens.members.human.profile fixture (AD-3 lane) + 我跑 React capture + AD-3 跑 Android capture
- (ii) 等 Files list visual testing case (owner 拍谁加) + capture
- (iii) 等 #231-#237 settings parity backlog done 部分 verify
- (iv) owner 显式派 Owner onboarding / Release Notes / Announcements / Reminders

我倾向 (i) 等 AD-3 落地 fixture, 我跑 React capture 一遍过.

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26
