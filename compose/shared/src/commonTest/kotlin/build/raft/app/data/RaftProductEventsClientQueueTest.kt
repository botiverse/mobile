package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/** Consent gate, batching/flush, retry and drop rules of [RaftProductEventsClient] (virtual time). */
class RaftProductEventsClientQueueTest {
    private val serverA = ServerId("srv-a")

    private fun RaftProductEventWire.route(): String = properties.getValue("route").jsonPrimitive.content

    @Test
    fun consentOffSendsNothingAndDropsLaterEventsAtOnce() =
        runTest {
            val transport = RecordingProductEventsTransport().apply {
                configAnswer = { RaftProductEventsConfigResult.Answered(false) }
            }
            val client = productEventsClient(transport)
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.track(pageViewed(RaftPageRoute.Tasks))
            settleProductEvents()

            assertEquals(listOf(serverA), transport.configCalls)
            assertEquals(0, transport.sendAttempts.size, "a server that does not allow client events gets no batch")
            assertEquals(0, client.queuedEventCount)

            client.track(pageViewed(RaftPageRoute.Activity))
            assertEquals(0, client.queuedEventCount, "a fresh `false` answer drops new events at once")
            settleProductEvents()
            assertEquals(0, transport.sendAttempts.size)
        }

    @Test
    fun invalidatedConsentIsAskedAgainSoTurningSharingOffStopsSendingAtOnce() =
        runTest {
            var allowed = true
            val transport = RecordingProductEventsTransport().apply {
                configAnswer = { RaftProductEventsConfigResult.Answered(allowed) }
            }
            val client = productEventsClient(transport)
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf(serverA), transport.configCalls)
            assertEquals(listOf("home"), transport.sentEvents.map { it.route() })

            // The user turns "Share usage data" off; the server now answers false.
            allowed = false
            client.invalidateConsent()
            client.track(pageViewed(RaftPageRoute.Tasks))
            client.flushNow()
            settleProductEvents()

            assertEquals(listOf(serverA, serverA), transport.configCalls, "the cached `true` is not reused")
            assertEquals(listOf("home"), transport.sentEvents.map { it.route() }, "nothing more is sent")
            client.track(pageViewed(RaftPageRoute.Activity))
            assertEquals(0, client.queuedEventCount, "the fresh `false` drops new events at once")
        }

    @Test
    fun nothingBeforeSignInAndPendingEventsJoinTheSignedInServer() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport)
            client.track(RaftProductEvent.AppOpened(RaftAppOpenedProperties(RaftAppOpenStart.Cold)))
            settleProductEvents()
            assertEquals(0, transport.configCalls.size, "sign-in unknown: nothing leaves the device")
            assertEquals(1, client.queuedEventCount)

            client.setSignedIn(serverA)
            settleProductEvents()
            assertEquals(listOf("app_opened"), transport.sentEvents.map { it.event })
            assertEquals(serverA, transport.batches.single().first)

            val signedOutTransport = RecordingProductEventsTransport()
            val signedOut = productEventsClient(signedOutTransport)
            signedOut.track(RaftProductEvent.AppOpened(RaftAppOpenedProperties(RaftAppOpenStart.Cold)))
            signedOut.setSignedOut()
            signedOut.track(pageViewed(RaftPageRoute.Home))
            signedOut.setSignedIn(serverA)
            settleProductEvents()
            assertEquals(0, signedOutTransport.sendAttempts.size, "events from before sign-in are never sent")
        }

    @Test
    fun batchesAreCappedAtTheIngestLimit() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport, RaftProductEventsClientConfig(flushSizeThreshold = 1_000))
            client.setSignedIn(serverA)
            repeat(230) { client.track(pageViewed(RaftPageRoute.Home)) }
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf(100, 100, 30), transport.batches.map { it.second.events.size })
        }

    @Test
    fun offlineConsentLookupAndThrowingTransportKeepEventsWithoutCrashing() =
        runTest {
            var online = false
            val transport = RecordingProductEventsTransport().apply {
                configAnswer = {
                    if (!online) throw IllegalStateException("no network")
                    RaftProductEventsConfigResult.Answered(true)
                }
            }
            val client = productEventsClient(transport, RaftProductEventsClientConfig(retryBaseMillis = 1_000L))
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            testScheduler.runCurrent()
            assertEquals(1, client.queuedEventCount, "kept while offline")
            assertEquals(0, transport.sendAttempts.size)

            online = true
            settleProductEvents()
            assertEquals(listOf("home"), transport.sentEvents.map { it.route() })
        }

    @Test
    fun queueIsCappedAndSignOutClearsIt() =
        runTest {
            val client =
                productEventsClient(
                    transport = null,
                    config = RaftProductEventsClientConfig(maxQueuedEvents = 5, flushSizeThreshold = 1_000, maxUnboundEvents = 2),
                )
            repeat(4) { client.track(pageViewed(RaftPageRoute.Home)) }
            assertEquals(2, client.queuedEventCount, "unbound (pre-sign-in) events are capped separately")
            client.setSignedIn(serverA)
            repeat(10) { client.track(pageViewed(RaftPageRoute.Tasks)) }
            assertEquals(5, client.queuedEventCount)
            client.setSignedOut()
            assertEquals(0, client.queuedEventCount)
            client.track(pageViewed(RaftPageRoute.Home))
            assertEquals(0, client.queuedEventCount, "signed out: dropped")
        }

    @Test
    fun eventsTooOldForIngestAreDropped() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport = null, config = RaftProductEventsClientConfig(maxEventAgeMillis = 60_000L))
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            testScheduler.advanceTimeBy(61_000L)
            client.track(pageViewed(RaftPageRoute.Tasks))
            client.bindTransport(transport)
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf("tasks"), transport.sentEvents.map { it.route() })
            assertTrue(transport.sentEvents.all { it.event == "page_viewed" })
        }
}
