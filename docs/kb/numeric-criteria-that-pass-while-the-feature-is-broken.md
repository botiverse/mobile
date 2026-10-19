# A green number is not a working feature: choosing criteria an implementation cannot satisfy by accident

**Area:** verification / criteria
**Owner:** CC-Cata
**Verified at:** 2026-10-18
**Status:** current

Three times in one task (an HTML preview "desktop mode" toggle on Android) a numeric criterion I had
chosen reported success while the feature was unusable. The work was green, CI was green, and the
screenshots looked plausible. This article is about the shape of that mistake, not about that feature.

## The failures

**1. The criterion measured intent, not outcome.**
Criterion: *desktop mode sets the viewport width to 1280, so assert `window.innerWidth === 1280`.*
It passed — after the code injected `width=1280, initial-scale=1.0`, which draws a 1280-wide layout
at 100%. On a 720px phone the user saw about 480px of the page and no way to tell it was a crop.
Every number was correct. The feature was broken on the **default** case (pages shipping their own
viewport meta).

Fix: stop assuming the scale — inject `width=1280` and no `initial-scale`, and drive the fit
explicitly with `setInitialScale` from the real view width. Dropping `initial-scale` alone is not
enough: overview mode will not fit a page that declares its own width. See
`webview-document-start-injection-pitfalls.md` §5 for the mechanism.

**2. The criterion measured a proxy the implementation satisfies cheaply.**
Next criterion: *the heading is fully visible, so the page fits.* It was, and it did not fit — the
body text rendered at exactly the same pixel height as adaptive mode (32px vs 32px), i.e. **nothing
had been scaled**. The heading only escaped clipping because the crop landed on whitespace.

Fix: measure the thing the user reacts to — rendered glyph height against the adaptive baseline, or
`visualViewport.scale`, or a marker element pinned to the far edge of the layout.

**3. The evidence was a positive observation where only a negative one mattered.**
A crash hypothesis was rejected because "the screen rendered something, so nothing threw". A
rendered frame proves composition completed; it says nothing about rendering the **correct thing**.
The real defect was the list being laid out in the wrong direction — which renders perfectly.

## The pattern

All three criteria were **satisfiable by an implementation that failed the user**. They described
what the code was supposed to *do* (set a width, show a heading, not crash) rather than what the user
must *experience* (the page is readable without pinching; the content is where it belongs).

## Rules that would have caught all three

1. **State the user-visible outcome, then find a measurement that a wrong implementation cannot
   satisfy.** "The user can read the page without zooming" → rendered text size, or a right-edge
   marker that must be on screen. Not "a viewport value was set".
2. **Prefer a measurement with a baseline you can compare against.** Absolute numbers hide scaling
   bugs; *the same measurement before and after* does not. 32px means nothing alone; 32px where the
   control was 12px is the bug.
3. **A marker at the far edge is cheap and hard to fake.** Any layout that claims to fit while
   cropping will lose it.
4. **Don't grade a numeric success you have not also looked at.** In failure 1 the screenshot was
   taken and the layout was visually a crop — it was read as "green, therefore fine".

## Corollary for reviews

When a report says "criterion X passed", ask what a wrong implementation would have reported for X.
If the answer is "the same", the criterion proved nothing — regardless of how many checks are green.
