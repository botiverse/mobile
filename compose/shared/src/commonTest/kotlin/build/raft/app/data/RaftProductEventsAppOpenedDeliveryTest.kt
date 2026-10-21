package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * app_opened delivery rule (Nova, 2026-10-21): an open is recorded only when the user is already
 * signed in at that moment (signed-in cold start: session restoring -> ready; signed-in return
 * after the 30-minute gap). It waits for the server and its config, then is sent or dropped per
 * `clientEventsAllowed`. An open while signed out is dropped, also after a later sign-in
 * (nothing before login). Virtual time throughout.
 */
class RaftProductEventsAppOpenedDeliveryTest {
    private val serverA = ServerId("srv-a")

    private fun RecordingProductEventsTransport.appOpenedStarts(): List<String> =
        sentEvents.filter { it.event == "app_opened" }.map { it.properties.getValue("start").jsonPrimitive.content }

    private fun TestScope.recorder(client: RaftProductEventsClient) =
        RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime })

    @Test
    fun signedInColdStartSendsOneColdOpenAfterLateServerAndConfig() =
        runTest {
            val config = CompletableDeferred<RaftProductEventsConfigResult>()
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport = null)
            val recorder = recorder(client)

            // Process start: the open is recorded while the stored session is still restoring
            // (sign-in unknown), before the transport, the server id or its config exist.
            recorder.onAppForeground()
            settleProductEvents()
            assertEquals(1, client.queuedEventCount, "the cold open waits for the restored session")

            client.bindTransport(
                object : RaftProductEventsTransport by transport {
                    override suspend fun fetchConfig(serverId: ServerId): RaftProductEventsConfigResult {
                        transport.fetchConfig(serverId)
                        return config.await()
                    }
                }
            )
            client.setSignedIn(serverA) // auth gate: Restoring -> Ready
            settleProductEvents()
            assertEquals(listOf(serverA), transport.configCalls, "config is asked for the resolved server")
            assertEquals(0, transport.sendAttempts.size, "nothing is sent before the config answers")

            // Recomposition / lifecycle repeats while the config is still loading.
            client.setSignedIn(serverA)
            recorder.onAppForeground()

            config.complete(RaftProductEventsConfigResult.Answered(true))
            settleProductEvents()
            assertEquals(listOf("cold"), transport.appOpenedStarts())
            assertEquals(serverA, transport.batches.single().first)

            client.setSignedIn(serverA)
            recorder.onAppForeground()
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf("cold"), transport.appOpenedStarts(), "exactly one cold open")
            assertEquals(0, client.queuedEventCount)
        }

    @Test
    fun openWhileSignedOutIsDroppedEvenAfterALaterSignIn() =
        runTest {
            // Fresh install / signed-out cold start: restoring -> sign-in screen -> login.
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport)
            val recorder = recorder(client)
            recorder.onAppForeground()
            client.setSignedOut()
            recorder.pageViews.onPageRoute("login", RaftProductPageView(RaftPageRoute.Home))
            recorder.pageViews.onPageShown("login")
            testScheduler.advanceTimeBy(2 * 60_000L)
            client.setSignedIn(serverA)
            settleProductEvents()
            assertEquals(0, transport.sendAttempts.size, "nothing from before login is sent")
            assertEquals(0, client.queuedEventCount)

            // A return after the session gap while signed out: also dropped.
            client.setSignedOut()
            recorder.onAppBackground()
            testScheduler.advanceTimeBy(RaftAppOpenForegroundGapMillis)
            recorder.onAppForeground()
            client.setSignedIn(serverA)
            settleProductEvents()
            assertEquals(emptyList(), transport.appOpenedStarts())
        }

    @Test
    fun signedInOpenIsDroppedWhenTheServerDisallowsClientEvents() =
        runTest {
            val transport = RecordingProductEventsTransport()
            transport.configAnswer = { RaftProductEventsConfigResult.Answered(false) }
            val client = productEventsClient(transport)
            val recorder = recorder(client)

            recorder.onAppForeground()
            client.setSignedIn(serverA)
            settleProductEvents()
            assertEquals(listOf(serverA), transport.configCalls)
            assertEquals(0, transport.sendAttempts.size, "clientEventsAllowed=false: never sent")
            assertEquals(0, client.queuedEventCount, "and not kept")
        }

    @Test
    fun signedInReturnAfterTheGapSendsOneForegroundOpen() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport)
            val recorder = recorder(client)
            client.setSignedIn(serverA)
            recorder.onAppForeground()
            settleProductEvents()
            assertEquals(listOf("cold"), transport.appOpenedStarts())

            recorder.onAppBackground()
            testScheduler.advanceTimeBy(RaftAppOpenForegroundGapMillis - 1)
            recorder.onAppForeground()
            settleProductEvents()
            assertEquals(listOf("cold"), transport.appOpenedStarts(), "29:59 away: same session")

            recorder.onAppBackground()
            testScheduler.advanceTimeBy(RaftAppOpenForegroundGapMillis)
            recorder.onAppForeground()
            recorder.onAppForeground() // iOS inactive -> active blip
            settleProductEvents()
            assertEquals(listOf("cold", "foreground"), transport.appOpenedStarts())
        }
}
