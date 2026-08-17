---
name: kuikly-expand-api
description: Kuikly 自定义 Module / Native API bridge 开发助手。用于在 Kuikly DSL 或 Compose DSL 定义 Module，并在 Android、iOS、HarmonyOS/OHOS、H5 或小程序 host 实现和注册；涵盖同步/异步、JSON/原子参数、回调生命周期、线程与跨端合同验证。
---

# Kuikly Custom Module

## 先确认版本与边界

1. 以项目实际消费的 Kuikly fork/commit 为准；Raft/Mobile 先查 `third_party/kuikly-ui`、shared Module 与各端 host 注册。
2. 明确目标平台、同步/异步、参数编码、回调是否常驻、取消/错误合同。
3. `moduleName()`、native 注册名和 iOS Objective-C runtime 类名必须一致。
4. 同步 bridge 会阻塞调用线程，不做 I/O、权限请求、弹 UI 或其他耗时工作。
5. 只实现任务需要的平台；未实现平台保留显式 unsupported/fallback。

## Shared/Kotlin 定义

```kotlin
import com.tencent.kuikly.core.module.CallbackFn
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal class MyLogModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun log(content: String) {
        asyncToNativeMethod(
            methodName = "log",
            data = JSONObject().apply { put("content", content) },
            callbackFn = null,
        )
    }

    fun logWithCallback(content: String, callback: CallbackFn) {
        asyncToNativeMethod(
            methodName = "logWithCallback",
            data = JSONObject().apply { put("content", content) },
            callbackFn = callback,
        )
    }

    fun syncLog(content: String): String =
        syncToNativeMethod(
            methodName = "syncLog",
            data = JSONObject().apply { put("content", content) },
            callbackFn = null,
        )

    fun observe(callback: CallbackFn): CallbackRef =
        requireNotNull(
            toNative(
                keepCallbackAlive = true,
                methodName = "observe",
                param = null,
                callback = callback,
                syncCall = false,
            ).callbackRef,
        )

    fun stopObserving(ref: CallbackRef) {
        removeCallback(ref)
    }

    companion object {
        const val MODULE_NAME = "KRMyLogModule"
    }
}
```

`JSONObject` overload serializes JSON across the bridge. For `ByteArray` or atomic values, use the `Array<Any>` overload and only types supported by the current `Module.kt` (`String`, `Int`, `Float`, `ByteArray`); confirm return/callback types in that exact source.

## Shared 注册与使用

Register once in the page/container owner and merge with inherited modules:

```kotlin
override fun createExternalModules(): Map<String, Module>? =
    (super.createExternalModules().orEmpty() +
        (MyLogModule.MODULE_NAME to MyLogModule()))
```

Kuikly DSL pages can call `acquireModule<MyLogModule>(MODULE_NAME)`. In Compose DSL, use the current container directly:

```kotlin
val module = LocalActivity.current.acquireModule<MyLogModule>(MyLogModule.MODULE_NAME)
```

Do not cast `LocalActivity.current.getPager()` to a concrete `Pager`; `ComposeContainer` already inherits `Pager`.

## Native 实现

Read [references/MODULE_IMPLEMENT.md](references/MODULE_IMPLEMENT.md) before writing native code. It contains the current registration shapes for Android, iOS and OHOS plus H5/mini-app notes.

Critical contracts:

- Android: export a `KuiklyRenderBaseModule` under the exact name in the host delegate.
- iOS: `KRBaseModule` is created through the Objective-C runtime. ObjC class name must equal the shared module name; Swift needs `@objc(KRMyLogModule)` and Objective-C-visible selectors, not bare `@objc` alone.
- OHOS/ArkTS: register a creator in `getCustomRenderModuleCreatorRegisterMap`; `syncMode()` must match supported call modes.
- JSON parameters are transport envelopes. Native code must decode the value under the renderer's parameter key instead of treating the entire JSON string as the business value.
- A native callback for `keepCallbackAlive=true` can fire repeatedly; shared code owns callback removal and native code owns listener/resource cleanup.

## Validation

- Shared compile/test proves method names, parameter encoding, return and callback behavior.
- Add a mutation/source-contract test for each host registration and selector/method dispatch.
- Compile every modified native target; run focused native tests where available.
- Test cancellation, duplicate callback, page destruction and unsupported-platform paths.
- `git diff --check`; report Simulator/Emulator and physical device separately.

## Fail-closed

If the current Kuikly source differs from this skill, follow the source and update the skill with evidence. Never infer platform parity from a shared Module definition alone.
