# OHOS HDS Tabbar Material Plan

## Problem

The HarmonyOS bottom tabbar is expected to use the official HDS immersive
system material. Real-device feedback showed the earlier implementation was
wrong: it tried to simulate the material with `backgroundBlurStyle` and a
white/tint backing layer, but HDS already exposes the correct
`barFloatingStyle({ systemMaterialEffect })` API. The task must not be accepted
by compile/HAP alone; it needs an OHOS screenshot proving the official system
material is active.

## Current Implementation

- Shared entry point:
  `compose/shared/src/commonMain/kotlin/build/raft/app/SlockTabBar.kt`
  selects the OHOS native renderer when page data contains
  `slockNativeTabBarRenderer=ohosHdsImmersive`.
- OHOS host:
  `compose/ohosApp/entry/src/main/ets/pages/Index.ets` enables that renderer
  when API level is at least 20 and
  `SystemCapability.UIDesign.HDSComponent.Core` is available.
- OHOS native view before the latest correction:
  `compose/ohosApp/entry/src/main/ets/kuikly/components/SlockOhosHdsTabBarView.ets`
  uses `@kit.UIDesignKit` `HdsTabs` with `barOverlap(true)`,
  `barBackgroundColor(...)`, `barBackgroundBlurStyle(BlurStyle.Thin)`, and
  custom tab items.
- Latest hardening commit:
  `66b1257 fix: harden ohos tabbar material backing` adds an explicit backing
  layer behind `HdsTabs`: near-opaque white tint, `BlurStyle.Thin`, and a top
  separator.
- Corrected target implementation:
  `HdsTabs.barOverlap(true)` plus
  `barFloatingStyle({ barSideMargin, barBottomMargin, thermoCtrl,
  systemMaterialEffect })`. The material is owned by HDS Tabs, not by a custom
  white/blur backing layer.

## Official References

- ArkUI Tabs component, including tab bar position/overlap APIs:
  <https://developer.huawei.com/consumer/en/doc/harmonyos-references/ts-container-tabs>
- HarmonyOS 6.1 / API 23 HDS material examples shared by @artin show:
  `HdsTabs.barFloatingStyle({ systemMaterialEffect: { materialType,
  materialLevel } })`, with `hdsMaterial.MaterialType.ADAPTIVE` or
  `IMMERSIVE` and `hdsMaterial.MaterialLevel.ADAPTIVE` or `SMOOTH`.
- Local SDK state on this machine: DevEco Studio 6.0.2 / HarmonyOS SDK
  6.0.2(api 22). Its local
  `/Applications/DevEco-Studio.app/Contents/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets`
  currently exposes `HdsTabs.barOverlap`, `barBackgroundColor`,
  `barBackgroundBlurStyle`, and `barBackgroundEffect`, but not
  `barFloatingStyle`; it also lacks local `@hms.hds.hdsMaterial`. Building the
  official API path therefore requires the HarmonyOS 6.1/API 23 SDK.

## Candidate Implementations

### Option A: Official HDS Floating Material

Use HDS Tabs as the visual and interaction owner:

- `barOverlap(true)`
- `barPosition(BarPosition.End)`
- `barFloatingStyle({ barSideMargin, barBottomMargin, thermoCtrl,
  systemMaterialEffect })`
- `systemMaterialEffect.materialType = hdsMaterial.MaterialType.ADAPTIVE`
- `systemMaterialEffect.materialLevel = hdsMaterial.MaterialLevel.ADAPTIVE`
- no separate white backing layer

This is the corrected primary path. The previous manual backing approach should
not be treated as a successful implementation of the immersive material.

### Option B: Capability Fallback for Older SDKs

Only for environments that do not expose HDS floating material APIs:

- keep the shared/KMP tabbar functional
- use an explicit readable background/tint so the app is usable
- mark visual status as degraded, not as immersive material pass

This is a compatibility fallback only. It must not be used to close #77 on a
device that supports `systemMaterialEffect`.

### Option C: Screenshot Harness

Use #86 to capture the OHOS screen and prove whether the HDS material is active.
The screenshot must show a floating system material tabbar over content, not a
fixed white container.

## Recommended Direction

Use Option A as the default implementation:

1. Upgrade the build environment/project SDK to HarmonyOS 6.1/API 23 so local
   UIDesignKit exposes `barFloatingStyle` and `hdsMaterial`.
2. Implement `HdsTabs.barFloatingStyle({ systemMaterialEffect })` directly.
3. Keep manual tint/blur only as a declared fallback for older SDKs/devices.
4. Gate acceptance on an OHOS screenshot from #86.

## Acceptance

The task is not accepted until an OHOS target is available and the report
includes:

- target type: emulator or real device
- output of `hdc list targets`
- bundle/module launched: `build.raft.app` / `entry`
- install and launch commands
- screenshot command
- pulled PNG path
- bottom tabbar screenshot showing official HDS system material owned by
  `HdsTabs.barFloatingStyle({ systemMaterialEffect })`, not a hand-drawn white
  backing layer

If the screenshot still fails, classify the cause as one of:

- build environment still on SDK 6.0.2/API 22, so official API was not compiled
- device/runtime does not support the requested `MaterialType`
- `barFloatingStyle` not applied because the HDS Tabs host/container clips it
- tabbar is not actually overlapping content (`barOverlap`/content padding issue)
- screenshot path captured a fallback build instead of the official material
  build
