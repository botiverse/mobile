---
name: kuikly-ui-framework
description: Kuikly UI 框架开发与排障助手。用于 Kuikly DSL 或 Kuikly Compose DSL 的页面、组件、布局、事件、手势、状态、列表、路由、模块以及跨平台渲染问题；要求先按项目实际 Kuikly 版本核对文档与源码，再给出可编译实现。
---

# Kuikly UI Framework

## 核心原则

1. 先确认任务使用 Kuikly DSL 还是 Kuikly Compose DSL，不混写两套 API。
2. 先确认项目实际消费的 Kuikly commit/artifact。项目内 fork、submodule、Gradle/Pod/HAR pin 优先于本 skill 自带的上游 checkout。
3. 只读取与当前任务相关的文档、源码和 demo；不要预加载整套索引。
4. 文档与源码冲突时，以项目实际消费版本的源码和可编译 callsite 为准。
5. 不编造属性、事件、平台能力或 import。平台差异必须查对应 renderer/host 实现。

## Reference 选择

### Raft/Mobile 仓库内

先查项目本身：

- `third_party/kuikly-ui` 及项目记录的 Kuikly commit/pin。
- `compose/shared` 中现有生产 callsite 与 focused tests。
- Android、iOS host 中与该能力对应的 renderer/bridge 注册（Raft main 已不再包含 OHOS host，OHOS 冻结在 `release/1.14.x`）。

不要用本 skill 的上游 checkout 覆盖 Raft fork 的行为。

### 通用 Kuikly 问题

按需查 `references/KuiklyUI`：

- 组件：`docs/API/components/`
- 模块：`docs/API/modules/`
- 开发指南：`docs/DevGuide/`
- 快速开始：`docs/QuickStart/`
- Compose DSL：`docs/Compose/`
- QA：`docs/QA/`
- Kuikly DSL 核心：`core/src/commonMain/kotlin/com/tencent/kuikly/core/`
- Compose DSL 核心：`compose/src/commonMain/kotlin/com/tencent/kuikly/compose/`
- Demo：`demo/src/commonMain/kotlin/`
- renderer：`core-render-android/`、`core-render-ios/`、`core-render-ohos/`、`core-render-web/`

`references/all-public-classes.md` 是生成的 Core public API 辅助索引；若它与当前源码不同，以源码为准。

内置 checkout 不在普通 skill 调用中自动 fetch/pull。只有用户明确要求刷新 reference 时，才在确认 checkout 干净后运行 `scripts/update-repository.sh`；更新失败不应改写或重置本地内容。

## 工作流

### 1. 定位 DSL 与能力

- Kuikly DSL：`Pager` / `BasePager`、`View { attr { } event { } }`、observable/vif/vfor。
- Compose DSL：`ComposeContainer`、`setContent`、`@Composable`、`Modifier`、Compose state/recomposition。
- Native 边界：自定义 View 或 Module、renderer 生命周期、线程、回调、平台权限。

如果请求命中更专门的 skill，优先读取对应 skill：动画、协程、资源、自定义 Module、自定义 View、Compose/DSL interop、曝光或重组分析。

### 2. 查最小 reference

常用路由：

| 场景 | 文档 |
|---|---|
| 通用属性与事件 | `docs/API/components/basic-attr-event.md` |
| 布局/FlexBox | `docs/DevGuide/layout.md`, `docs/DevGuide/flexbox-basic.md` |
| 页面与路由 | `docs/DevGuide/multi-page.md`, `docs/API/modules/router.md` |
| 自定义原生 API | `docs/DevGuide/expand-native-api.md` |
| 自定义原生 View | `docs/DevGuide/expand-native-ui.md` |
| Compose 入门 | `docs/Compose/getting-started.md`, `docs/Compose/core-components.md` |
| Compose 布局/Modifier | `docs/Compose/layout.md`, `docs/Compose/modifier.md` |
| Compose 手势/状态 | `docs/Compose/gesture-system.md`, `docs/Compose/status-management.md` |
| Compose 列表/导航 | `docs/Compose/list-and-scroll.md`, `docs/Compose/navigation.md` |
| Compose 线程/重组 | `docs/Compose/thread-and-coroutines.md`, `docs/Compose/recomposition-performance.md` |
| Android/iOS/OHOS 调试 | `docs/DevGuide/android-debug.md`, `iOS-debug.md`, `ohos-debug.md` |
| H5 接入/调试 | `docs/QuickStart/h5.md`, `docs/DevGuide/h5-debug.md` |

具体组件先查同名小写文档，例如 `image.md`、`input.md`、`list.md`；找不到时再 `rg` 源码与 demo，不要猜文件名或 API。

### 3. 用源码和生产 callsite 复核

- 确认类、方法、参数、返回类型与 import 存在。
- 确认 API 所在线程和生命周期；同步 bridge 不做耗时工作。
- 确认目标平台 renderer 真正实现该能力。
- Raft 中同时核对 shared contract 与 Android/iOS host，不把单端实现写成双端能力。

### 4. 实现与验证

- 复用项目已有组件、theme/token、store 和 bridge。
- 为行为合同加 focused test；原生边界补 source-contract 或 native test。
- 至少运行受影响 source set 的 compile/test 和 `git diff --check`。
- 只声明实际运行过的平台；Simulator/Emulator 不等于物理设备。

## Kuikly DSL 速查

```kotlin
@Page("sample")
internal class SamplePage : Pager() {
    override fun body(): ViewBuilder = {
        View {
            attr {
                size(100f, 100f)
                backgroundColor(Color.GREEN)
            }
            event {
                click { /* handle */ }
            }
        }
    }
}
```

响应式与指令必须按当前源码核对：普通值通常使用 `observable`，列表使用 `observableList`；不要把 Compose state API 带入 Kuikly DSL。

## Compose DSL 速查

```kotlin
@Page("sample_compose")
internal class SampleComposePage : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent { SampleScreen() }
    }
}

@Composable
private fun SampleScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hello Kuikly")
    }
}
```

包名不是一刀切：runtime 继续使用 `androidx.compose.runtime.*`，Kuikly UI/foundation/material/animation 使用 `com.tencent.kuikly.compose.*` 下的实际包。始终从当前源码或现有 callsite 复制准确 import。

## Fail-closed 规则

- 文档没有、源码也没有的能力：明确说明未找到；需要时转自定义 View/Module 方案。
- 只有某个平台实现：保留其他平台的显式 unsupported/fallback，不伪造一致性。
- API 或版本不确定：给出所核对的 commit/path 和未验证边界，不用历史包大小、Beta 标签或营销描述推断当前能力。

## 输出要求

先给结论与改动，再列关键验证和未运行边界。只有当路径能帮助复核时才引用它；不要复制整份文档索引或输出未经验证的大段示例。
