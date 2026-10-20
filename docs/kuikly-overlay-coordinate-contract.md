# Kuikly Overlay Coordinate Contract

This document records the coordinate contract for Slock Kuikly Compose floating
overlays, dropdowns, context menus, and anchored popovers.

## Rule

Overlay geometry is always in pixels, but feature code must not exchange those
pixels as a raw `Offset`.

- `onGloballyPositioned { coords -> coords.positionInRoot() }` returns root
  coordinates in pixels.
- `coords.size.width` and `coords.size.height` are pixels.
- `RaftFloatingOverlayLayout.origin` and `RaftFloatingOverlayLayout.size` are
  pixels.
- `RaftAnchoredPopover(anchor = ...)` accepts a typed `RaftPopoverAnchor`.
  Its opaque root pixels are unwrapped only by the shared overlay layout.
- `RaftFloatingOverlayLayout.positionContent(...)` returns a pixel offset,
  consumed by `Modifier.offset { IntOffset(...) }`.

Do not convert anchors through `LocalDensity`, `toDp()`, `.dp`, or
`toSlockDpOffset()`. Mixing a pixel overlay layout with a dp anchor makes the
popover land in the wrong place.

Use dp only for visual dimensions and spacing passed into Compose layout
modifiers, such as `width(220.dp)`, `padding(8.dp)`, or API parameters that are
explicitly named `*Dp`.

## Correct Live Anchor Pattern

```kotlin
val anchorRef = remember(identity) { RaftLiveRootAnchorRef(identity) }

Modifier.raftCaptureLiveRootAnchor(anchorRef)

// Resolve at the click/long-press that opens the popover.
anchorRef.resolve(RaftPopoverLocalPoint.BottomEnd)?.let(onOpenMenu)
```

For a programmatic open, retain a typed `RaftPopoverAnchorResolver` and call
`resolve()` only when opening. The resolver keeps current coordinates, size,
translation, and scale; detached coordinates fail closed. Every resolver owns a
stable identity so a Lazy item cannot reuse another item's anchor.

Available local-point policies are `Exact`, `BottomStart`, `BottomEnd`, and
`BottomAtX`. Do not add feature callbacks such as `(Offset) -> Unit` for popup
anchors, store `positionInRoot()` snapshots, or construct root offsets in
business screens.

## Known Regression Class

Task #258 found this exact bug in `RaftAnchoredToolbarChip`: the trigger anchor
converted `positionInRoot()` and the trigger height to dp before opening the
Home Search / Tasks filter dropdowns. Android then aligned the dropdown around
the trigger's top instead of opening from the trigger bottom.

The original fix resolved the bottom edge in pixels. The hardened contract now
keeps that calculation behind the typed live resolver:

```kotlin
anchorRef.resolve(RaftPopoverLocalPoint.BottomStart)
```

## Relevant Code

- `compose/shared/src/commonMain/kotlin/build/raft/app/RaftFloatingOverlay.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/RaftLiveRootAnchorRef.kt`
- `compose/shared/src/commonMain/kotlin/build/raft/app/components/RaftBrutalComponents.kt`
