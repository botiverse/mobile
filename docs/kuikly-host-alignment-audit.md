# Kuikly Host Alignment Audit

Date: 2026-05-20

This notes the current gap between Kuikly's default Android host/router implementation and Slock's later-added Android/OHOS Kuikly hosts. The goal is to stop fixing host behavior one field at a time and move Slock toward the same host contract on both platforms.

## Sources Checked

- Kuikly upstream render defaults: `core-render-android/src/main/java/com/tencent/kuikly/core/render/android/KuiklyRenderView.kt`
- Kuikly upstream page data parser: `core/src/commonMain/kotlin/com/tencent/kuikly/core/pager/PagerData.kt`
- Kuikly upstream Android demo host: `androidApp/src/main/java/com/tencent/kuikly/android/demo/KuiklyRenderActivity.kt`
- Kuikly upstream Android router: `androidApp/src/main/java/com/tencent/kuikly/android/demo/adapter/KRRouterAdapter.kt`
- Slock Android host: `app/src/main/java/ai/slock/android/shared/SlockKuiklyActivity.kt`
- Slock Android host contract: `app/src/main/java/ai/slock/android/shared/SlockKuiklyHostContract.kt`
- Slock Android island host: `app/src/main/java/ai/slock/android/shared/SlockKuiklyIsland.kt`
- Slock OHOS host: `compose/ohosApp/entry/src/main/ets/pages/Index.ets`
- Slock OHOS router: `compose/ohosApp/entry/src/main/ets/kuikly/adapter/RouterAdapter.ets`
- Shared safe area consumer: `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockSafeArea.kt`

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

- `SlockKuiklyActivity` is a separate host instead of reusing the demo shape directly.
- `SlockKuiklyPageData.toKuiklyMap()` merges raw pageData and adds `appId`.
- A short-lived `createPageSpec()` hotfix injected `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from Android resources. That has been removed in `c218a15 fix: align safe area with kuikly page data`, which is correct: Kuikly already injects root `statusBarHeight/safeAreaInsets`.
- Slock's extra safe-area keys, where still needed, are passed inside user pageData/`param`, while Kuikly root fields are separate root fields.
- `SlockSafeArea.kt` is the shared consumption point and now separates units:
  - Kuikly root `pageData.statusBarHeight` and `pageData.safeAreaInsets` are dp on Android.
  - Slock custom `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from OHOS are raw px.
  - `c218a15` fixed the earlier bug where Android root fields were treated as px and converted with `toDp()` a second time.
- Back forwarding was recently added in `SlockKuiklyActivity.onBackPressed()`, matching the intent of the upstream demo.
- Immersive mode is simpler than upstream: it uses deprecated system UI flags and does not currently mirror `setDecorFitsSystemWindows(false)`, cutout mode, or navigation bar contrast handling.
- Adapter registration is always assigned once through `SlockKuiklyAdapters.init(...)`. This is acceptable for the app process, but it differs from upstream's "only set if null" pattern and could matter if tests/demo host and app host coexist.
- `SlockKuiklyIslandHost` embeds multiple Kuikly pages inside Android Compose using separate `KuiklyRenderViewBaseDelegator` instances. This was useful during migration, but it is the opposite of the long-term "one shared shell/router container" direction.

Slock's OHOS host is also custom:

- `RouterAdapter.ets` correctly routes `openPage` to `pages/Index` with `pageName/pageData`.
- `Index.ets` copies route pageData and manually adds `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from `AppStorage`.
- `EntryAbility.ets` computes those safe-area px values from `getWindowAvoidArea(...)`.
- `Index.ets` gates the HDS immersive tabbar renderer by API/capability before passing `slockNativeTabBarRenderer`.
- OHOS does not currently appear to provide the same root `safeAreaInsets/statusBarHeight` semantics as Android's `KuiklyRenderView.generateWithParams(...)`; the Slock custom px fields remain necessary unless Kuikly OHOS render starts emitting equivalent root fields.

## Gap Matrix

| Area | Kuikly Android default | Slock current | Risk | Recommendation |
| --- | --- | --- | --- | --- |
| Router entry | `KRRouterAdapter -> KuiklyRenderActivity.start(pageName,pageData)` | Similar `SlockKuiklyRouterAdapter -> SlockKuiklyActivity.start(...)` | Low | Keep the shape, but align `SlockKuiklyActivity` internals with the demo host. |
| PageData system fields | Render view injects root `statusBarHeight/safeAreaInsets/density/platform` | Slock app pageData adds app data under params; Android resource safe-area injection has been removed | Low/Medium | Keep Android on Kuikly root fields; keep custom keys only as OHOS/host fallback. |
| Safe-area units | Android root fields are dp | OHOS/custom fallback keys are px | Medium | Current `SlockSafeArea` split is correct; keep tests so future host keys cannot regress units. |
| Immersive window | Uses `setDecorFitsSystemWindows(false)`, cutout, light status/nav, contrast handling | Simpler deprecated flags | Medium | Port upstream window handling into `SlockKuiklyActivity` unless product has a reason not to. |
| Back handling | `dispatchKeyEvent` delegates to Kuikly | `onBackPressed` now delegates | Low | Current fix is acceptable; consider modern `OnBackPressedDispatcher` later. |
| Adapter registration | Set adapters only when null | Slock sets once using its own guard | Low/Medium | Keep Slock-specific adapters, but avoid overwriting external/demo adapters in mixed debug processes. |
| Error UI | Demo has loading/error placeholders | Slock root only has container; Kuikly exceptions may surface in shared overlay/OHOS text | Medium | Add a controlled host error surface that logs diagnostics but does not expose raw stack to users. |
| Island usage | Demo opens pages via router/activity | Slock still has Android Compose islands | High for perf/navigation | Continue removing islands in favor of `compose_slock_app_shell` single container/router. |
| OHOS safe area | Android render has root fields | OHOS host manually injects px fields | Medium | Keep OHOS bridge, but name/convert explicitly so shared code cannot confuse px and dp. |
| Native HDS tabbar | Not part of Android default | OHOS host injects capability renderer | Medium | Keep renderer seam in shared; host should only inject when capability is present. |

## Recommended Migration Order

1. **Keep shared safe-area units pinned.**
   - Android: consume `PagerData.statusBarHeight` / `safeAreaInsets` as dp.
   - OHOS/custom host fallbacks: consume `safeAreaTop/statusBarHeight/safeAreaBottom/navigationBarHeight` from `pageData.params` as px and convert to dp.
   - This is already implemented in `c218a15`; keep `SlockSafeAreaTest` as the regression gate.

2. **Align `SlockKuiklyActivity` with upstream Android window handling.**
   - Bring over `setDecorFitsSystemWindows(false)` for API 30+.
   - Mirror cutout mode and status/navigation contrast handling.
   - Keep Slock colors and product-specific theme decisions separate.

3. **Normalize the Slock pageData contract.**
   - Keep Slock app data under user pageData/`param`.
   - Do not duplicate Kuikly root fields unless the platform render lacks them.
   - Use explicit names for px fallbacks, e.g. `slockSafeAreaTopPx`, to avoid collision with Kuikly root `statusBarHeight`.

4. **Reduce Android islands.**
   - Use `compose_slock_app_shell` + shared router for Home -> Thread -> Settings/Members.
   - Keep `SlockKuiklyPageIsland` only for temporary migration slices or isolated previews.

5. **Add host parity smoke tests/checks.**
   - Android app shell launch receives non-zero root safe area from Kuikly render.
   - Shared safe-area resolver unit covers Android dp root fields and OHOS px fallback fields separately.
   - Router open/close preserves pageData and Back returns inside shared route before finishing Activity.

6. **Document platform capability seams.**
   - HDS tabbar renderer, file picker, keyboard, storage, network, and secure store should all be host capabilities behind shared contracts.
   - Shared state/router decides behavior; host only provides platform primitives.

## Immediate Follow-Ups

- Rename or separate Slock custom safe-area fallback keys to make units explicit.
- Open a follow-up for `SlockKuiklyActivity` window handling parity with Kuikly upstream.
- Open a follow-up to retire remaining Android Compose islands from main navigation once shared app shell covers the route.
- Add a lightweight host parity check that fails if Android app shell starts relying on resource-derived safe-area again.
