# Kuikly Overlay Coordinate Contract

This document records the coordinate contract for Slock Kuikly Compose floating
overlays, dropdowns, context menus, and anchored popovers.

## Rule

Overlay geometry is always in pixels, but feature code must not exchange those
pixels as a raw `Offset`.

- `onGloballyPositioned { coords -> coords.positionInRoot() }` returns root
  coordinates in pixels.
- `coords.size.width` and `coords.size.height` are pixels.
- `SlockFloatingOverlayLayout.origin` and `SlockFloatingOverlayLayout.size` are
  pixels.
- `SlockAnchoredPopover(anchor = ...)` accepts a typed `SlockPopoverAnchor`.
  Its opaque root pixels are unwrapped only by the shared overlay layout.
- `SlockFloatingOverlayLayout.positionContent(...)` returns a pixel offset,
  consumed by `Modifier.offset { IntOffset(...) }`.

Do not convert anchors through `LocalDensity`, `toDp()`, `.dp`, or
`toSlockDpOffset()`. Mixing a pixel overlay layout with a dp anchor makes the
popover land in the wrong place.

Use dp only for visual dimensions and spacing passed into Compose layout
modifiers, such as `width(220.dp)`, `padding(8.dp)`, or API parameters that are
explicitly named `*Dp`.

## Correct Live Anchor Pattern

```kotlin
val anchorRef = remember(identity) { SlockLiveRootAnchorRef(identity) }

Modifier.slockCaptureLiveRootAnchor(anchorRef)

// Resolve at the click/long-press that opens the popover.
anchorRef.resolve(SlockPopoverLocalPoint.BottomEnd)?.let(onOpenMenu)
```

For a programmatic open, retain a typed `SlockPopoverAnchorResolver` and call
`resolve()` only when opening. The resolver keeps current coordinates, size,
translation, and scale; detached coordinates fail closed. Every resolver owns a
stable identity so a Lazy item cannot reuse another item's anchor.

Available local-point policies are `Exact`, `BottomStart`, `BottomEnd`, and
`BottomAtX`. Do not add feature callbacks such as `(Offset) -> Unit` for popup
anchors, store `positionInRoot()` snapshots, or construct root offsets in
business screens.

## Known Regression Class

Task #258 found this exact bug in `SlockAnchoredToolbarChip`: the trigger anchor
converted `positionInRoot()` and the trigger height to dp before opening the
Home Search / Tasks filter dropdowns. Android then aligned the dropdown around
the trigger's top instead of opening from the trigger bottom.

The original fix resolved the bottom edge in pixels. The hardened contract now
keeps that calculation behind the typed live resolver:

```kotlin
anchorRef.resolve(SlockPopoverLocalPoint.BottomStart)
```

## Relevant Code

- `compose/shared/src/commonMain/kotlin/build/raft/app/SlockFloatingOverlay.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/SlockBrutalComponents.kt`
