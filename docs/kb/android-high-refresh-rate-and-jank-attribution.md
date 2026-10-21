# Android high refresh rate: enabling 120Hz and telling real jank from measurement artifacts

Owner: 沙琪玛 · Verified at: 2026-10-18 · Status: current

How the Android client opts into high refresh rate (landed in PR #3003), how to verify the mode switch actually happened on a device, and the criteria we use to separate real frame drops from sampling artifacts before blaming the renderer.

## 1. Why third-party apps are capped at 60Hz by default

On high-refresh Android devices the system does not automatically give every app the panel's maximum refresh rate. A window runs at the refresh rate of its current `Display.Mode`, and the default mode for third-party apps is typically 60Hz even on a 120Hz panel. The app must explicitly ask for a faster mode via `Window.attributes.preferredDisplayModeId` (API 23+).

Our renderer (`KRVsyncModule`) drives frames through `Choreographer.postFrameCallback`, so it follows the vsync cadence of whatever mode the window is in — capped window means capped frames, regardless of how fast the render code is.

## 2. The opt-in recipe (what PR #3003 does)

In the activity's `onCreate`:

1. Read `display.mode` (the current mode).
2. Filter `display.supportedModes` to modes with the **same physical resolution** as the current mode.
3. Pick the highest `refreshRate` among those.
4. If it differs from the current mode, set `preferredDisplayModeId`.

Two deliberate constraints:

- **Same-resolution filter** — `supportedModes` mixes resolution and refresh-rate variants; picking by refresh rate alone can silently switch resolution.
- **Preference, not force** — `preferredDisplayModeId` is a hint. The system may still downclock (power saving, thermal, battery saver). That is intended: we never fight the platform's power policy.

No-op paths: below API 23, devices with a single mode, and devices already on the fastest same-resolution mode.

## 3. Verification SOP (does the window actually run at 120Hz?)

Do not trust "it feels smoother". Confirm with platform state:

1. **Mode check**: `adb shell dumpsys display | grep -i "mBaseDisplayInfo\|mode"` while the app is foregrounded — the active mode for the display should show the high refresh rate with the app focused. Per-window: `adb shell dumpsys window` shows the app's `preferredDisplayModeId`.
2. **Frame pacing**: `adb shell dumpsys gfxinfo <package> framestats` — frame intervals should cluster around ~8.3ms (120Hz), not ~16.6ms (60Hz).
3. **Settings override check**: some OEMs expose per-app refresh-rate settings; verify the device isn't forcing 60Hz on the app before concluding the code path failed.

A/B pairing rule: compare the same build with the preference removed/disabled, on the same device, same content, same scroll gesture. Only paired runs make a refresh-rate claim.

## 4. Real jank vs. measurement artifact — attribution criteria

Before reporting "the app is dropping frames", rule out the artifact classes:

| Observation | Likely artifact if… | Likely real jank if… |
|---|---|---|
| Sparse `>2× frame interval` samples | They cluster at mode switches, app foregrounding, or notification shade pulls | They cluster during steady-state scrolling of uniform content |
| Jank on emulator | Emulator at all — emulator vsync is host-driven and meaningless for jank claims | (never conclude jank from emulator) |
| Jank right after enabling high refresh | First seconds after a display-mode change (surface re-creation transient) | Sustained after the mode has settled |
| Long frames in `gfxinfo` | `input handling`/`animation` buckets dominated by touch batching on a busy device | `sync/upload` or `draw` buckets dominate repeatedly on the same screen |
| Frame-time spikes | Choreographer callback delayed by a GC or a background thread dump | The main thread was continuously busy between two vsyncs (verify with a method trace / `Choreographer` delta histogram) |

Practical rules we follow:

- **Steady-state only**: discard the first 2 seconds after navigation or mode change.
- **Physical device only**: emulators and cloud phones are for functional checks, never for jank numbers.
- **Threshold**: count a dropped frame at `>2×` the expected frame interval (16.6ms at 60Hz, 8.3ms at 120Hz), and only call it a regression when the paired A/B run shows a statistically visible difference (e.g. p95 frame time or dropped-frame ratio), not a single spike. Minimum collection window: at least 10 seconds of steady state per run, three repeated runs per side, and the claim is made from the trend across runs, never from one run.
- **Confirm with a second instrument**: `Choreographer` sampling, `gfxinfo framestats`, and a systrace/Perfetto capture should agree before a jank claim is treated as real.

## 5. Platform boundary (as of 2026-10-18)

Be precise about what "120Hz support" means today:

- **Android** — source merged (PR #3003); `preferredDisplayModeId` opt-in as described above. No full real-device frame-timing PASS receipt yet; treat as code-complete, evidence-pending.
- **iOS** — source merged (PR #2989, `CADisableMinimumFrameDurationOnPhone`); same evidence-pending status for full real-device frame timing.

## 6. What is intentionally not covered

- Forcing 120Hz against system power policy (by design, see §2).
- Per-scene dynamic refresh-rate switching (e.g. dropping to 60Hz on static content to save power) — not implemented; revisit only with device evidence.
- iOS ProMotion mechanism details — separate platform mechanism (PR #2989); only its status boundary is noted in §5.
