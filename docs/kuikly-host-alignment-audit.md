# Kuikly Host Alignment Audit

Date: 2026-05-20

This notes the current gap between Kuikly's default Android host/router implementation and Slock's later-added Android/OHOS Kuikly hosts. The goal is to stop fixing host behavior one field at a time and move Slock toward the same host contract on both platforms.

## Sources Checked

- Kuikly upstream render defaults: `core-render-android/src/main/java/com/tencent/kuikly/core/render/android/KuiklyRenderView.kt`
- Kuikly upstream page data parser: `core/src/commonMain/kotlin/com/tencent/kuikly/core/pager/PagerData.kt`
- Kuikly upstream Android demo host: `androidApp/src/main/java/com/tencent/kuikly/android/demo/KuiklyRenderActivity.kt`
- Kuikly upstream Android router: `androidApp/src/main/java/com/tencent/kuikly/android/demo/adapter/KRRouterAdapter.kt`
- Slock Android host: `compose/androidApp/src/main/java/build/raft/app/shared/RaftLegacyHostActivity.kt`
- Slock Android host contract: `compose/androidApp/src/main/java/build/raft/app/shared/RaftHostContract.kt`
- Slock OHOS host: `compose/ohosApp/entry/src/main/ets/pages/Index.ets`
- Slock OHOS router: `compose/ohosApp/entry/src/main/ets/kuikly/adapter/RouterAdapter.ets`
- Shared safe area consumer: `compose/shared/src/commonMain/kotlin/build/raft/app/RaftAppSafeArea.kt`
- Slock bridge contract: `docs/kuikly-bridge-module-contract.md`

## Kuikly Default Behavior

Kuikly's Android `KuiklyRenderView.generateWithParams(...)` already creates the root pageData fields consumed by `PagerData`:

- `rootViewWidth` / `rootViewHeight` in dp.
- `statusBarHeight` in dp.
- `platform = android`.
- `deviceWidth` / `deviceHeight` in dp.
- `osVersion`, `appVersion`, `nativeBuild`.
- `safeAreaInsets`, formatted as the Kuikly edge-insets string.
- `activityWidth` / `activityHeight` in dp.
- `density`.
- `feature`.
- User pageData is nested under `param`.

Kuikly `PagerData.init(...)` then reads those root fields directly. For Android, `statusBarHeight` and `safeAreaInsets` are already dp values by the time shared Kotlin sees them.

The upstream Android demo host keeps the host thin:

- `KRRouterAdapter.openPage(...)` calls `KuiklyRenderActivity.start(context, pageName, pageData)`.
- `KuiklyRenderActivity.createPageData()` only adds app-level parameters such as `appId`, `sysLang`, and debug flag.
- System fields are not manually rebuilt in app code; they come from `KuiklyRenderView`.
- Back is forwarded through `kuiklyRenderViewDelegator.onBackPressed()` in `dispatchKeyEvent`.
- Immersive mode uses `setDecorFitsSystemWindows(false)` on API 30+ and explicitly handles cutout/light status/navigation bars.
- Adapter registration is guarded so an existing adapter is not overwritten.

## Slock Current Behavior

Slock's Android host is close but not equivalent:

- `RaftLegacyHostActivity` is a separate host instead of reusing the demo shape directly.
- `RaftPageData.toKuiklyMap()` merges raw pageData and adds `appId`.
- A short-lived `createPageSpec()` hotfix injected `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from Android resources. That has been removed in `c218a15 fix: align safe area with kuikly page data`, which is correct: Kuikly already injects root `statusBarHeight/safeAreaInsets`.
- Slock's extra safe-area keys, where still needed, are passed inside user pageData/`param`, while Kuikly root fields are separate root fields.
- `RaftAppSafeArea.kt` is the shared consumption point and now separates units:
  - Kuikly root `pageData.statusBarHeight` and `pageData.safeAreaInsets` are dp on Android.
  - Slock custom `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from OHOS are raw px.
  - `c218a15` fixed the earlier bug where Android root fields were treated as px and converted with `toDp()` a second time.
- Back forwarding was recently added in `RaftLegacyHostActivity.onBackPressed()`, matching the intent of the upstream demo.
- Immersive mode is simpler than upstream: it uses deprecated system UI flags and does not currently mirror `setDecorFitsSystemWindows(false)`, cutout mode, or navigation bar contrast handling.
- Adapter registration is always assigned once through `RaftAdapters.init(...)`. This is acceptable for the app process, but it differs from upstream's "only set if null" pattern and could matter if tests/demo host and app host coexist.
- The old Android Compose Kuikly island host has been removed. Current Thread work should stay in the shared shell/page route instead of reintroducing local Android page islands.

Slock's OHOS host is also custom:

- `RouterAdapter.ets` correctly routes `openPage` to `pages/Index` with `pageName/pageData`.
- `Index.ets` copies route pageData and manually adds `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from `AppStorage`.
- `EntryAbility.ets` computes those safe-area px values from `getWindowAvoidArea(...)`.
- `Index.ets` gates the HDS immersive tabbar renderer by API/capability before passing `slockNativeTabBarRenderer`.
- OHOS does not currently appear to provide the same root `safeAreaInsets/statusBarHeight` semantics as Android's `KuiklyRenderView.generateWithParams(...)`; the Slock custom px fields remain necessary unless Kuikly OHOS render starts emitting equivalent root fields.

## Gap Matrix

| Area | Kuikly Android default | Slock current | Risk | Recommendation |
| --- | --- | --- | --- | --- |
| Router entry | `KRRouterAdapter -> KuiklyRenderActivity.start(pageName,pageData)` | Similar `SlockKuiklyRouterAdapter -> RaftLegacyHostActivity.start(...)` | Low | Keep the shape, but align `RaftLegacyHostActivity` internals with the demo host. |
| PageData system fields | Render view injects root `statusBarHeight/safeAreaInsets/density/platform` | Slock app pageData adds app data under params; Android resource safe-area injection has been removed | Low/Medium | Keep Android on Kuikly root fields; keep custom keys only as OHOS/host fallback. |
| Safe-area units | Android root fields are dp | OHOS/custom fallback keys are px | Medium | Current `RaftAppSafeArea` split is correct; keep tests so future host keys cannot regress units. |
| Immersive window | Uses `setDecorFitsSystemWindows(false)`, cutout, light status/nav, contrast handling | Simpler deprecated flags | Medium | Port upstream window handling into `RaftLegacyHostActivity` unless product has a reason not to. |
| Back handling | `dispatchKeyEvent` delegates to Kuikly | `onBackPressed` now delegates | Low | Current fix is acceptable; consider modern `OnBackPressedDispatcher` later. |
| Adapter registration | Set adapters only when null | Slock sets once using its own guard | Low/Medium | Keep Slock-specific adapters, but avoid overwriting external/demo adapters in mixed debug processes. |
| Error UI | Demo has loading/error placeholders | Slock root only has container; Kuikly exceptions may surface in shared overlay/OHOS text | Medium | Add a controlled host error surface that logs diagnostics but does not expose raw stack to users. |
| Island usage | Demo opens pages via router/activity | Android Compose islands have been removed | Low | Keep the island path retired; do not add new local Android page islands for product routes. |
| OHOS safe area | Android render has root fields | OHOS host manually injects px fields | Medium | Keep OHOS bridge, but name/convert explicitly so shared code cannot confuse px and dp. |
| Native HDS tabbar | Not part of Android default | OHOS host injects capability renderer | Medium | Keep renderer seam in shared; host should only inject when capability is present. |

## Adapter / Module / Render View Diff

The top safe-area issue was only one symptom. Slock's host also differs from Kuikly `androidApp` in adapter and module coverage.

### Global Adapter Manager

Kuikly upstream demo registers these adapters, usually only when the slot is still `null`:

- `krImageAdapter`
- `krLogAdapter`
- `krUncaughtExceptionHandlerAdapter`
- `krFontAdapter`
- `krColorParseAdapter`
- `krRouterAdapter`
- `krThreadAdapter`
- `krPagViewAdapter`
- `krAPNGViewAdapter`
- `krVideoViewAdapter`
- `krTextPostProcessorAdapter`

Slock Android currently registers:

- `krImageAdapter = SlockKuiklyImageAdapter`
- `krLogAdapter = SlockKuiklyLogAdapter`
- `krUncaughtExceptionHandlerAdapter = SlockKuiklyExceptionAdapter`
- `krFontAdapter = SlockKuiklyFontAdapter`
- `krRouterAdapter = SlockKuiklyRouterAdapter`
- `krThreadAdapter = RaftThreadAdapter`

Slock Android does not currently register:

- `krColorParseAdapter`
- `krPagViewAdapter`
- `krAPNGViewAdapter`
- `krVideoViewAdapter`
- `krTextPostProcessorAdapter`

Risk notes:

- `krColorParseAdapter`: low immediate risk if shared uses only literal colors, but any Kuikly CSS color parsing edge case will differ from the demo/default path.
- `krTextPostProcessorAdapter`: medium risk for text rendering parity, emoji/font post-processing, accessibility labels, or platform text adjustments. This is relevant because several reported issues are visual/text-quality related.
- `PAG/APNG/Video`: low immediate risk for current Slock pages unless animated media or video attachments become shared-rendered.
- Registration policy: upstream avoids overwriting adapters if a host already set them. Slock's own `initialized` guard is okay for the app, but it still unconditionally owns the global adapter manager once initialized. Mixed demo/app testing can diverge.

### External Modules

Kuikly upstream demo exposes:

- `HRBridgeModule` (`KRBridgeModule`): demo/native capability bridge. It supports alert, close/open page, pasteboard, toast, log, telemetry stubs, local time/timestamp, date formatting, local image path, asset reads, and demo helpers.
- `HRShareModule` (`KRShareModule`): placeholder share module.
- Optional TDF module in the demo handler.

Slock Android exposes:

- `HRBridgeModule` (`RaftBridgeModule`): Slock production bridge. It includes auth/session storage, selected server/session actions, event dispatch back to Android islands, logging/toast, `authRequest`, and other Slock-specific capabilities.

Slock OHOS exposes:

- `HRBridgeModule` (`KRBridgeModule.ets`): OHOS implementation of the same shared bridge name. It includes `authRequest`, `relationalStore`, asset reads, time helpers, log, and stubs for several demo-like methods.

Module conclusions:

- The shared bridge name `HRBridgeModule` is good: Android and OHOS can bind the same shared code.
- The method surface is now documented in `docs/kuikly-bridge-module-contract.md`. New shared flows should only depend on methods marked required there, or promote an optional method by updating that contract first.
- OHOS still has TODO/stub methods (`closePage`, `openPage`, pasteboard, toast, date formatting). If shared code starts depending on those methods, OHOS behavior will diverge even when Android works.
- Android currently implements network with `HttpURLConnection` inside the bridge; OHOS uses ArkTS `http`. That is acceptable as host capability, but shared must own request/response shape and retry/error policy.
- `relationalStore` exists on OHOS but Android side should be checked for equivalent shared local-store bridge behavior before moving more state machines fully into KMP.
- `KRMyModule` / `KRMyView` have been removed from OHOS production registration/source; future smoke/demo components should live in a sample/debug host.

### External Render Views

Kuikly upstream demo exports:

- `KuiklyPageView`: native page embedding demo.
- `MyModalView`: overrides built-in modal behavior for demo accessibility.
- Optional PAG/APNG/video adapters via adapter manager.

Slock Android exports:

- `SlockLucideIconView`: native Lucide/icon render view.

Slock OHOS exports:

- `SlockLucideIconView`: native Lucide/icon render view.
- `SlockOhosHdsTabBarView`: native HDS immersive tabbar renderer.

Render-view conclusions:

- `SlockLucideIconView` is a valid Slock-specific production view, but Android/OHOS implementations should be treated as one capability with the same input schema and fallback path.
- `SlockOhosHdsTabBarView` is intentionally OHOS-only, behind shared tabbar renderer capability. This is acceptable because shared still owns selected tab and route state.
- `KRMyView` has been removed from OHOS production registration/source.
- Slock does not currently mirror upstream `MyModalView`; if shared modal/action sheet behavior differs on Android, this is a place to check.

### Performance / Monitoring Hooks

Kuikly upstream demo's `ContextCodeHandler` wires launch/frame/memory performance callbacks and logs performance data. Slock Android currently logs Slock diagnostics around island attach/update and exceptions, but does not mirror the full Kuikly `KRMonitorType.LAUNCH/FRAME/MEMORY` setup in the main host path.

Recommendation:

- Add a Slock host performance adapter layer that records Kuikly first paint/createInstance/frame metrics into existing Slock diagnostics.
- Use it for the reported cold `createInstance` and route-switch jank work instead of inferring from unrelated thread/home diagnostics.

## Recommended Migration Order

1. **Keep shared safe-area units pinned.**
   - Android: consume `PagerData.statusBarHeight` / `safeAreaInsets` as dp.
   - OHOS/custom host fallbacks: consume `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from `pageData.params` as px and convert to dp.
   - This is already implemented in `c218a15`; keep `RaftSafeAreaTest` as the regression gate.

2. **Align `RaftLegacyHostActivity` with upstream Android window handling.**
   - Bring over `setDecorFitsSystemWindows(false)` for API 30+.
   - Mirror cutout mode and status/navigation contrast handling.
   - Keep Slock colors and product-specific theme decisions separate.

3. **Define the Slock bridge/module contract.**
   - Keep `docs/kuikly-bridge-module-contract.md` current for `HRBridgeModule` method names and payload/result schemas.
   - Mark each method as required, optional, debug-only, or deprecated.
   - Make Android/OHOS parity explicit for `authRequest`, `relationalStore`, storage/session, route/back, clipboard, toast/log, file picker/upload, and diagnostics.
   - Keep demo-only modules/views out of OHOS production registration.

4. **Normalize the Slock pageData contract.**
   - Keep Slock app data under user pageData/`param`.
   - Do not duplicate Kuikly root fields unless the platform render lacks them.
   - Use explicit names for px fallbacks, e.g. `slockSafeAreaTopPx`, to avoid collision with Kuikly root `statusBarHeight`.

5. **Complete adapter parity decisions.**
   - Decide whether Slock needs `krColorParseAdapter` and `krTextPostProcessorAdapter`.
   - Keep PAG/APNG/video as explicit "not needed yet" unless attachments/media require them.
   - Avoid overwriting global adapters in mixed debug/demo contexts.

6. **Keep Android islands retired.**
   - Use `compose_slock_app_shell` + shared router for Home -> Thread -> Settings/Members.
   - Add future previews as explicit visual/debug harnesses, not product-looking local Android page islands.

7. **Add host parity smoke tests/checks.**
   - Android app shell launch receives non-zero root safe area from Kuikly render.
   - Shared safe-area resolver unit covers Android dp root fields and OHOS px fallback fields separately.
   - Router open/close preserves pageData and Back returns inside shared route before finishing Activity.
   - Required `HRBridgeModule` methods exist on both Android and OHOS and return the documented error schema.

8. **Document platform capability seams.**
   - HDS tabbar renderer, file picker, keyboard, storage, network, and secure store should all be host capabilities behind shared contracts.
   - Shared state/router decides behavior; host only provides platform primitives.

## Immediate Follow-Ups

- Rename or separate Slock custom safe-area fallback keys to make units explicit.
- Open a follow-up for `RaftLegacyHostActivity` window handling parity with Kuikly upstream.
- Add Android/OHOS parity tests/checks for the required `HRBridgeModule` methods documented in `docs/kuikly-bridge-module-contract.md`.
- Decide whether to add `krTextPostProcessorAdapter` / `krColorParseAdapter` for Android text/color parity.
- Open a follow-up to retire remaining Android Compose islands from main navigation once shared app shell covers the route.
- Add a lightweight host parity check that fails if Android app shell starts relying on resource-derived safe-area again.
