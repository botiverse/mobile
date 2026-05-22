# OHOS HDS Tabbar Material Plan

## Problem

The HarmonyOS bottom tabbar is expected to use an immersive HDS light material,
but real-device feedback still reports a pure white/transparent background. The
current implementation must not be accepted by compile/HAP alone; it needs an
OHOS screenshot proving the tabbar owns a visible material/tint layer.

## Current Implementation

- Shared entry point:
  `compose/shared/src/commonMain/kotlin/ai/slock/compose/SlockTabBar.kt`
  selects the OHOS native renderer when page data contains
  `slockNativeTabBarRenderer=ohosHdsImmersive`.
- OHOS host:
  `compose/ohosApp/entry/src/main/ets/pages/Index.ets` enables that renderer
  when API level is at least 20 and
  `SystemCapability.UIDesign.HDSComponent.Core` is available.
- OHOS native view:
  `compose/ohosApp/entry/src/main/ets/kuikly/components/SlockOhosHdsTabBarView.ets`
  uses `@kit.UIDesignKit` `HdsTabs` with `barOverlap(true)`,
  `barBackgroundColor(...)`, `barBackgroundBlurStyle(BlurStyle.Thin)`, and
  custom tab items.
- Latest hardening commit:
  `66b1257 fix: harden ohos tabbar material backing` adds an explicit backing
  layer behind `HdsTabs`: near-opaque white tint, `BlurStyle.Thin`, and a top
  separator.

## Official References

- ArkUI Tabs component, including tab bar background and overlap APIs:
  <https://developer.huawei.com/consumer/en/doc/harmonyos-references/ts-container-tabs>
- ArkUI `backgroundBlurStyle` universal attribute:
  <https://developer.huawei.com/consumer/en/doc/harmonyos-references/ts-universal-attributes-background-blur-style>
- ArkUI `backgroundEffect` universal attribute for explicit blur/effect
  options:
  <https://developer.huawei.com/consumer/en/doc/harmonyos-references/ts-universal-attributes-background-effect>
- OpenHarmony EffectComponent blur example:
  <https://gitee.com/openharmony/docs/blob/9c5954d6491ee755e5f2c5d4ffbc707056ef0141/en/application-dev/reference/apis-arkui/arkui-ts/ts-container-effectcomponent-sys.md>

## Candidate Implementations

### Option A: HDS Tabs Owns the Material

Keep `HdsTabs` as the visual and interaction owner:

- `barOverlap(true)`
- `barBackgroundColor(...)`
- `barBackgroundBlurStyle(BlurStyle.Thin)`
- `barHeight(...)`
- no separate backing layer

Risk: real-device feedback suggests this path may not paint a visibly distinct
material layer in our current host/container composition.

### Option B: Explicit ArkUI Material Backing + HDS Tabs

Use a `Stack`:

- bottom layer: full tabbar bounds with tint + blur + separator
- top layer: `HdsTabs` with transparent/near-transparent internal background
- shared/KMP still owns selected tab and route state

This is what commit `66b1257` starts doing. It is safer because the material
layer is not dependent on HDS tab internals. It still needs device screenshot
verification.

### Option C: EffectComponent/BackgroundEffect Fallback

If `backgroundBlurStyle` on a regular component is not visually stable, wrap the
bottom material in an `EffectComponent` or use `backgroundEffect(...)` to
explicitly control blur radius, saturation, brightness, and color.

This should be the fallback when HDS blur/tint is supported by syscap but does
not produce the target visual on the current device/SDK.

## Recommended Direction

Use Option B as the default implementation, with Option C as fallback:

1. Keep `HdsTabs` for HDS interaction semantics and future native behavior.
2. Render our own tabbar material backing layer behind it.
3. Keep a non-zero tint/opacity so the tabbar remains readable even when blur is
   unavailable or visually weak.
4. Gate the native renderer behind API/syscap; unsupported devices keep the
   shared Compose tabbar.

## Acceptance

The task is not accepted until an OHOS target is available and the report
includes:

- target type: emulator or real device
- output of `hdc list targets`
- bundle/module launched: `ai.slock.compose` / `entry`
- install and launch commands
- screenshot command
- pulled PNG path
- bottom tabbar screenshot showing visible material/tint/background owned by the
  tabbar, not just the page white background

If the screenshot still fails, classify the cause as one of:

- backing layer not drawn
- HDS `barBackgroundBlurStyle` / `barBackgroundColor` ineffective
- generic `backgroundBlurStyle` ineffective
- outer Kuikly/OHOS container covering or clipping the tabbar layer
- page background visually indistinguishable from tabbar tint
