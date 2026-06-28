# Responsive Layout Spec (Foldable / Landscape / Wide) — 2026-06-29

Per @Pi-PMO 2026-06-29 01:40 #大屏适配:77c4b6f6 "出 spec: 折叠屏/宽屏/横屏响应式布局设计. 覆盖 auth (登录页), 首页, 对话流, 设置等核心页面. 考虑折叠屏展开态, 横屏左右分栏策略, 内容最大宽度约束等".
Owner: @Product-UX-Designer (spec lane)
Task: #大屏适配 #12
Worktree: `Slock-worktrees/pux-responsive-spec-2026-06-29`
主仓 main HEAD: `e0f767e1 Merge branch 'ad3/task261-ohos-ios-svg-assets'`

## Slice Scope

覆盖页面的折叠屏展开态 + 横屏 + 宽屏响应式:
- Auth (登录页, 服务选择, 选 server, register, forgot)
- Home (channels / DM / joint / saved / search / activity, server switcher)
- Thread (conversation view, composer, side panel col-3)
- Settings (account, sessions, language, billing, server, workspace, admin, appearance, notifications, connected apps)
- Members (list, human detail, agent detail)

## React source of truth

`packages/web/src/components/layout/MainLayout.tsx` (~2400 lines, 1 个文件):
- `isDesktop` / `isLg` state from `window.matchMedia("(min-width: 768px)")` / `"(min-width: 1024px)"`
- Mobile bottom tab bar: `className="md:hidden"` (768px 以下才显示)
- 主内容 + 左 rail + 右 col-3 panel 切分: `[@media(max-width:1023px)]` 折叠 col-3
- 多处: `(min-width: 768px)` / `(min-width: 1024px)` media queries
- 全部用 `matchMedia` 实时响应

`packages/web/src/hooks/useMediaQuery.ts` 之类的 hook (推断)
- `[@media(max-height:600px)]` 减高度场景
- Tailwind responsive: `md:` / `lg:` (768 / 1024 breakpoint)

## KMP source

**当前: KMP 无 responsive 代码**. `grep -rn "WindowSizeClass|@media|breakpoint|LocalConfiguration" compose/shared/...` 无结果.

- KMP 当前按 phone portrait (390x844) 固定布局设计
- 横屏 / 折叠屏 / tablet 都是 phone portrait 强行 stretch
- 没考虑 keyboard 弹起 (per #392/#393/#405 等历史 issue)
- 没考虑 foldable unfolded 状态 (Android Flex 1 + 1 split / Surface Duo / Galaxy Fold)

## Feature points inventory (per main 06-28)

主要类别:
1. **React breakpoint 系统** (3+ commits): 768px / 1024px media queries
2. **Mobile bottom tab bar** (`md:hidden` + visible on root-level views)
3. **Sidebar / Left rail** (different layouts per breakpoint)
4. **Col-3 panel** (Thread human/dm/agent 详情, 折叠屏 split)
5. **Auth 页面** (login / register / forgot 不同布局)
6. **KMM WindowSizeClass 缺失** (KMP 0 覆盖)
7. **Foldable 适配缺失** (Android Flex / Surface Duo / Galaxy Fold 都没考虑)

## 现状 vs React parity

### ✅ 已对齐
- KMP phone portrait (390x844) 基础布局 (auth / home / thread / settings / members)

### ⚠️ 接近对齐 (P1, 部分通过手写 Layout 完成)
- 横屏 phone landscape (844x390) — KMP 没专门适配, 但 layout 是 flexible 的 (auto-fit), 不会崩
- 折叠屏 cover display 关闭态 — phone mode 复用, 跟普通 phone portrait 一样

### ❌ 仍未对齐 (06-28 仍 P0)
1. **Phone 横屏 (844x390)** — KMP layout 在 phone 横屏下可能溢出 / 错位. 没专门适配.
2. **Tablet portrait (768x1024)** — React 切到 desktop 模式 (isDesktop=true), KMP 没切.
3. **Tablet landscape (1024x768)** — React 切到 lg 模式 (isLg=true), col-3 panel 折叠逻辑激活. KMP 没切.
4. **折叠屏展开 (e.g. 2208x1840)** — React layout 自动扩展 (因为是 web responsive). KMP 0 覆盖.
5. **横屏 (landscape) 物理键盘弹起 / input 占位** — KMP 偶尔有 issue.
6. **多窗口模式 (multi-window)** — KMP 0 覆盖, React 仍是 web 全屏.
7. **可折叠设备 (Surface Duo / Galaxy Fold)** — 折叠展开变化, KMP 没接 WindowSizeClass.

## P0/P1/P2 缺口分类

### P0 (本轮必修, foldable 设备基础)
1. **横屏适配** (phone landscape 844x390): login / home / thread / settings 调整
2. **Tablet 切 desktop 模式** (768px+): layout 切两栏 (sidebar + main) / 三栏 (sidebar + main + col3)
3. **Foldable unfolded 适配** (2208x1840 等): layout 自动扩到三栏

### P1 (体验, 横屏体验优化)
1. **Col-3 panel 折叠逻辑**: React 1024px+ 显示 col-3, KMP 缺
2. **Mobile bottom tab bar**: React md:hidden, KMP 缺 (目前只在 portrait 显示)
3. **Auth 页面横屏布局**: React 居中, KMP 0 适配
4. **Multi-window 模式**: 分屏场景
5. **Physical keyboard 占位**

### P2 (业务扩展, future)
1. **Android Flex (折叠) 状态识别** (Flex Mode 1 / 2 / Dual)
2. **iPad (iOS 13+) Stage Manager** (KMP 走 shared, 涉及多端)
3. **Desktop 模式 (ChromeOS)** (Mouse 切换, 窗口 resize)
4. **悬停 / Mouse support** (desktop hover state, KMP 主要 touch)

## 推荐方案 (per PUX 视角)

### Phase 1: Phone 横屏 + Tablet portrait (1-2 周)
1. KMP 引入 `LocalConfiguration.current.screenWidthDp` / `screenHeightDp` (AndroidX WindowSizeClass 已有)
2. 共用 `SlockWindowSize` 数据类:
   ```kotlin
   sealed class SlockWindowSize {
       object Compact : SlockWindowSize()  // <600dp
       object Medium : SlockWindowSize()   // 600-839dp
       object Expanded : SlockWindowSize() // ≥840dp
   }
   ```
3. 各 page 入口处: `val size = rememberScreenSize(); when(size) {...}`
4. 横屏 (landscape phone): 左右分栏 (sidebar + main), portrait 单栏
5. Tablet portrait (medium): 左右分栏 (sidebar + main)
6. Tablet landscape (expanded): 三栏 (sidebar + main + col3)

### Phase 2: Foldable 展开 (1 周)
1. AndroidX `WindowMetricsCalculator` 监听 fold 事件
2. WindowSize 重新分类: `FoldedCompact` (phone) / `FoldedExpanded` (2208x1840) / `Tabletop` (Galaxy Z Fold 半开)
3. FoldedExpanded: 三栏 max-width (1600dp) 居中, content 大宽屏 friendly

### Phase 3: 体验优化 (2 周)
1. Bottom tab bar 在 <840dp 显示, ≥840dp 隐藏
2. Auth 横屏: 居中 + max-width 480dp
3. 横屏 keyboard: Composer 跟 input 一起上移, 不被 keyboard 挡
4. Multi-window: 监听 configuration change, 重 layout

## 推荐 breakpoint 系统 (跟 React 对齐)

| React          | KMP (推荐)             | DP Range          |
|----------------|------------------------|-------------------|
| `< 768px` (mobile)  | `Compact`         | < 600dp           |
| `768-1023px` (md)   | `Medium`          | 600-839dp         |
| `≥ 1024px` (lg)     | `Expanded`        | ≥ 840dp           |
| Foldable 展开 | `FoldedExpanded`    | ≥ 840dp (但有 fold state) |
| 横屏 phone (landscape) | `CompactLandscape` | width > height, < 600dp width |

## Owner 拍 (下一步)

- (i) AD/KMP 落地 Phase 1 (横屏 + Tablet portrait) — 估计 1-2 周
- (ii) 跟 Activity SSOT 收敛 (#386/#387/#388) 一起做, 因为 responsive 也是 store 触发的
- (iii) owner 显式派 (e.g. 跟 #392 一起做, 或 #407 foldable specific)

我倾向 (i): 先做 Phase 1 横屏 + Tablet portrait, 1-2 周, 跟 spec v2 #68 + Activity SSOT 收敛并行. 不要做 foldable 太早 (Phase 2), foldable 用户群小, Phase 1 优先覆盖 99% case.

## 验收标准

### Phase 1 验收 (1-2 周)
1. Phone landscape (844x390): login / home / thread / settings 自动 layout 切左右分栏, 不溢出
2. Tablet portrait (768x1024): 切两栏 (sidebar + main)
3. Tablet landscape (1024x768): 切两栏 + 可选 col-3
4. 配置变更 (orientation, multi-window) 实时响应, 不崩
5. Visual testing: 至少 3 个 case 覆盖 phone portrait / phone landscape / tablet portrait

### Phase 2 验收 (1 周)
1. Foldable 展开: layout 自动扩到三栏 max-width 1600dp
2. Galaxy Z Fold 半开 (tabletop) 模式: 上下分栏 (Composer 跟消息列表)
3. 配置变更 (fold/unfold) 实时响应

### Phase 3 验收 (2 周)
1. Bottom tab bar: <840dp 显示, ≥840dp 隐藏
2. Auth 横屏: 居中, max-width 480dp
3. 横屏 keyboard: Composer 跟 input 一起上移
4. Multi-window: 监听 + 重 layout

## 风险

1. **跟 Activity SSOT 收敛 (#386/#387/#388) 撞车**: 收 store 跟 responsive 都触发 store 触发的 layout 变化, 可能需要协调
2. **跟 Kuikly Compose API 兼容**: `LocalConfiguration.current` 在 Kuikly Compose 是否完整? 需要 @Kuikly-MP 验证
3. **Foldable 设备 cover display**: 物理硬件 + AndroidX `WindowMetricsCalculator` API 适配, 实现成本比 responsive 高
4. **KMP 0 覆盖**: 整个 responsive 系统是新功能, 估计 200-500 行代码 (SlockWindowSize utility + 各 page layout 调整)
5. **跟 spec v2 #68 status resolution 撞车**: status resolution 改时, UI 刷新可能跟 responsive trigger 同时, 要协调
6. **跟 ConversationSummary 瘦化 (#467) 撞车**: responsive 会消费 SlockWindowSize, 跟 summary 字段解耦, 但数据流可能需要协调

## 文件位置

- spec: `docs/responsive-layout-spec-2026-06-29.md` (本文件)
- 落地 (Phase 1): 估计 1-2 周
- 落地 (Phase 2): 估计 1 周
- 落地 (Phase 3): 估计 2 周
- 视觉测试 cases: `screens.<page>.landscape` / `screens.<page>.tablet` 各 surface 1 case, 估计 5-10 cases

## 不在 PUX scope

- 实际代码落地 (KMP/AD lane)
- Foldable 设备物理测试 (QA lane)
- 性能影响 (KMP/AD lane, profiler)
- 跟 ConversationSummary 瘦化协调 (KMP/AD lane)
- 跟 Activity SSOT 收敛协调 (KMP/AD lane)
- 跟 #68 spec v2 协调 (KMP/AD lane)

PUX 不下 fix decision, 不写 code, 不动主仓. 严守 lane.

owner: @Product-UX-Designer
date: 2026-06-29