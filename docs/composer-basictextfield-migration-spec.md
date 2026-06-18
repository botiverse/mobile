# Composer BasicTextField Migration Spec

## Problem
Kuikly Compose `TextField(value: TextFieldValue)` 在 OHOS 上不兼容。我们传 `TextFieldValue` 但 Kuikly 公开 API 只支持 `TextField(value: String)`，导致：
- `onValueChange` 回调可能不触发或传回的值不正确
- `@` / `#` 触发检测（`detectKuiklyComposerSuggestionTrigger`）依赖 `fieldValue.text` 更新
- 输入 `@` 或 `#` 后建议弹窗不出现

## Root Cause
Kuikly Compose `TextField` 底层用原生输入组件，不暴露 `TextFieldValue` 的 `selection` 参数。我们传 `TextFieldValue` 作为 `value`，Kuikly 可能只消费了 `.text` 部分，忽略 `.selection`，且 `onValueChange` 回调行为不确定。

## Solution
迁移到 Kuikly Compose `BasicTextField` + `TextFieldState`，这是文档推荐的带选区控制的输入方式。

### Kuikly Doc Reference
`docs/Compose/core-components.md` §4-5: `TextField(value: String)` 不暴露 `selection`；推荐 `BasicTextField(state = rememberTextFieldState())` 控制 raw 光标/选区。

## Implementation Plan

### Phase 1: BasicTextField Migration
Replace `TextField(value = fieldValue, onValueChange = {...})` with:
```kotlin
val state = rememberTextFieldState()
BasicTextField(
    state = state,
    ...
)
```

- `state.text` → 当前文本
- `state.edit { replace(start, end, newText) }` → 程序化修改文本
- `state.selection` → 光标位置

### Phase 2: Adapt Suggestion Detection
- `detectKuiklyComposerSuggestionTrigger` 改为读 `state.text`
- `replaceComposerTrigger` 改为用 `state.edit { replace(...) }`
- `insertTextAtSelection` 改为用 `state.edit { replace(selection.start, selection.end, textToInsert) }`

### Phase 3: Adapt Field Value
- `fieldValue` (TextFieldValue) → `state` (TextFieldState)
- `onValueChange` 不再需要手动 set，BasicTextField 自动更新 state

## Impact
- `ThreadComposerPage.kt` — TextField → BasicTextField
- `detectKuiklyComposerSuggestionTrigger` — 入参改为读 state.text
- `replaceComposerTrigger` / `insertTextAtSelection` — 改用 state.edit
- `toComposerFieldValueAtEnd` — 改为 state 初始化

## Risk
- BasicTextField 在 Kuikly OHOS 上的支持程度需要验证
- Android 上 BasicTextField 应该正常工作（标准 Compose API）
