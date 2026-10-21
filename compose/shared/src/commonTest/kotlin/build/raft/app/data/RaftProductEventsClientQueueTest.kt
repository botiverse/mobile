package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/** Consent gate, batching/flush, retry and drop rules of [RaftProductEventsClient] (virtual time). */
class RaftProductEventsClientQueueTest {
    private val serverA = ServerId("srv-a")
    private val serverB = ServerId("srv-b")

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
    fun answerAskedBeforeInvalidationIsAskedAgain() =
        runTest {
            val recording = RecordingProductEventsTransport()
            val firstAnswer = CompletableDeferred<Unit>()
            var allowed = true
            val transport =
                object : RaftProductEventsTransport {
                    override suspend fun fetchConfig(serverId: ServerId): RaftProductEventsConfigResult {
                        val first = recording.configCalls.isEmpty()
                        val answer = RaftProductEventsConfigResult.Answered(allowed)
                        recording.configCalls += serverId
                        if (first) firstAnswer.await()
                        return answer
                    }

                    override suspend fun sendBatch(serverId: ServerId, batch: RaftProductEventBatchRequest) =
                        recording.sendBatch(serverId, batch)
                }
            val client = productEventsClient(transport = null).also { it.bindTransport(transport) }
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            testScheduler.runCurrent()
            assertEquals(1, recording.configCalls.size, "config request in flight")

            // Answered `true` before the switch flipped; the server now says false.
            allowed = false
            client.invalidateConsent()
            firstAnswer.complete(Unit)
            settleProductEvents()

            assertEquals(2, recording.configCalls.size, "the in-flight answer is not trusted")
            assertEquals(0, recording.sendAttempts.size, "nothing is sent on the stale `true`")
            assertEquals(0, client.queuedEventCount)
        }

    @Test
    fun consentIsCachedPerServerAndAskedAgainAfterTheTtl() =
        runTest {
            val transport = RecordingProductEventsTransport().apply {
                configAnswer = { server -> RaftProductEventsConfigResult.Answered(server == serverA) }
            }
            val client = productEventsClient(transport, RaftProductEventsClientConfig(consentTtlMillis = 60_000L))
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            settleProductEvents()
            client.track(pageViewed(RaftPageRoute.Tasks))
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf(serverA), transport.configCalls, "second flush reuses the cached answer")

            client.setSignedIn(serverB)
            client.track(pageViewed(RaftPageRoute.Activity))
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf(serverA, serverB), transport.configCalls)
            assertEquals(listOf(serverA, serverA), transport.batches.map { it.first }, "server B said no")

            client.setSignedIn(serverA)
            testScheduler.advanceTimeBy(61_000L)
            client.track(pageViewed(RaftPageRoute.More))
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf(serverA, serverB, serverA), transport.configCalls, "stale answer is refreshed")
            assertEquals(listOf("home", "tasks", "more"), transport.sentEvents.map { it.route() })
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
    fun flushesOnIntervalOnSizeAndOnDemand() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client =
                productEventsClient(transport, RaftProductEventsClientConfig(flushIntervalMillis = 10_000L, flushSizeThreshold = 3))
            client.setSignedIn(serverA)

            client.track(pageViewed(RaftPageRoute.Home))
            testScheduler.advanceTimeBy(9_000L)
            testScheduler.runCurrent()
            assertEquals(0, transport.batches.size, "quiet queue waits for the interval")
            testScheduler.advanceTimeBy(1_001L)
            testScheduler.runCurrent()
            assertEquals(listOf(1), transport.batches.map { it.second.events.size })

            repeat(3) { client.track(pageViewed(RaftPageRoute.Tasks)) }
            testScheduler.runCurrent()
            assertEquals(listOf(1, 3), transport.batches.map { it.second.events.size }, "size threshold flushes at once")

            client.track(pageViewed(RaftPageRoute.More))
            client.flushNow()
            testScheduler.runCurrent()
            assertEquals(listOf(1, 3, 1), transport.batches.map { it.second.events.size }, "background flush")
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
    fun badRequestDropsTheBatchWithoutRetrying() =
        runTest {
            val transport = RecordingProductEventsTransport().apply {
                sendResults += RaftProductEventsSendResult.Rejected(400)
            }
            val client = productEventsClient(transport)
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            settleProductEvents()
            assertEquals(1, transport.sendAttempts.size)
            assertEquals(0, client.queuedEventCount)

            testScheduler.advanceTimeBy(10 * 60_000L)
            testScheduler.runCurrent()
            assertEquals(1, transport.sendAttempts.size, "a 400 is never retried")

            client.track(pageViewed(RaftPageRoute.Tasks))
            client.flushNow()
            settleProductEvents()
            assertEquals(listOf("tasks"), transport.sentEvents.map { it.route() }, "later events still go out")
        }

    @Test
    fun networkFailureRetriesWithBackoffAndKeepsOrder() =
        runTest {
            val transport = RecordingProductEventsTransport().apply {
                sendResults += RaftProductEventsSendResult.Retryable(0)
                sendResults += RaftProductEventsSendResult.Retryable(503)
            }
            val client =
                productEventsClient(transport, RaftProductEventsClientConfig(retryBaseMillis = 2_000L, retryMaxMillis = 60_000L))
            client.setSignedIn(serverA)
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            testScheduler.runCurrent()
            assertEquals(1, transport.sendAttempts.size)
            client.track(pageViewed(RaftPageRoute.Tasks))

            testScheduler.advanceTimeBy(1_999L)
            testScheduler.runCurrent()
            assertEquals(1, transport.sendAttempts.size, "first retry waits the base delay")
            testScheduler.advanceTimeBy(2L)
            testScheduler.runCurrent()
            assertEquals(2, transport.sendAttempts.size)

            // The first retry ran at t=2000; the second is due at t=6000 (now t=2001).
            testScheduler.advanceTimeBy(3_998L)
            testScheduler.runCurrent()
            assertEquals(2, transport.sendAttempts.size, "second retry doubles the delay")
            testScheduler.advanceTimeBy(2L)
            testScheduler.runCurrent()
            assertEquals(3, transport.sendAttempts.size)
            assertEquals(listOf("home", "tasks"), transport.sentEvents.map { it.route() })
            assertEquals(0, client.queuedEventCount)
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
