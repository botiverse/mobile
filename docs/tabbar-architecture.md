# Bottom Tab Bar Architecture (Home / Tasks / Members / Settings)

This document describes how the app-shell bottom tab bar is rendered across
Android, iOS, and HarmonyOS (OHOS), the shared contract that keeps the three
in sync, and the hit-testing/tap-through design. It is the reference for
anyone touching `SlockAppBottomTabBarHost` or the per-platform native tab bar
views.

## The one thing to understand first

There is exactly **one** app shell (`compose_slock_app_shell`, a long-lived
Kuikly root). Home / Tasks / Members / Settings are retained tabs *inside* that
shell — switching tabs does not launch a new native page. The bottom tab bar
is a single shared Composable host (`SlockAppBottomTabBarHost`) that, depending
on the platform, either draws the bar itself in Compose or delegates to a
**native tab bar view** and only feeds it data.

The tab *content* and the tab *bar* are two different render-granularity
concepts and must not be conflated:

- **Tab content** (Home/Tasks/Members/…): Compose, sometimes wrapped in a
  native child-page container (`SlockKuiklyPageView` / `glassStyle=liquid` on
  iOS). This is the "native view embeds a Kuikly page" demo granularity.
- **Tab bar itself**: on OHOS/iOS a *native tab bar render view* — the native
  view **is** the bar; it is not a child page. Shared code only hands it
  `keys/labels/icons/selectedKey` and gets back a selection callback.

## Renderer selection

`SlockTabBar.kt` reads the page-data param `slockNativeTabBarRenderer` (legacy
alias `nativeTabBarRenderer`) and maps it to `SlockAppTabBarRenderer`. An
explicit page-data value always wins. When the value is absent, the platform
capability supplies the default: iOS returns `iosLiquidGlass`, while Android
and OHOS return no default and keep the shared fallback unless their host
explicitly opts into a native renderer.

| param value                         | renderer            | who renders the bar                    |
| ----------------------------------- | ------------------- | -------------------------------------- |
| `ohosHdsImmersive` / `hdsImmersive` | `OhosHdsImmersive`  | OHOS native `HdsTabs` (HDS material)   |
| `iosLiquidGlass` / `iosNativeFloating` | `IosLiquidGlass` | iOS native `UITabBar` (Liquid Glass)   |
| `androidFloating` / `androidNativeFloating` | `AndroidFloating` | shared Kuikly floating dock |
| anything else / absent              | `SharedCompose`     | shared Compose `SlockBottomTabBar`     |

Each host decides its own param:

- **OHOS** (`compose/ohosApp/entry/src/main/ets/pages/Index.ets`): sets
  `ohosHdsImmersive` only when API ≥ `HDS_MIN_API_VERSION` **and**
  `canIUse('SystemCapability.UIDesign.HDSComponent.Core')`; otherwise
  `sharedCompose` with a `slockNativeTabBarFallbackReason`.
- **iOS** (`compose/iosApp/iosApp/ContentView.swift`): sets `iosLiquidGlass` on
  the SwiftUI root. The iOS shared platform capability also defaults missing
  renderer values to `iosLiquidGlass`, because post-auth/server-selection
  Router launches create a new app-shell page without inheriting root
  pageData.
- **Android**: uses `sharedCompose` by default. The old floating aliases keep
  their floating presentation through a shared Kuikly renderer.

The `SharedCompose` fallback guarantees a working bar everywhere, so the native
paths can assume their platform capability is present.

## Shared → native contract

Both native renderers share **one** view name and **one** prop/event
contract, defined in `SlockTabBar.kt` (`SlockNativeFloatingTabBar` /
`SlockNativeFloatingTabBarAttr`):

- View name: `SlockNativeFloatingTabBarView`.
- Props (all strings joined by `U+001F`, one entry per tab):
  - `keysText`, `labelsText`, `iconsText` (Lucide icon wire names),
    `enabledText` (`"1"`/`"0"`).
  - `selectedKey` (the key of the selected tab).
  - `bottomSafeArea` (float, dp).
- Event: `select` → `{ key, index }`; shared side calls `onSelect(key)`.

Native implementations:

- OHOS: `compose/ohosApp/entry/src/main/ets/kuikly/components/SlockNativeFloatingTabBarView.ets`
  registered in `KuiklyViewDelegate.ets` under the shared view name. Uses
  `@kit.UIDesignKit` `HdsTabs` + `barFloatingStyle({ systemMaterialEffect })`
  for the native adaptive-material floating dock.
- iOS: `compose/iosApp/iosApp/KuiklyExpand/Components/SlockNativeFloatingTabBarView.m`
  wraps a UIKit `UITabBar` (system Liquid Glass on iOS 26).

Because the contract is identical, swapping a platform's *look* only touches
that platform's native file — the shared host, insets, and z-index are
untouched.

## Host geometry & content inset

`SlockAppBottomTabBarHost` places the bar as a bottom-aligned overlay
(`zIndex = SlockAppBottomTabBarHostZIndex = 1000`, shared with the floating
overlay host so menus and the bar agree on stacking).

- Native floating hosts (OHOS/iOS) are overlays: content keeps filling the
  screen behind them; `slockAppBottomTabBarContentInset` only reserves
  `bottomSafeArea + Lg` so the last scroll rows clear the dock.
- The native floating host box is `92dp + bottomSafeArea`. The OHOS HDS pill is
  a `56dp` bar lifted `26dp` off the host bottom, inset `18dp` per side →
  it occupies `[top+10 .. top+66]` of the 92dp region.
- `SharedCompose` uses a solid `58dp + safe area` bar and insets content by the
  full host height + `Lg`.

## Hit-testing & tap-through (important)

Different platforms need different tap containment:

- **iOS `UITabBar`** consumes touches within its own bounds — no extra work.
  (The previous hand-rolled dock relied on a `pointInside:` override; the real
  `UITabBar` makes that unnecessary.)
- **Android** `AndroidFloating` and `SharedCompose` are both shared Kuikly
  renderers. Their standard Compose hit testing consumes tab-item taps; there
  is no Android native tab-bar view or separate native touch path.
- **OHOS is the tricky one.** Custom native views are hosted by
  `KRForwardArkTSView`, which the Kuikly OHOS renderer initializes with
  `ARKUI_HIT_TEST_MODE_TRANSPARENT` ("ForwardArkTSView 不会遮挡同层级事件").
  That means a tap on the dock **also** reaches the content list behind it —
  the reported tap-through. This is **not** a z-index problem: the OHOS
  renderer *does* honor `zIndex` (`KRBasePropsHandler` → `NODE_Z_INDEX`), but
  transparent hit mode passes the event to lower siblings regardless of z.

  Fix: `SlockAppBottomTabBarHost` puts a **Compose tap blocker** under the OHOS
  dock, sized to exactly the pill rect (`slockOhosHdsDockBlocker*` helpers:
  top `10dp`, height `56dp`, side inset `18dp`), with a no-op `clickable`. The
  transparent native view is drawn on top; taps fall through it and are
  consumed by the blocker before they can reach the content list. Margins
  around the pill stay tap-through, matching iOS `pointInside` semantics. The
  blocker is gated to the `OhosHdsImmersive` renderer only.

## History / gotchas

- Commit `26c5e658` ("fix(native): align child hosts and ohos tab dock")
  accidentally deleted the real HDS implementation and replaced it with a
  hand-drawn `Row` dock (flat `#CCFFFFFF` fill, no system material) — this is
  why OHOS stopped looking like a native HDS tab bar. It was restored under
  task #216.
- Also under task #216 the OHOS ArkTS file/class/struct were renamed from
  `SlockOhosHdsTabBar*` to `SlockNativeFloatingTabBar*` so all three platforms
  share both the registered view name **and** the class name. The registered
  view name (`SlockNativeFloatingTabBarView`) was already consistent before the
  rename; only the OHOS symbol names were out of step.
- Do not "fix" OHOS tap-through by bumping `zIndex`; see the hit-testing
  section. The blocker is the correct mechanism.
- Any change to the OHOS pill geometry (`kDockHeight` / `kDockBottomMargin` /
  `kDockSideMargin` in the `.ets`) must be mirrored in the
  `slockOhosHdsDockBlocker*` helpers and their test
  (`SlockAppShellTest.ohosHdsDockBlockerMatchesFloatingPillRect`).

## Validation

Shared logic is covered by `SlockAppShellTest` (host heights, content insets,
z-index, dock-blocker rect). Native rendering (HDS material, UITabBar Liquid
Glass, and real-device tap-through) **cannot** be asserted by unit tests or the
KMP CI — it requires an OHOS device/emulator screenshot and an iOS
simulator/device run. See `docs/ohos-hds-tabbar-material-plan.md` for the OHOS
acceptance bar.
