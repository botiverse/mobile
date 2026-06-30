# Kuikly Overlay Coordinate Contract

This document records the coordinate contract for Slock Kuikly Compose floating
overlays, dropdowns, context menus, and anchored popovers.

## Rule

Anchor and overlay geometry for `SlockAnchoredPopover` is always in pixels.

- `onGloballyPositioned { coords -> coords.positionInRoot() }` returns root
  coordinates in pixels.
- `coords.size.width` and `coords.size.height` are pixels.
- `SlockFloatingOverlayLayout.origin` and `SlockFloatingOverlayLayout.size` are
  pixels.
- `SlockAnchoredPopover(anchor = ...)` expects the anchor in that same pixel
  coordinate system.
- `SlockFloatingOverlayLayout.positionContent(...)` returns a pixel offset,
  consumed by `Modifier.offset { IntOffset(...) }`.

Do not convert anchors through `LocalDensity`, `toDp()`, `.dp`, or
`toSlockDpOffset()`. Mixing a pixel overlay layout with a dp anchor makes the
popover land in the wrong place.

Use dp only for visual dimensions and spacing passed into Compose layout
modifiers, such as `width(220.dp)`, `padding(8.dp)`, or API parameters that are
explicitly named `*Dp`.

## Correct Bottom-Edge Anchor Pattern

```kotlin
modifier.onGloballyPositioned { coords ->
    val root = coords.positionInRoot()
    val anchor = Offset(root.x, root.y + coords.size.height.toFloat())
}
```

This places a dropdown relative to the trigger's bottom edge. The popover may
then apply its own pixel `verticalOffset`.

## Known Regression Class

Task #258 found this exact bug in `SlockAnchoredToolbarChip`: the trigger anchor
converted `positionInRoot()` and the trigger height to dp before opening the
Home Search / Tasks filter dropdowns. Android then aligned the dropdown around
the trigger's top instead of opening from the trigger bottom.

The fix was to pass the bottom-edge anchor in pixels:

```kotlin
val root = coords.positionInRoot()
val next = Offset(root.x, root.y + coords.size.height.toFloat())
```

## Relevant Code

- `compose/shared/src/commonMain/kotlin/build/raft/app/SlockFloatingOverlay.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockBrutalComponents.kt`
