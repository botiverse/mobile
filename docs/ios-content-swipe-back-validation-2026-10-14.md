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

## Verification and limits

`SlockInteractivePopGestureTests` drives the delegate installed by the real
navigation push hook. Only touch locations and motion vectors are supplied by
test doubles. Seven tests cover both nested ancestor pans, edge-only pages,
root/disabled pages, text-input admission reset, inset-aware horizontal scroll
consumption, non-back directions, and custom/unrelated recognizers.

The first native run passed six tests and exposed one failure: vertical velocity
(0, 300) with earlier translation (30, 0) incorrectly admitted pop. That policy
has been corrected, with both a native test vector and a focused C-policy
vector. The next native run passed all seven tests. The later restriction to
scroll views' own pans adds custom-recognizer assertions and requires another
native run before assigning that result to the final patch.

The rebuilt shared/native app has since verified Activity paging left to Unread,
right to All, then right again back to Home, and vertical channel scrolling.
The final carrier still needs image gesture, text-selection, settings and edge
checks. **Image gestures are not yet verified and must not be reported as
PASS.** Image zoom/pan uses Compose pointer input rather than a native scroll
offset, so the native horizontal-scroll check alone cannot prove its behavior.
The current fenced-code renderer does not enable horizontal scrolling; this
must be recorded as not applicable, not a passing horizontal-scroll test.
Native delegate tests do not prove UIKit transition completion.

No physical-device or TestFlight behavior conclusion is made by this report.
