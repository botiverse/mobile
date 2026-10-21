package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/** app_opened (30-minute session gap) and page_viewed (one per page entry) rules, on virtual time. */
class RaftProductEventsRecorderSessionTest {
    private class Harness(val recorder: RaftProductEventsRecorder, val transport: RecordingProductEventsTransport)

    private fun TestScope.harness(): Harness {
        val transport = RecordingProductEventsTransport()
        val client = productEventsClient(transport)
        client.setSignedIn(ServerId("srv-a"))
        return Harness(RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime }), transport)
    }

    private fun TestScope.sent(harness: Harness): List<String> {
        harness.recorder.client.flushNow()
        testScheduler.runCurrent()
        return harness.transport.sentEvents.map { wire ->
            when (wire.event) {
                "app_opened" -> "app_opened:" + wire.properties.getValue("start").jsonPrimitive.content
                "page_viewed" -> "page_viewed:" + wire.properties.getValue("route").jsonPrimitive.content
                else -> wire.event
            }
        }
    }

    @Test
    fun appOpenedIsColdOnceThenForegroundOnlyAfterThirtyMinutesAway() =
        runTest {
            val h = harness()
            h.recorder.onAppForeground()
            h.recorder.onAppForeground() // iOS inactive -> active: not a new open
            assertEquals(listOf("app_opened:cold"), sent(h))

            h.recorder.onAppBackground()
            testScheduler.advanceTimeBy(RaftAppOpenForegroundGapMillis - 1)
            h.recorder.onAppForeground()
            assertEquals(listOf("app_opened:cold"), sent(h), "29:59 away is the same session")

            h.recorder.onAppBackground()
            testScheduler.advanceTimeBy(RaftAppOpenForegroundGapMillis)
            h.recorder.onAppForeground()
            assertEquals(listOf("app_opened:cold", "app_opened:foreground"), sent(h), "30:00 away is a new open")

            val sessions = h.transport.sentEvents.mapNotNull { it.clientSessionId }.distinct()
            assertEquals(2, sessions.size, "each open starts a new client session")
        }

    @Test
    fun appOpenedCarriesViaOnlyWhenKnown() =
        runTest {
            val h = harness()
            h.recorder.onAppForeground(RaftAppOpenVia.Notification)
            sent(h)
            val properties = h.transport.sentEvents.single().properties
            assertEquals("notification", properties.getValue("via").jsonPrimitive.content)
        }

    @Test
    fun pageViewedIsNotRepeatedOnRecompositionOrRepeatedAppear() =
        runTest {
            val h = harness()
            val pages = h.recorder.pageViews
            pages.onPageRoute("p1", RaftProductPageView(RaftPageRoute.Channel))
            assertEquals(emptyList(), sent(h), "not shown yet")
            pages.onPageShown("p1")
            pages.onPageShown("p1")
            pages.onPageRoute("p1", RaftProductPageView(RaftPageRoute.Channel))
            assertEquals(listOf("page_viewed:channel"), sent(h))
        }

    @Test
    fun returningFromBackgroundIsNotAPageEntry() =
        runTest {
            val h = harness()
            val pages = h.recorder.pageViews
            h.recorder.onAppForeground()
            pages.onPageRoute("shell-home", RaftProductPageView(RaftPageRoute.Home))
            pages.onPageShown("shell-home")

            // Android order: the page hides, then the process goes to the background.
            pages.onPageHidden("shell-home")
            h.recorder.onAppBackground()
            testScheduler.advanceTimeBy(60_000L)
            h.recorder.onAppForeground()
            pages.onPageShown("shell-home")

            // iOS order: background first; the page may never report hidden.
            h.recorder.onAppBackground()
            h.recorder.onAppForeground()
            pages.onPageShown("shell-home")

            assertEquals(listOf("app_opened:cold", "page_viewed:home"), sent(h))
        }

    @Test
    fun backFromACoveringPageAndTabSwitchBackAreEntries() =
        runTest {
            val h = harness()
            val pages = h.recorder.pageViews
            pages.onPageRoute("tab-home", RaftProductPageView(RaftPageRoute.Home))
            pages.onPageShown("tab-home")
            // Push a channel over Home, then Back.
            pages.onPageHidden("tab-home")
            pages.onPageRoute("thread-1", RaftProductPageView(RaftPageRoute.Channel))
            pages.onPageShown("thread-1")
            pages.onPageHidden("thread-1")
            pages.onPageClosed("thread-1")
            pages.onPageShown("tab-home")
            // A system dialog pauses Home without another page showing: not an entry.
            pages.onPageHidden("tab-home")
            pages.onPageShown("tab-home")
            // Tab switch to Tasks and back.
            pages.onPageHidden("tab-home")
            pages.onPageRoute("tab-tasks", RaftProductPageView(RaftPageRoute.Tasks))
            pages.onPageShown("tab-tasks")
            pages.onPageHidden("tab-tasks")
            pages.onPageShown("tab-home")

            assertEquals(
                listOf("page_viewed:home", "page_viewed:channel", "page_viewed:home", "page_viewed:tasks", "page_viewed:home"),
                sent(h),
            )
        }

    @Test
    fun aPageCoveredAcrossABackgroundStillCountsALaterBack() =
        runTest {
            val h = harness()
            val pages = h.recorder.pageViews
            h.recorder.onAppForeground()
            pages.onPageRoute("tab-home", RaftProductPageView(RaftPageRoute.Home))
            pages.onPageShown("tab-home")
            // iOS: background and foreground while Home stays on screen (no hide reported).
            h.recorder.onAppBackground()
            h.recorder.onAppForeground()
            pages.onPageHidden("tab-home")
            pages.onPageRoute("dm-1", RaftProductPageView(RaftPageRoute.Dm))
            pages.onPageShown("dm-1")
            pages.onPageHidden("dm-1")
            pages.onPageShown("tab-home")
            assertEquals(listOf("app_opened:cold", "page_viewed:home", "page_viewed:dm", "page_viewed:home"), sent(h))
        }

    @Test
    fun taskThreadMarkerIsConsumedOnce() =
        runTest {
            val h = harness()
            h.recorder.markTaskThreadOpening("shared-shell-thread-t1")
            assertEquals(true, h.recorder.consumeTaskThreadOpening("shared-shell-thread-t1"))
            assertEquals(false, h.recorder.consumeTaskThreadOpening("shared-shell-thread-t1"))
            assertEquals(false, h.recorder.consumeTaskThreadOpening(""))
        }
}
