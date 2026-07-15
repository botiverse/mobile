# Programmatic Scroll Ownership Contract

Programmatic scrolling is permitted only when the caller can name the viewport intent it owns and prove that direct user movement has not replaced that intent.

## Invariant

Every automatic `scrollToItem`, `animateScrollToItem`, `scrollBy`, or app-level wrapper must have both:

1. an explicit intent owner, such as a user action, route focus request, initial route placement, key/offset restoration, or a parked feed-tail/feed-head session; and
2. a user-override fence that prevents a delayed data/layout effect from scrolling after a drag or another viewport-replacement intent.

The fence must be appropriate to the intent. A top feed commonly needs `parked + genuine top update + !isScrollInProgress`. A bottom feed needs a retained bottom-ownership state that is retired when the user leaves the tail. A delayed restore or route correction needs an identity or user-scroll generation captured when the intent began.

Do not mechanically convert every caller to one shared top-feed policy. Thread focus, older-page key/offset restoration, local send, initial placement, and keyboard correction have different ownership lifetimes.

## Required review questions

- What event created the scroll intent: direct user action, route request, initial placement, data append, layout correction, or restoration?
- Is the data change genuine for that intent, or only pagination, read-state mutation, busy-state mutation, or list replacement?
- What retires ownership: pointer movement, leaving the parked edge, a newer route nonce, another viewport-replacement action, or a bounded timeout?
- If the effect suspends, how does it prove that ownership is still current after resumption?
- Does the target include structural rows such as loading/busy sentinels?
- Is there an executable negative test proving that a drag or irrelevant mutation does not scroll?

## Production inventory at `origin/main@457ee1be`

| Surface | Intent owner | Required fence / current status |
|---|---|---|
| Home server list position restore | server-position restore | user-scroll generation + active-scroll fence; task #541 |
| Home title and system scroll-to-top | direct user/system action | explicit viewport replacement; compliant |
| Activity automatic top anchor | parked top feed + genuine top update | pagination/read mutation excluded; drag fenced; compliant (#928) |
| Activity “new updates” badge | direct user action | explicit viewport replacement; compliant |
| Settings Activity/Search/Saved titles | direct user action | explicit viewport replacement; compliant |
| Quiver Feedback conversation tail | parked bottom feed + genuine conversation append | busy-only changes excluded; busy sentinel included; task #541 |
| Members selected tab reveal | user/route tab selection | active-scroll fence; compliant |
| Members Agent Activity tail | parked bottom feed | leaving tail/drag retires ownership; compliant |
| Thread long-message collapse correction | direct collapse action | measurable item + active-scroll fence; compliant |
| Thread header, inline search, back-to-bottom | direct user action | explicit viewport replacement; compliant |
| Thread share capture | bounded capture workflow | capture session owns viewport; compliant |
| Thread older-page restore | key/offset restoration | captured/current user-scroll generation; compliant |
| Thread ordinary bottom correction | parked bottom session | active-scroll + focused-owner fences; compliant |
| Thread local-send bottom session | explicit local send | anchor identity + user-scroll generation; compliant |
| Thread focused-message session | route `(messageId, nonce)` | consumed/interrupted identity, generation, active-scroll fence, bounded cap; compliant |
| Thread initial/keyboard bottom placement | initial route or parked layout intent | drag/focus yield for initial placement; parked-tail gate for keyboard; compliant |

Framework-internal Kuikly corrections and test/probe pages are not app-level inventory entries. Framework corrections still must preserve the same user-override invariant at their own ownership boundary.

## Minimum regression matrix for data-driven feed anchors

An executable policy test should prove at least:

1. genuine edge append while parked scrolls to the correct structural target;
2. pagination or an irrelevant mutation does not scroll;
3. the same genuine append during direct user movement does not scroll; and
4. leaving the parked edge retires ownership until the user returns to it.
