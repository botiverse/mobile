# iOS content-area swipe-back regression

## Scope

The fix applies to pages pushed onto the native navigation stack. Bottom tabs
remain tabs and do not gain a back gesture. Opening Activity from within Home
is a pushed-page path and is covered by this investigation.

Baseline: mobile `d216f6559`, including PR #3004. The simulator checkout uses
OpenKuiklyIOSRender `2.28.0-raft.9-2.1.21`. This is separate from TestFlight
1.13.1 build 11301005, which does not include #3004.

## Observed failure and causal check

On the baseline simulator app, a content-area rightward drag did not return
from pushed Activity or a channel opened from Activity; an edge drag returned
from the channel to Activity.

LLDB inspection on Activity established that:

- The navigation stack contained two controllers and full-screen pop was enabled.
- The center touch was admitted, and the production `gestureRecognizerShouldBegin:`
  returned `YES`.
- The touched vertical `KRScrollView` had a 402 × 680 frame, content size
  402 × 238, and offset (0, 0).
- Its outer horizontal pager had width 402, content width 1206, and offset 0.
  It could not consume a rightward drag at that boundary.

Making only the inner vertical pan wait for pop did not fix the gesture.
Making the outer pager pan wait for pop as well made a center drag at
(100, 600), displacement (+250, 0), duration 350 ms return Activity to Home.
This was a debugger-controlled causal experiment, not acceptance of a built fix.

## Change

The native pan belonging to each descendant `UIScrollView` now waits for an
admitted back gesture to make its direction and horizontal-scroll decision.
This ordering previously applied only to the physical edge strip. Custom pan
recognizers, including additional recognizers attached to a scroll view, keep
their existing arbitration. Pop still fails when the horizontal scroller can
consume the drag, allowing that scroller to proceed; text inputs remain
excluded at touch admission.

Direction selection now treats velocity on either axis as meaningful. The
previous horizontal-axis-only check could interpret current vertical motion
using an earlier rightward translation. The iOS TabPager full-width edge guard
is removed because native arbitration owns this decision; that guard otherwise
consumes horizontal gestures before normal paging can handle them.

## Simulator carrier

PR #3014 product source: `96f724e1bf72c65f82eee61e802c1b9e134aa04e`.
The normal arm64 Debug app was built with Xcode 26.1 and exercised on the
iPhone 17 Pro simulator running iOS 26.1, device
`B1AF4DE8-DCC6-4EF1-9E1F-2DC7125AC2D9`. Its bundle identity is
`build.raft.app`, local version `1.0 (1)`; this is not a release build number.

Evidence is retained under
`/Users/artin/.slock/agents/691630e5-64cd-41c3-888b-e7a7b4a3fe13/evidence/task193-20261014/`:

- App: `96f724e1b-simulator.app.zip`, SHA-256
  `f83a2abddf131c1ce8a2a1f0364e319ec6c43225a71b59c19bf5026903d92e79`.
- Runtime recording: `96f724e1b-gestures.mp4`.
- Build and native-test logs: `build-96f724.log`, `tests-96f724.log`.

The build used local CocoaPods-generated metadata changes in addition to that
source commit: `Podfile.lock` regenerated the OpenKuiklyIOSRender and shared
podspec checksums without changing their selected versions; `project.pbxproj`
regenerated the test target's Pods framework/xcconfig references and removed
its obsolete duplicate resource/embed phases, with ordering-only changes to
other entries. The complete pre-cleanup delta and metadata snapshots are in
`generated-config-backup/`. Therefore the carrier is identified by the source
commit **plus this generated metadata delta**, not a claim of a clean checkout.

## Verification and limits

`SlockInteractivePopGestureTests` drives the delegate installed by the real
navigation push hook. Only touch locations and motion vectors are supplied by
test doubles. Seven tests cover both nested ancestor pans, edge-only pages,
root/disabled pages, text-input admission reset, inset-aware horizontal scroll
consumption, non-back directions, and custom/unrelated recognizers.

The final native run on the above source passed all seven tests with zero
failures, including the restriction to scroll views' own pans. The direction
regression vector—vertical velocity (0, 300) with earlier translation (30, 0)—is
covered by both the native test and the focused C-policy script. Native delegate
tests establish decisions and failure ordering, not UIKit transition completion.

The same normal simulator carrier provided these runtime results:

| Path | Observed result |
| --- | --- |
| Pushed Activity | All → swipe left → Unread → swipe right → All → content-area swipe right → Home. |
| Channel | Vertical scrolling exposes older messages; content-area swipe right returns to Home. |
| Settings → Notifications | Content-area and edge right swipes both return to the Settings root. |
| Message text selection | Long press → select text → drag a selection handle expands `sender2` to `sender2-02`; the selection modal remains open. |

### Image gesture result: bounded passes, slow single-finger failure

The existing `compose_file_preview_editor_visual&visualFixture=action_bar`
route was pushed from Router and exercised the production zoomable image
component. Its SVG data URI did not render, so a separate local variant changed
only that fixture URI to the already packaged `assets://image_adapter/sample.png`.
This uncommitted transport patch is excluded from PR #3014; no product fixture
change is included. The variant is source `96f724e1b` **plus** URI patch SHA-256
`7a0d616918e9cb80fe158cd1519183be013902e79a292c5f62e1100f88dee97a`, with app ZIP
SHA-256 `99e8d77d3376229158d9411fd3456c73897813a392ae1b33fdab9a895932f34f`.
Full identity and transport details are in
`96f724-image-fixture-assets-readme.md` in the evidence directory; the runtime
recording is `jony-task193-image-assets.mp4` in that directory.

| Image case | Observed result |
| --- | --- |
| Pinch ×1.5 | PASS: visible square expands from x=97–304 to x=46–355; page remains. |
| Two-finger horizontal pan +120 px | PASS: image boundary moves to x=145; page remains. |
| Single-finger left/right, 120 px in 100 ms | PASS: image visibly moves and page remains. |
| Single-finger left/right, 120 px in 650 ms; slow vertical pan | FAIL: no visible image movement. |
| Restore scale to 1 with pinch ×0.5, then center right drag +240 px / 350 ms | PASS: returns to Router. |

LLDB confirmed stack depth 2 and full-screen pop enabled. Temporarily disabling
FD pop did not restore slow single-finger movement, while a fast left drag still
moved the image; FD was re-enabled before the final return check. This isolates
the slow-pan failure from FD pop in this run. The image handler in
`BasicWidget.kt` is unchanged between baseline `d216f6559` and `96f724e1b`
(last modifying commit `d74cbe961`); it compares each event's displacement with
touch slop without accumulating movement, a separate source-level defect tracked
in Raft task #198 (#iOS适配:70d3e814).
**These bounded passes do not constitute full image-gesture PASS.** Multi-image
gallery swiping was not covered by this single-image fixture.

The current fenced-code renderer does not enable horizontal scrolling; this
must be recorded as not applicable, not a passing horizontal-scroll test.

No physical-device or TestFlight behavior conclusion is made by this report.
