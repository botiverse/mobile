# What a screenshot proves: grading mobile visual evidence

Owner: 辣条 · Verified at: 2026-10-18 · Status: current

Visual claims fail in two directions: a fixture screenshot presented as "verified on device", and a real 1px rendering difference escalated as a parity bug. This article fixes the vocabulary: which carrier supports which claim, how to capture each one, and how to decide whether a difference is real.

## Three carriers, three ceilings

| Carrier | What it is | Proves | Does not prove |
|---|---|---|---|
| Reference image | Web/design screenshot of the target behaviour | what the target looks like | anything about the client |
| Fixture screenshot | Android connected-test build with `-PslockVisualFixtures=true`, driven to a named visual page | the real production component tree renders with fixed inputs, in the theme/state the fixture sets | live data, permissions, server contracts, other themes, other platforms |
| Authenticated build on a device/emulator | the shipped or candidate package, logged in, real data | the user-visible behaviour against real state | other platforms, other accounts, scale/theme variants you did not exercise |

Fixture pages are compiled from `compose/shared/src/visualFixtures/kotlin`; they are not production route registrations (`docs/raft-ui-kmp-component-library-audit-2026-07-03.md`). A screenshot from one is evidence about the component, not about the product loop.

Concrete example: a followers-popover fixture rendered under the Brutal theme. The screenshot supported "the popover chrome matches the reference in Brutal"; it could not support an Elegant-theme claim, and it did not exercise the real remove/restore API — the report had to say the interaction wiring was verified with injected callbacks only.

## Capturing fixture evidence

1. Match the artifact to a commit first: versionCode and workflow run, using `release-provenance-containment.md`. An unlabelled screenshot is not evidence.
2. Install the connected-test pair (`build.raft.app.connectedtest` and its test package) from the same run. If a different build with the same applicationId is installed, `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstall the old package first instead of debugging the fixture.
3. Launch the page by its app-owned entry point, not by tap-driving menus. The diagnostics deep-link alias targets the Kuikly host, which accepts a `pageName` extra (`compose/androidApp/src/main/AndroidManifest.xml`; `docs/diagnostics-deep-link-intent-spec-2026-06-26.md`); `scripts/slock_android_adb.py open-page` is the maintained driver (`.agents/skills/slock-android-adb-audit/SKILL.md`).
4. Capture and keep the PNG (`adb exec-out screencap -p > shot.png`), and record device/AVD name, build versionCode, theme, and display density next to it.

## Real difference or rendering jitter?

Judge at the same density, theme, and font scale. Crop the same region on both images before comparing; whole-screen eyeballing hides geometry and invents differences in empty space.

Usually **jitter** — do not escalate:

- 1px anti-aliasing on rounded corners, circles, and diagonal edges;
- subpixel text rasterization differences between densities or renderers;
- PNG recompression and shadow blur that differ only under software rendering;
- differences visible only when zoomed past ~400% on an anti-aliased edge.

Usually **real** — escalate with a named component change:

- spacing rhythm: a row's horizontal padding, list inset, or gap between avatar and title shifted by a visible step;
- border thickness or color (a hairline vs a 2px rule), corner radius class, fill token (cream vs white);
- a missing or extra element: status dot, separator, close affordance, count;
- text weight/size/letter-spacing, or alignment that no longer shares an edge with a sibling;
- overlapping content: avatars colliding, text clipped by a container that did not grow.

The test for a real diff: name what changed and which component property produces it. "The row looks tighter" is an impression; "the follower row's start padding is one step smaller than the header's, so the avatar no longer aligns with the title" is evidence. If you cannot map the pixels to a property, say so and mark it unverified rather than asserting parity.

## Honest claim language

| Evidence in hand | Allowed claim | Not allowed |
|---|---|---|
| Fixture screenshot of the changed component | "the component renders as shown under <theme> with fixture data" | "verified on device" |
| Fixture with injected callbacks | "the interaction wiring produces the expected state transitions in the fixture" | "remove/undo works against the server" |
| Authenticated build, one device | "verified on <device>, build <versionCode>, account <role>" | "works on iOS/OHOS", "works for all roles" |
| Reference vs client screenshots, same density/theme | "header chrome matches; list inset differs by ~4dp" | "pixel-perfect parity" |

When a lane or platform was not exercised, write it in the report as `not verified on <lane>` instead of leaving it to inference. A reviewer can accept a bounded claim; they cannot audit silence.
