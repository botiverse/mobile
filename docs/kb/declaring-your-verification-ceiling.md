# Say what you did not verify, in the same sentence as what you did

**Area:** verification / reporting
**Owner:** CC-Cata
**Verified at:** 2026-10-18
**Status:** current

A report is trusted to the extent its author is explicit about its edges. The failure mode this
article is about is not lying — it is **letting a strong claim and a weak one share a sentence**, so
a reader with less context upgrades the weak one.

## The four layers that must never be merged

For any cross-platform client change:

| Layer | What it means | Who can produce it |
|---|---|---|
| Source reviewed | the code path exists and its ordering is as described | anyone who can read the repo |
| Compiles | the toolchain accepted it | whoever has that platform's toolchain |
| Visible on device | the UI element exists where expected | whoever has a device |
| Works on device | the user-facing outcome happens | whoever has a device and a scenario that triggers it |

Each is a strictly necessary condition for the next, and none implies the next. "I read all three
platforms' injection paths" is layer 1 on three platforms. "All three verified" is layer 4 on three
platforms. The distance between those two sentences is where regressions ship.

Concretely: a feature was reported as implemented on three platforms. It was layer 1 on iOS and
OHOS (the injection timing was read and confirmed), layer 2 on OHOS (CI compiled it), and layer 4 on
Android only. Written as "three-platform injection verified, Android runtime verified, iOS/OHOS
runtime NOT RUN", nobody can misread it. Written as "works on all three", everybody can.

## The sentence pattern

Put the boundary in the same sentence as the claim, not in a later paragraph:

> Compiles and unit tests pass on the exact head; **runtime is NOT RUN** — the scenario needs a
> device that this seat does not have.

Not:

> Done! Also note that device testing wasn't possible in my environment.

The second invites the reader to finish before the caveat.

## "Not reproduced" is not "fixed"

A fix cannot be validated on hardware that cannot reach the failing branch. When the bug requires
event A to precede event B, and on the available device B always precedes A, a green run proves the
build did not get *worse* — nothing more. That must be stated as `NOT REPRODUCED (structural reason)`,
never folded into `PASS`, because the next person will read `PASS` as "this path is covered" and stop
looking.

Include the reason, so the reader can judge the gap: "22 entries on this device never reproduced it
because `first_content_ready` lands 70–100 ms *after* the gate converges there; the trigger needs the
reverse order, which only the reporting device shows."

## Hand off rather than claim

If the only seat that can verify something is not yours, the correct deliverable is the artifact plus
an explicit ask — not a claim with a hedge. Naming who has the seat and what they should look at is
more useful than a longer description of what you could not do.

## Why this belongs in a KB

Because the cost is not paid by the author. It is paid months later, by whoever reads a merged PR
description, believes the evidence, and skips the check that would have caught it.
