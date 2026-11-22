package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** dm #102: the ready signal, stall timer and process-gone handling of in-app Raft web pages. */
class RaftWebViewLoadWatchdogTest {
    private var now = 1_000L
    private val logs = mutableListOf<RaftWebViewLoadLogEntry>()

    private fun watchdog(url: String = "https://app.raft.build/s/raft/settings/billing?access_token=SECRET#frag", retry: Int = 0) =
        RaftWebViewLoadWatchdog(page = "settings_billing", url = url, retry = retry, clock = { now }, logger = { logs += it })

    @Test
    fun aDeadlineBeforeReadyStallsAndShowsTheError() {
        val dog = watchdog()
        assertEquals(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS, dog.remainingMs())
        now += 5_000
        assertEquals(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS - 5_000, dog.remainingMs())
        dog.onLoadFinished()
        dog.onContentProbe(false)

        now += 10_000
        dog.onDeadline(dog.state.attempt)
        assertEquals(RaftWebViewLoadPhase.Stalled, dog.state.phase)
        assertTrue(dog.showsError)
        assertFalse(dog.state.probing)
        val stalled = logs.last()
        assertTrue(stalled.warn)
        assertEquals(
            "webview_load_stalled afterMs=15000 timeoutMs=15000 finished=true blank=true attempt=1 page=settings_billing host=app.raft.build",
            stalled.message,
        )

        // Terminal for the attempt: a late finish, probe or deadline changes nothing.
        dog.onLoadFinished()
        dog.onContentProbe(true)
        dog.onPageReadyMessage()
        dog.onDeadline(dog.state.attempt)
        assertEquals(RaftWebViewLoadPhase.Stalled, dog.state.phase)
        assertEquals(1, logs.count { it.message.startsWith("webview_load_stalled") })
    }

    @Test
    fun aDeadlineNeverStallsAReadyFailedOrOtherAttempt() {
        val ready = watchdog()
        ready.onLoadFinished()
        ready.onContentProbe(true)
        ready.onDeadline(1)
        assertEquals(RaftWebViewLoadPhase.Ready, ready.state.phase)

        val failed = watchdog()
        failed.onLoadFailed()
        failed.onDeadline(1)
        assertEquals(RaftWebViewLoadPhase.Failed, failed.state.phase)
        assertFalse(failed.showsError)

        // A re-armed attempt (a later navigation) gets its own full budget.
        val navigated = watchdog()
        navigated.onLoadFinished()
        navigated.onContentProbe(true)
        now += 20_000
        navigated.onNavigationState(RaftWebViewNavigationState(isLoading = false, progress = 100))
        navigated.onNavigationState(RaftWebViewNavigationState(isLoading = true, progress = 10))
        assertEquals(2, navigated.state.attempt)
        assertEquals(RaftWebViewLoadPhase.Loading, navigated.state.phase)
        assertEquals(RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS, navigated.remainingMs())
        navigated.onDeadline(1)
        assertEquals(RaftWebViewLoadPhase.Loading, navigated.state.phase)
        navigated.onDeadline(2)
        assertEquals(RaftWebViewLoadPhase.Stalled, navigated.state.phase)
    }

    @Test
    fun processGoneBeforeReadyIsAnErrorAfterReadyRearms() {
        val loading = watchdog()
        now += 3_000
        loading.onProcessGone("web_content_process_terminated")
        assertEquals(RaftWebViewLoadPhase.ProcessGone, loading.state.phase)
        assertTrue(loading.showsError)
        assertEquals(
            "webview_process_gone reason=web_content_process_terminated phase=loading recovering=false elapsedMs=3000 " +
                "page=settings_billing host=app.raft.build",
            logs.last().message,
        )
        assertTrue(logs.last().warn)

        val ready = watchdog()
        ready.onLoadFinished()
        ready.onContentProbe(true)
        ready.onProcessGone("render_process_crashed")
        // The platform host reloads the page it showed; the reload is guarded again.
        assertEquals(RaftWebViewLoadPhase.Loading, ready.state.phase)
        assertEquals(2, ready.state.attempt)
        assertFalse(ready.showsError)
        assertEquals(
            "webview_process_gone reason=render_process_crashed phase=ready recovering=true attempt=2 page=settings_billing host=app.raft.build",
            logs.last().message,
        )
    }

    @Test
    fun logsCarryThePageAndHostButNeverTheUrlsTokens() {
        watchdog(url = "https://user:pw@App.Raft.Build:8443/s/raft/settings?token=SECRET#x", retry = 2)
        assertEquals("webview_load_started reason=retry attempt=1 retry=2 page=settings_billing host=app.raft.build:8443", logs.single().message)
        listOf("SECRET", "token", "pw", "/s/raft").forEach { secret ->
            assertFalse(logs.single().message.contains(secret), secret)
        }
        assertEquals("-", raftWebViewLogHost("not a url"))
        assertEquals("https://app.raft.build/s/raft/settings", raftWebViewUrlForLog("https://app.raft.build/s/raft/settings?token=SECRET#x"))
        assertEquals("https://app.raft.build", raftWebViewUrlForLog("https://app.raft.build?token=SECRET"))
    }
}
