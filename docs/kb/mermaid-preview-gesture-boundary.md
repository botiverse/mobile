# Mermaid preview gesture ownership: keep diagram panning inside the card

Owner: Nathan · Verified at: 2026-10-18 · Status: current

The inline Mermaid preview uses a horizontal scroller for diagram panning after zoom. The outer message surface keeps ownership of navigation outside that scroller. This contract is implemented by mobile PR [#2880](https://github.com/botiverse/mobile/pull/2880), whose squash commit on `main` is `85d77b2f4a5c3c701bcf5c60262c8eda4152ae59`.

## The boundary

There are two independent axes:

- The message list keeps its vertical scroll behavior.
- The diagram card owns horizontal panning for a gesture that starts in the diagram scroller. The diagram is centered in a horizontal `LazyRow`; a small diagram is not enlarged, and toolbar zoom is relative to the fitted scale.

The card sets both `nestedScroll(SELF_ONLY)` and the renderer's `scrollWithParent = 0`. They solve different interception points: nested scroll controls the Compose connection, while the Kuikly renderer separately consults `scrollWithParent` for same-axis parent interception. Setting only one leaves a path where the outer tab can still consume a horizontal drag.

The ownership boundary is local to the diagram scroller. A drag from a toolbar or from the surrounding message surface remains available to the outer navigation. Fullscreen behavior is a separate surface and is not changed by this contract.

## How to verify a change

1. Use a diagram wider than the card and record its fitted initial scale. A narrow diagram must not be upscaled.
2. Start a horizontal drag inside the diagram at the fitted scale and after zoom. Confirm that the diagram pans while the outer tab index stays unchanged.
3. Start the same drag from the toolbar and from outside the card. Confirm that those gestures remain with the outer surface.
4. Drag vertically from the message list and confirm that the list still scrolls. Check resize and invalid viewport inputs as well as zoom changes.
5. Run the shared fit/resize/zoom tests and the Android renderer edge-ownership tests. Run the iOS compilation checks before treating the change as cross-platform ready.

The source-level and boundary tests in PR #2880 cover these contracts, including diagram-origin versus outside/disabled gestures and vertical interception. They are not a substitute for an Android/iOS device or released-package acceptance run; report those separately when they have not been executed.

## Failure classification

- **Diagram drag switches the outer tab:** inspect both `nestedScroll(SELF_ONLY)` and `scrollWithParent = 0`, then verify that the pointer started inside the diagram scroller.
- **Wide diagram is clipped before any drag:** inspect the fitted base scale and card width before changing gesture handling.
- **Toolbar or outside-card drag stops navigating:** the ownership region is too broad; keep the diagram scroller as the only horizontal child surface.
- **Vertical list scrolling regresses:** check that the diagram's horizontal policy did not replace the parent list's vertical axis.
