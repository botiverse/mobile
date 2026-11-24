package build.raft.webcontainer

import build.raft.webcontainer.resilience.WebContainerPhase
import build.raft.webcontainer.resilience.WebContainerWatchdog
import build.raft.webcontainer.resilience.WebContainerWatchdogAction
import build.raft.webcontainer.resilience.WebContainerWatchdogEvent
import build.raft.webcontainer.resilience.WebContainerWatchdogPolicy
import build.raft.webcontainer.resilience.WebNavigationStateRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
