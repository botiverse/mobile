# `:web-container` (`build.raft.webcontainer`)

Standalone Kotlin Multiplatform Hybrid WebView Container and JSB Runtime targeting **Android**, **iOS**, and **Desktop (JVM)**.

## Design Invariants

1. **Zero internal Raft dependencies**: No dependency on `:shared`, `:core`, `:ui-cmp`, or `:designsystem`. Only Compose Multiplatform runtime/foundation/ui, `kotlinx-coroutines`, `kotlinx-serialization-json`, and platform WebView primitives (`androidx.webkit` on Android, `platform.WebKit` on iOS, pluggable `DesktopWebEngine` SPI on Desktop).
2. **Pure content container**: Renders only the web viewport (`WebContainerView`). Navigation chrome, sheets, toolbars, and business policies stay in the host app.
3. **Host-side JSB in 100% Kotlin (`commonMain`)**:
   - Callers register typed async handlers once in Kotlin via `JsbRegistry`.
   - Security gates run uniformly across all three platforms: disabled by default (`JsbPolicy.Disabled`), payload byte cap, main-frame check, exact origin allowlist, `v=1` strict JSON envelope, per-method capability allowlist, per-call timeout, and automatic cancellation on navigation/crash/dispose.
   - Zero `@JavascriptInterface` on Android (`WebViewCompat.addWebMessageListener` + `JavaScriptReplyProxy`).
   - Dual `WKContentWorld` separation on iOS: `window.raftBridge` (`WKScriptMessageHandlerWithReply`) in `WKContentWorld.pageWorld` with strict main-frame/origin verification, internal probes in `WKContentWorld.defaultClientWorld`.
4. **Isolation & Lifecycle**:
   - `WebIsolationScope` (`Default`, `Ephemeral`, `Named(id)`) backed by Android `ProfileStore` (`MULTI_PROFILE`), iOS 17+ singleton-cached `WKWebsiteDataStore(forIdentifier:)` (fallback `nonPersistentDataStore()` on iOS 15–16), and Desktop engine partitions.
   - `WebIsolationScopeManager` evicts and destroys all pooled instances bound to a scope before invoking platform deletion (required by Android `ProfileStore.deleteProfile`) and defers deletion while foreground instances remain active.
5. **Resilience & Offline Resources**:
   - `WebContainerWatchdog` provides DOM blank detection, load stall timeout, and a rolling-window renderer-gone circuit breaker.
   - `WebResourceInterceptor` & `WebOfflineBundleInterceptor` provide path-traversal-safe local asset interception with automatic network fallback; iOS custom-scheme registration strictly rejects `http`/`https`.
   - Desktop uses the pluggable `DesktopWebEngine` SPI (ready for direct macOS `WKWebView` / Windows `WebView2` native bridges or an adapter over an existing off-EDT gateway).

## Minimal Kotlin JSB Usage

```kotlin
val registry = JsbRegistry().apply {
    register(
        method = "device.info",
        requiredCapabilities = setOf("device.read"),
    ) { _, _ ->
        JsbResult.Success(buildJsonObject { put("platform", "kmp") })
    }
}

WebContainerView(
    config = WebContainerConfig(
        url = "https://app.raft.build/demo",
        surfaceId = "demo_app",
        isolationScope = WebIsolationScope.Named("app_demo"),
        jsbPolicy = JsbPolicy(
            enabled = true,
            allowedOrigins = setOf(parseWebContainerOrigin("https://app.raft.build")!!),
            grantedCapabilities = setOf("device.read"),
        ),
    ),
    jsbRegistry = registry,
)
```
