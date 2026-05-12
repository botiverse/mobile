# Inline Chip 对齐问题 — 情况说明与问题分析报告

**日期：** 2026-05-13  
**文件：** `app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt`

---

## 一、现象描述

在 Thread 消息体中，含有 `@mention` 或 `#tag` 的文本行存在两个视觉问题：

| # | 问题 | 触发场景 |
|---|------|---------|
| 1 | **chip 偏下（下沉）** — chip 在文字行中显示靠下，上下不居中 | 任何包含 inline tag/mention 的行 |
| 2 | **最后一排白边** — 当 chip 出现在消息最后一行时，行下方有一条白色空白 | chip 所在行是段落/消息的最后一行 |

截图参考：`@KMP-专家` 消息头，chip 可见偏下，末行有白边。

---

## 二、技术背景

### 2.1 Compose `InlineTextContent` 机制

Compose 的 `InlineTextContent` 通过 `Placeholder` 在文本流中预留矩形槽位（slot），由布局引擎根据 `PlaceholderVerticalAlign` 决定槽位相对于文字行的垂直位置。

```
文字行 = 行高盒子（line box）
           │
           ├── 上半导（half leading）
           ├── ascent（字符上行）
           ├── baseline
           ├── descent（字符下行）
           └── 下半导（half leading）
```

`PlaceholderVerticalAlign` 枚举含义：

| 值 | 含义 |
|----|------|
| `TextCenter` | slot 中心对齐文字行盒子中心（含 leading） |
| `AboveBaseline` | slot 底部对齐 baseline |
| `Center` | slot 中心对齐整个排版行（含段落 padding，更高） |
| `Bottom` | slot 底部对齐行底（含下半导） |
| `Top` | slot 顶部对齐行顶 |

### 2.2 当前代码状态

```kotlin
// 占位高度 = 16sp（chip 内文字行高）+ 1dp（top padding）+ 4dp（bottom padding）
// 在 15sp 字号、21sp 行高的 body 字体下，约 = 21dp ≈ 21sp
private fun threadInlineChipPlaceholderHeightSp(density: Density): TextUnit

// 垂直对齐
placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter

// Body 正文样式
val ThreadMessageBodyTextStyle = SlockType.Body.copy(
    fontSize = 15.sp,
    lineHeight = 21.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

// Chip 内部文字样式
val ThreadInlineChipTextStyle = SlockType.Caption.copy(
    fontSize = 14.sp,
    lineHeight = 16.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)
```

---

## 三、根本原因分析

### 3.1 问题 1：chip 偏下

**原因：`PlaceholderVerticalAlign.TextCenter` 对齐的是"行盒子中心"，而非视觉文字中心。**

Android 默认字体渲染（即使 `includeFontPadding = false`）中，Space Grotesk 字体的 ascent/descent 比例约为 **70% ascent / 30% descent**，不对称。`TextCenter` 把 slot 中心对齐 ascent+descent 区间的中心，但这个中心比人眼感知的"文字中心"（大写字母/汉字光学中心）略低。因此 chip 视觉上偏低。

**量化估算：**

| 参数 | 数值 |
|------|------|
| body fontSize | 15 sp |
| body lineHeight | 21 sp |
| 半导（half leading） | (21 - 15) / 2 = 3 sp |
| ascent（估算） | 15 × 0.70 = 10.5 sp |
| descent（估算） | 15 × 0.30 = 4.5 sp |
| em 盒子中心距行顶 | 3 + 10.5/2 × ... （因字体 metrics 差异，实际偏移约 1–2 sp）|

`TextCenter` 使 slot 的几何中心 = 行中心；但 Space Grotesk 的光学中心在几何中心之上约 1.5dp，导致 chip 视觉偏下。

**已尝试方案：**
- `PlaceholderVerticalAlign.TextBottom` → chip 贴 baseline，偏高，不可接受
- 非对称 padding（top = 1dp, bottom = 4dp）→ 轻微改善，但本质未变
- Body 加 `LineHeightStyle(trim = Both)` → 引入白边回归，已回滚

### 3.2 问题 2：最后一排白边

**原因：placeholder height > 行的有效绘制区域，溢出导致行高被撑高，但文字容器有 `clip` 裁剪，造成白色空白可见。**

详细链路：

```
文字段落容器（BasicText / MarkdownText）
  └── 段落行盒子（line box = 21sp）
        └── Placeholder slot（21dp ≈ 21sp，但换算误差 + 1dp top + 4dp bottom 实际 > 21sp）
              └── 溢出部分被父容器 clip，留出空白像素
```

`threadInlineChipPlaceholderHeightSp` 计算：

```kotlin
ThreadInlineChipTextStyle.lineHeight.toDp()   // 16sp → 约 16dp
+ ThreadInlineChipVerticalPaddingTop           // 1dp
+ ThreadInlineChipVerticalPaddingBottom        // 4dp
= 21dp
```

**问题所在**：
1. `16sp.toDp()` 依赖 `fontScale`。当系统字体缩放 ≠ 1.0，`16sp.toDp()` ≠ `16dp`，导致实际高度偏离 `21sp`（body lineHeight）。
2. 即使 fontScale=1.0，`21dp` 转回 `Sp` 时经过浮点运算，可能有 0.5px 误差，导致 placeholder 轻微超出行高。
3. `PlaceholderVerticalAlign.TextCenter` 下，slot 溢出行盒子底部，最后一行无后续行来"承接"溢出，白边可见。

---

## 四、已尝试方案汇总

| 方案 | 变更 | 结果 |
|------|------|------|
| 非对称 chip padding (top=1, bottom=4) | 偏上 padding | 轻微改善，不彻底 |
| `PlaceholderVerticalAlign.TextBottom` | 对齐 baseline | 白边消失，但 chip 偏高 |
| Body 加 `LineHeightStyle(trim=Both)` | 裁剪行半导 | 使问题更严重，已回滚 |
| Body 加 `includeFontPadding=false` | 移除字体内嵌 padding | 保留，有轻微改善 |
| 减小 placeholder bottom padding | 缩短 slot | 无明显变化 |

---

## 五、推荐修复方案

### 方案 A（推荐）：使用 `AboveBaseline` + 精确 slot 高度

将 `PlaceholderVerticalAlign` 改为 `AboveBaseline`，slot 底部紧贴 baseline。再将 slot 高度设为 body 的 **ascent**（从 baseline 向上），chip 内部不加 bottom padding，只加 top padding 作微调。

```kotlin
// slot 高度 = body ascent 估算（15sp × 0.76 ≈ 11.4sp，实测约 18dp 含内部 padding 后视觉居中）
// 需要通过 TextMeasurer 量取实际 ascent：

val metrics = textMeasurer.measure("A", style = ThreadMessageBodyTextStyle)
val ascentDp = with(density) { -metrics.firstBaseline.toDp() }  // firstBaseline 是负值

Placeholder(
    width = ...,
    height = (ascentDp + descentDp).toSp(),  // 精确等于 em 盒子
    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline
)
```

优点：slot 精确坐落于 em 盒子，不溢出行盒，白边消失；chip 视觉上居中。

### 方案 B：`TextCenter` + 负 top offset

保持 `TextCenter`，在 chip Composable 内对根节点加 `offset(y = (-2).dp)`，人工把 chip 上移补偿字体偏差。白边需另外通过把 placeholder 高度缩减到精确 `21sp`（不加 padding 进 slot，padding 改为 offset）解决。

### 方案 C：绕开 InlineTextContent，改用自定义 Layout

彻底脱离 `BasicText` 内联机制，用 `Layout` + `SubcomposeLayout` 手动排布文字与 chip 混排，精确控制每个元素的 y 偏移。开发代价最高，但视觉最精确。

---

## 六、关键代码位置速查

| 内容 | 文件位置 |
|------|---------|
| Body 正文样式 | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L265) |
| Chip 内文字样式 | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L4865) |
| Placeholder 高度计算 | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L4851) |
| Placeholder 垂直对齐 | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L4981) |
| Chip padding 常量 | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L4861) |
| ThreadChip Composable | [ThreadScreen.kt](../app/src/main/java/ai/slock/android/feature/thread/ThreadScreen.kt#L5408) |

---

## 七、结论

两个问题均源于 **Compose `InlineTextContent` 的 `Placeholder` API 与 Android 字体 metrics 之间的阻抗不匹配**：

1. `TextCenter` 按几何中心对齐，但 Space Grotesk 的光学中心略高 → chip 偏下
2. Placeholder 高度用 `sp+dp` 混合计算，存在换算误差，且超出行盒底部时末行白边 → 白边

推荐优先尝试**方案 A**：用 `TextMeasurer` 量取实际 ascent，配合 `AboveBaseline` 对齐，从根源上解决两个问题。
