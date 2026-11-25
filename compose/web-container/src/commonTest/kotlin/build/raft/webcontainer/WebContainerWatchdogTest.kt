package build.raft.webcontainer

import build.raft.webcontainer.resilience.WebContainerPhase
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebContainerWatchdogTest {
    @Test
    fun happyPathTransitionsThroughDomProbeToReady() {
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    stallTimeoutMs = 10_000L,
                    blankProbeDelayMs = 1_000L,
                ),
            )
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 0L))
        assertEquals(WebContainerPhase.Loading, watchdog.phase)

        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.PageCommitted(1), 500L))
        assertEquals(WebContainerPhase.Committed, watchdog.phase)

        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(1), 800L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 1_500L))

        val probeAction = watchdog.onEvent(WebContainerWatchdogEvent.Tick, 1_800L)
        assertEquals(WebContainerWatchdogAction.RequestDomProbe(1), probeAction)

        assertEquals(
            WebContainerWatchdogAction.None,
            watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(1, hasVisibleContent = true), 1_850L),
        )
        assertEquals(WebContainerPhase.Ready, watchdog.phase)
    }

    @Test
    fun blankDomProbeEmitsTerminalErrorWhileUnavailableProbeFailsOpen() {
        val watchdog = WebContainerWatchdog(WebContainerWatchdogPolicy(blankProbeDelayMs = 0L))
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 0L)
        assertEquals(WebContainerWatchdogAction.RequestDomProbe(1), watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(1), 100L))

        val blankAction = watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(1, hasVisibleContent = false), 150L)
        val terminal = assertIs<WebContainerWatchdogAction.EmitTerminalError>(blankAction)
        assertEquals("blank_screen_detected", terminal.reason)
        assertEquals(WebContainerPhase.BlankSuspected, watchdog.phase)

        // New load with scripts disabled (null probe) fails open to Ready.
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(2), 200L)
        watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(2), 250L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(2, null), 300L))
        assertEquals(WebContainerPhase.Ready, watchdog.phase)
    }

    @Test
    fun stallTimeoutTriggersTerminalError() {
        val watchdog = WebContainerWatchdog(WebContainerWatchdogPolicy(stallTimeoutMs = 5_000L))
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 1_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 5_999L))

        val timedOut = watchdog.onEvent(WebContainerWatchdogEvent.Tick, 6_000L)
        val terminal = assertIs<WebContainerWatchdogAction.EmitTerminalError>(timedOut)
        assertEquals("load_stall_timeout", terminal.reason)
        assertEquals(WebContainerPhase.Failed, watchdog.phase)
    }

    @Test
    fun rendererCrashDuringLoadFailsImmediatelyWithoutLooping() {
        val watchdog = WebContainerWatchdog()
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 0L)
        val action = watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 100L)
        val terminal = assertIs<WebContainerWatchdogAction.EmitTerminalError>(action)
        assertEquals("render_process_crashed_during_load", terminal.reason)
        assertEquals(WebContainerPhase.CrashedTerminal, watchdog.phase)
    }

    @Test
    fun rollingWindowCircuitBreakerStopsReloadLoopAndRecoversAfterWindowExpires() {
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    crashWindowMs = 60_000L,
                    maxRecoveriesInWindow = 2,
                ),
            )

        // Crash #1 while Ready -> Recover attempt 1
        markReady(watchdog, gen = 1, t = 1_000L)
        assertEquals(
            WebContainerWatchdogAction.TriggerRecoveryReload(1),
            watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 2_000L),
        )

        // Crash #2 while Ready -> Recover attempt 2
        markReady(watchdog, gen = 2, t = 3_000L)
        assertEquals(
            WebContainerWatchdogAction.TriggerRecoveryReload(2),
            watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = false), 4_000L),
        )

        // Crash #3 within 60s window -> Circuit open!
        markReady(watchdog, gen = 3, t = 5_000L)
        val openAction = watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 6_000L)
        val terminal = assertIs<WebContainerWatchdogAction.EmitTerminalError>(openAction)
        assertEquals("render_process_circuit_open", terminal.reason)
        assertEquals(WebContainerPhase.CrashedTerminal, watchdog.phase)

        // After 60s window expires, fresh recovery is permitted again.
        markReady(watchdog, gen = 4, t = 70_000L)
        assertEquals(
            WebContainerWatchdogAction.TriggerRecoveryReload(1),
            watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 71_000L),
        )
    }

    @Test
    fun loadCancelledRestoresPriorReadyStateWithoutResettingCrashWindow() {
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    stallTimeoutMs = 5_000L,
                    crashWindowMs = 60_000L,
                    maxRecoveriesInWindow = 2,
                ),
            )
        // First load cancelled before any commit -> returns to Idle without stall timeout.
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 0L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.LoadCancelled(1), 500L))
        assertEquals(WebContainerPhase.Idle, watchdog.phase)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 10_000L))

        // Committed page experiences one recovery, then starts a navigation converted to download.
        markReady(watchdog, gen = 2, t = 10_000L)
        assertEquals(
            WebContainerWatchdogAction.TriggerRecoveryReload(1),
            watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 11_000L),
        )
        markReady(watchdog, gen = 3, t = 12_000L)
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(4), 13_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.LoadCancelled(4), 13_200L))
        assertEquals(WebContainerPhase.Ready, watchdog.phase)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 25_000L))
        // Prior recovery count is preserved (next crash is recovery attempt 2, not 1).
        assertEquals(
            WebContainerWatchdogAction.TriggerRecoveryReload(2),
            watchdog.onEvent(WebContainerWatchdogEvent.RenderProcessGone(didCrash = true), 26_000L),
        )
    }

    @Test
    fun navigationStateRelayDeduplicatesAndBoundsReentrancy() {
        val relay = WebNavigationStateRelay(maxReentrySteps = 3)
        val delivered = mutableListOf<WebNavigationState>()
        var current = WebNavigationState(url = "https://app.raft.build/1", progress = 50)

        relay.notify(force = false, isActive = { true }, readState = { current }, deliver = { delivered += it })
        relay.notify(force = false, isActive = { true }, readState = { current }, deliver = { delivered += it })
        assertEquals(1, delivered.size)

        // Non-forced re-entrant state mutation is delivered once on follow-up pass.
        delivered.clear()
        relay.reset()
        relay.notify(
            force = false,
            isActive = { true },
            readState = { current },
            deliver = { state ->
                delivered += state
                if (state.progress == 50) {
                    current = current.copy(progress = 100)
                    relay.notify(force = false, isActive = { true }, readState = { current }, deliver = {})
                }
            },
        )
        assertEquals(listOf(50, 100), delivered.map { it.progress })

        // Forced re-entrant callback stops at maxReentrySteps.
        delivered.clear()
        var step = 0
        relay.notify(
            force = true,
            isActive = { true },
            readState = { current.copy(progress = step * 10) },
            deliver = { state ->
                delivered += state
                step += 1
                relay.notify(force = true, isActive = { true }, readState = { current.copy(progress = step * 10) }, deliver = {})
            },
        )
        assertEquals(3, delivered.size)
    }

    @Test
    fun backgroundedLoadingPausesStallTimeoutAndPreservesRemainingForegroundBudget() {
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    stallTimeoutMs = 15_000L,
                    blankProbeDelayMs = 1_500L,
                ),
            )
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 1_000L)
        // Spend 4s in foreground -> 11s foreground budget left.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 5_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 5_000L))
        assertTrue(watchdog.isBackgrounded)

        // Stay backgrounded for 60s (4x stallTimeoutMs): ticks must never fire errors.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 25_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 65_000L))
        assertEquals(WebContainerPhase.Loading, watchdog.phase)

        // Return to foreground at t=65_000: must still have full 11s remaining.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 65_000L))
        assertFalse(watchdog.isBackgrounded)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 65_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 70_000L))

        // Second background excursion of 30s after 5s more foreground (9s total foreground, 6s left).
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 70_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 100_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 100_000L))

        // 5_999ms later (14_999ms total foreground): still loading, no premature error.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 105_999L))
        assertEquals(WebContainerPhase.Loading, watchdog.phase)

        // Exactly at 15_000ms total foreground: stalls cleanly.
        val timedOut = watchdog.onEvent(WebContainerWatchdogEvent.Tick, 106_000L)
        val terminal = assertIs<WebContainerWatchdogAction.EmitTerminalError>(timedOut)
        assertEquals("load_stall_timeout", terminal.reason)
        assertEquals(WebContainerPhase.Failed, watchdog.phase)
    }

    @Test
    fun backgroundedPageFinishedSuppressesDomProbeUntilForegroundDelayElapses() {
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    stallTimeoutMs = 15_000L,
                    blankProbeDelayMs = 1_500L,
                ),
            )
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 0L)
        watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(1), 1_000L)
        // Spend 500ms in foreground post-finish, then background for 30s.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 1_500L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 1_500L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 31_500L))

        // Foreground at 31_500 -> remaining 1_000ms of blankProbeDelayMs runs in foreground.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 31_500L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 32_499L))
        assertEquals(
            WebContainerWatchdogAction.RequestDomProbe(1),
            watchdog.onEvent(WebContainerWatchdogEvent.Tick, 32_500L),
        )
    }

    @Test
    fun loadStartedAndPageFinishedWhileBackgroundedKeepExactForegroundBudget() {
        // Case 1: LoadStarted arrives while already backgrounded -> no overshoot or underflow.
        val watchdog =
            WebContainerWatchdog(
                WebContainerWatchdogPolicy(
                    stallTimeoutMs = 10_000L,
                    blankProbeDelayMs = 1_500L,
                ),
            )
        watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 1_000L)
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 5_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 25_000L))

        // Foreground at 25_000: loadStartedAtMs shifts from 5_000 by (25_000 - 5_000) to 25_000 (not 29_000!).
        watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 25_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 34_999L))
        val terminal =
            assertIs<WebContainerWatchdogAction.EmitTerminalError>(
                watchdog.onEvent(WebContainerWatchdogEvent.Tick, 35_000L),
            )
        assertEquals("load_stall_timeout", terminal.reason)

        // Case 2: PageFinished arrives while already backgrounded (non-zero delay).
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(2), 40_000L)
        watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 41_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(2), 45_000L))
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 60_000L))
        // Foreground at 60_000: finishedAtMs anchors at 60_000 -> probes at 61_500, not 65_500.
        watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 60_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 61_499L))
        assertEquals(
            WebContainerWatchdogAction.RequestDomProbe(2),
            watchdog.onEvent(WebContainerWatchdogEvent.Tick, 61_500L),
        )

        // Case 3: PageFinished arrives while backgrounded with blankProbeDelayMs == 0L -> defers probe until foreground.
        val zeroDelayWatchdog = WebContainerWatchdog(WebContainerWatchdogPolicy(blankProbeDelayMs = 0L))
        zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 100L)
        zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 200L)
        assertEquals(
            WebContainerWatchdogAction.None,
            zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.PageFinished(1), 300L),
        )
        assertEquals(WebContainerWatchdogAction.None, zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.Tick, 10_000L))
        zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 10_000L)
        assertEquals(
            WebContainerWatchdogAction.RequestDomProbe(1),
            zeroDelayWatchdog.onEvent(WebContainerWatchdogEvent.Tick, 10_000L),
        )
    }

    @Test
    fun duplicateBackgroundForegroundEventsAreIdempotentAndResetClearsPauseState() {
        val watchdog = WebContainerWatchdog(WebContainerWatchdogPolicy(stallTimeoutMs = 10_000L))

        // Foregrounded before any Backgrounded is a no-op.
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 500L))
        assertFalse(watchdog.isBackgrounded)

        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(1), 1_000L)
        // Backgrounded at 3_000 (2s elapsed), then duplicate Backgrounded at 9_000 must keep earliest anchor (3_000).
        watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 3_000L)
        watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 9_000L)
        assertTrue(watchdog.isBackgrounded)

        // Foregrounded at 23_000 -> shifts by 20_000 (from 3_000), leaving 8_000ms remaining.
        watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 23_000L)
        // Duplicate Foregrounded at 28_000 must be a no-op (not re-shift!).
        watchdog.onEvent(WebContainerWatchdogEvent.Foregrounded, 28_000L)
        assertFalse(watchdog.isBackgrounded)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 30_999L))
        assertIs<WebContainerWatchdogAction.EmitTerminalError>(
            watchdog.onEvent(WebContainerWatchdogEvent.Tick, 31_000L),
        )

        // Reset clears pause state cleanly so subsequent loads tick normally without requiring Foregrounded.
        watchdog.onEvent(WebContainerWatchdogEvent.Backgrounded, 32_000L)
        assertTrue(watchdog.isBackgrounded)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Reset, 33_000L))
        assertFalse(watchdog.isBackgrounded)
        assertEquals(WebContainerPhase.Idle, watchdog.phase)

        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(2), 40_000L)
        assertEquals(WebContainerWatchdogAction.None, watchdog.onEvent(WebContainerWatchdogEvent.Tick, 49_999L))
        val terminal =
            assertIs<WebContainerWatchdogAction.EmitTerminalError>(
                watchdog.onEvent(WebContainerWatchdogEvent.Tick, 50_000L),
            )
        assertEquals("load_stall_timeout", terminal.reason)
    }

    private fun markReady(
        watchdog: WebContainerWatchdog,
        gen: Int,
        t: Long,
    ) {
        watchdog.onEvent(WebContainerWatchdogEvent.LoadStarted(gen), t)
        watchdog.onEvent(WebContainerWatchdogEvent.PageCommitted(gen), t + 10L)
        watchdog.onEvent(WebContainerWatchdogEvent.PageFinished(gen), t + 20L)
        watchdog.onEvent(WebContainerWatchdogEvent.DomProbeResult(gen, true), t + 30L)
    }
}
