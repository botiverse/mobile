# Bottom Tab Bar Architecture (Home / Tasks / Members / Settings)

This document describes how the app-shell bottom tab bar is rendered across
Android and iOS, the shared contract that keeps them in sync, and the hit-testing/tap-through design. It is the reference for
anyone touching `RaftAppBottomTabBarHost` or the per-platform native tab bar
views.

HarmonyOS (OHOS) was frozen at 1.14.x and its host was removed from `main`
(see `release/1.14.x`). Shared code may still carry the `OhosHdsImmersive`
renderer value and `slockOhosHdsDockBlocker*` helpers; no host on `main` selects
them.

## The one thing to understand first

There is exactly **one** app shell (`compose_slock_app_shell`, a long-lived
Kuikly root). Home / Tasks / Members / Settings are retained tabs *inside* that
shell — switching tabs does not launch a new native page. The bottom tab bar
is a single shared Composable host (`RaftAppBottomTabBarHost`) that, depending
on the platform, either draws the bar itself in Compose or delegates to a
**native tab bar view** and only feeds it data.

The tab *content* and the tab *bar* are two different render-granularity
concepts and must not be conflated:

- **Tab content** (Home/Tasks/Members/…): Compose, sometimes wrapped in a
  native child-page container (`SlockKuiklyPageView` / `glassStyle=liquid` on
  iOS). This is the "native view embeds a Kuikly page" demo granularity.
- **Tab bar itself**: on iOS a *native tab bar render view* — the native
  view **is** the bar; it is not a child page. Shared code only hands it
  `keys/labels/icons/selectedKey` and gets back a selection callback.

## Renderer selection

`RaftTabBar.kt` reads the page-data param `slockNativeTabBarRenderer` (legacy
alias `nativeTabBarRenderer`) and maps it to `RaftAppTabBarRenderer`. A
host-registered native tab bar bridge (see below) takes precedence over the
param and selects `NativeHost`. Otherwise an explicit page-data value always
wins. When the value is absent, the platform
capability supplies the default: iOS returns `iosLiquidGlass`, while Android
returns no default and keep the shared fallback unless their host
explicitly opts into a native renderer.

| param value                         | renderer            | who renders the bar                    |
| ----------------------------------- | ------------------- | -------------------------------------- |
| `ohosHdsImmersive` / `hdsImmersive` | `OhosHdsImmersive`  | legacy OHOS `HdsTabs` (no host on `main`) |
| `iosLiquidGlass` / `iosNativeFloating` | `IosLiquidGlass` | iOS native `UITabBar` (Liquid Glass)   |
| `androidFloating` / `androidNativeFloating` | `AndroidFloating` | shared Kuikly floating dock |
| anything else / absent              | `SharedCompose`     | shared Compose `RaftBottomTabBar`     |

Each host decides its own param:

- **iOS** (`compose/iosApp/iosApp/ContentView.swift`): sets `iosLiquidGlass` on
  the SwiftUI root. The iOS shared platform capability also defaults missing
  renderer values to `iosLiquidGlass`, because post-auth/server-selection
  Router launches create a new app-shell page without inheriting root
  pageData.
- **Android**: uses `sharedCompose` by default. The old floating aliases keep
  their floating presentation through a shared Kuikly renderer.

The `SharedCompose` fallback guarantees a working bar everywhere, so the native
paths can assume their platform capability is present.

## Shared → native contract (iOS system tab bar)

On iOS every app-shell page (`compose_slock_app_shell`) is hosted by
`RaftIosTabBarController` (`compose/iosApp/iosApp/RaftIosTabBarController.swift`):
the shell's Compose controller fills the page and a real `UITabBarController`
is layered above it. The system controller owns the `UITabBar` (Liquid Glass
on iOS 26+, the standard bar on earlier versions); its tab children are empty
placeholders, and an overlay view passes every touch outside the bar through
to Compose. The shell stays a single Compose page with retained tabs.

The contract lives in `RaftNativeTabBar.kt` (commonMain, exported to Swift):

- `RaftPageControllerFactory` creates a `RaftNativeTabBarBridge`, registers it
  in `RaftNativeTabBarRegistry` under a fresh host id and passes the id in
  pageData as `slockNativeTabBarHostId` (identity only; other pages have it
  stripped).
- When `RaftAppBottomTabBarHost` finds a bridge for its page, the renderer is
  `NativeHost`: Compose draws nothing and `RaftNativeTabBarPublisher`
  publishes `RaftNativeTabBarState` (items in `appShellBottomTabs` order with
  key, localized title, SF Symbol names, badge count and capped `badgeValue`,
  enabled; `selectedKey`; `visible`). Leaving composition (the shell hides
  its bar, `showBottomTabs == false`) publishes `visible = false`.
- Swift renders the state (`tabBarItem.title/image/badgeValue/isEnabled`,
  `selectedIndex`, `setTabBarHidden`) and reports taps through
  `bridge.selectTab(key)`, which runs the shell's normal tab handler (switch,
  re-tap scroll-to-top). UIKit never switches tabs on its own; programmatic
  shell navigation (deep links, notification opens) updates the bar through
  the published state.
- Swift reports the bar's bottom band (bar top → page bottom, including the
  home indicator) through `bridge.updateBottomInset(points)`; shared uses it
  as the content inset (`raftNativeHostTabBarContentInset`) so content scrolls
  under the bar without a second safe-area padding.

A tab key added to the shell's ordered tab keys appears natively with its
label, icon (`raftNativeTabBarSymbol`) and badge (the payload's `badgeCount`;
Activity carries the unread count from `appShellBottomTabs(activityBadgeCount)`).
A compact `UITabBarController` shows at most 5 tabs; extra tabs are dropped and
logged.

### Overlays over the native bar

The system bar sits above the whole Compose page and does not move with it.

- Compose `Dialog`s (`Modal`, `RaftUnifiedDialog`, ...) need nothing: CMP
  attaches its dialog/popup layers next to the window's root view, above this
  controller, so their scrim covers the bar.
- Overlays the page draws itself stay under the bar, the same as under the
  Compose bar on Android (menus, popovers, the Tasks overlay).
- A full-height panel the page draws itself — the server drawer, which slides
  the shell and its Compose bar aside — hides the bar while it shows:
  `RaftNativeTabBarCover(active)` marks the bridge, which then publishes
  `visible = false` until every cover is released. The content inset keeps the
  last reported band, so content does not jump.

## Host geometry & content inset

`RaftAppBottomTabBarHost` places the bar as a bottom-aligned overlay
(`zIndex = RaftAppBottomTabBarHostZIndex = 1000`, shared with the floating
overlay host so menus and the bar agree on stacking).

- Native floating hosts (iOS) are overlays: content keeps filling the
  screen behind them; `raftAppBottomTabBarContentInset` only reserves
  `bottomSafeArea + Lg` so the last scroll rows clear the dock.
- The native floating host box is `92dp + bottomSafeArea`.
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
- The former OHOS host needed a shared Compose tap blocker under its
  transparent-hit-mode native dock; that history lives on `release/1.14.x`.

## History / gotchas

- OHOS-specific history (HDS dock restoration, ArkTS renames, dock-blocker
  geometry mirroring) is preserved on `release/1.14.x`.

## Validation

Shared logic is covered by `RaftAppShellTest` (host heights, content insets,
z-index, dock-blocker rect). Native rendering (HDS material, UITabBar Liquid
Glass, and real-device tap-through) **cannot** be asserted by unit tests or the
KMP CI — it requires an iOS simulator/device run.
