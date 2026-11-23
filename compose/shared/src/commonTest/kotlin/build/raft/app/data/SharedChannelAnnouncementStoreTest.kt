package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val RULES = "pt_rules"
private const val OTHER = "pt_other"

private fun announcementBody(artifactId: String?, seen: Boolean = false): String =
    if (artifactId == null) {
        """{ "announcement": null }"""
    } else {
        """{ "announcement": { "artifactId": "$artifactId", "title": "Ops rules", "version": 1, "updatedAt": "2026-11-10T09:30:00.000Z", "updatedBy": { "type": "user", "id": "u1", "name": "Martin" }, "note": null, "seen": $seen } }"""
    }

/**
 * The channel announcement slice (web `channelAnnouncementStore.ts`): when it reads
 * `GET channels/:id/announcement`, one test per row of the trigger table of botiverse/slock #16289
 * (4bb09d45d; its cases are `packages/web/tests/channelAnnouncementReads.behavior.test.tsx`), and
 * what an answer or a change does to what is shown. Realtime frames go through the app's mapper as
 * the server sends them; time is virtual.
 */
class SharedChannelAnnouncementStoreTest {
    private val key = ChannelAnnouncementKey("srv-1", "ch-ops")
    private val mapper = RealtimeEventMapper()

    /**
     * The server: [has] is the channel's announcement (null: none). A read answers what it had when
     * asked: at once, or once released while [gated]; 500 while [failing]. A change waits for the
     * answer the test hands it.
     */
    private class FakeApi : RaftChannelAnnouncementApi {
        var has: String? = RULES
        var gated = false
        var failing = false
        val readCalls = mutableListOf<DataRequestPriority>()
        private val waiting = ArrayDeque<CompletableDeferred<Unit>>()
        val updates = ArrayDeque<CompletableDeferred<HomeNetworkResponse>>()
        val updateCalls = mutableListOf<String?>()

        val reads: Int get() = readCalls.size

        override suspend fun read(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse {
            readCalls += priority
            val asked = has
            if (gated) CompletableDeferred<Unit>().also { waiting.addLast(it) }.await()
            return if (failing) HomeNetworkResponse(code = 500, bodyText = null) else HomeNetworkResponse(code = 200, bodyText = announcementBody(asked))
        }

        override suspend fun update(serverId: ServerId, channelId: ChannelId, artifactId: String?): HomeNetworkResponse {
            updateCalls += artifactId
            val answer = CompletableDeferred<HomeNetworkResponse>()
            updates.addLast(answer)
            return answer.await()
        }

        /** The oldest read waiting answers. */
        fun releaseFirst() {
            waiting.removeFirst().complete(Unit)
        }

        fun release() {
            while (waiting.isNotEmpty()) releaseFirst()
        }
    }

    /** [jitter]: the wait after a drop the app did not cause; a store that never waits unless a test says so. */
    private fun TestScope.store(
        api: FakeApi,
        foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
        jitter: () -> Long = { error("only a reconnect after a drop the app did not cause waits") },
    ) = SharedChannelAnnouncementStore(
        ChannelAnnouncementRepository(api),
        backgroundScope,
        foreground = foreground,
        nowMillis = { testScheduler.currentTime },
        reconnectJitterMillis = jitter,
    )

    private fun TestScope.open(store: SharedChannelAnnouncementStore) {
        store.dispatch(ChannelAnnouncementIntent.Open(key))
        runCurrent()
    }

    /** An accepted realtime event of srv-1 (the shell's realtime owner hands the store every one). */
    private fun TestScope.deliver(store: SharedChannelAnnouncementStore, event: RealtimeEvent, serverId: String = "srv-1") {
        store.dispatch(ChannelAnnouncementIntent.Realtime(serverId, event))
        runCurrent()
    }

    private fun TestScope.trigger(store: SharedChannelAnnouncementStore, intent: ChannelAnnouncementIntent) {
        store.dispatch(intent)
        runCurrent()
    }

    /** `channel:updated` as publishChannelUpdate sends it after a `PATCH channels/:id`: the row as stored. */
    private fun channelUpdated(announcement: String?, name: String = "ops"): RealtimeEvent =
        mapper.map(
            RealtimeSocketEvents.ChannelUpdated,
            """{"channel":{"id":"ch-ops","serverId":"srv-1","name":"$name","description":null,"type":"channel","guestVisible":false,"visitorVisible":false,"archivedAt":null,"announcementArtifactId":${announcement?.let { "\"$it\"" } ?: "null"},"createdAt":"2026-11-01T00:00:00.000Z"}}""",
        )!!

    /** A message in the channel; with [change] ("set", "replaced", "removed" of [artifactId]), the change line a set posts. */
    private fun messageNew(id: String, change: String? = null, artifactId: String = RULES): RealtimeEvent {
        val notice =
            change?.let {
                ""","actionMetadata":{"kind":"channel-announcement","change":"$it","actor":{"type":"user","id":"u1","name":"Martin"},"artifactId":"$artifactId","title":"Ops rules"}"""
            }.orEmpty()
        val sender = if (change == null) """"senderType":"user","senderId":"u2","messageType":"chat"""" else """"senderType":"user","senderId":"system","messageType":"system""""
        return mapper.map(
            RealtimeSocketEvents.MessageNew,
            """{"id":"$id","seq":${id.filter(Char::isDigit).ifEmpty { "1" }},"channelId":"ch-ops",$sender,"content":"line $id","createdAt":"2026-11-23T09:00:00.000Z"$notice}""",
        )!!
    }

    private fun activityChanged(serverId: String = "srv-1"): RealtimeEvent =
        mapper.map(RealtimeSocketEvents.ActivityChanged, """{"serverId":"$serverId","source":"object"}""")!!

    private fun SharedChannelAnnouncementStore.shows(): String? = current(key).announcement?.artifactId

    // ---- The trigger table of slock #16289, row by row ------------------------------------------

    @Test
    fun row01OpeningReadsUnlessAnAnswerUnder30SecondsOldIsHeldOneReadAtATime() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            assertEquals(1, api.reads)
            assertEquals(RULES, store.shows())
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            open(store)
            assertEquals(1, api.reads, "reopened within 30 s")
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MILLIS)
            open(store)
            assertEquals(2, api.reads, "reopened 30 s later")
            // The strip and the details row open it while it is read: one request.
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_OPEN_FRESH_MILLIS)
            api.gated = true
            open(store)
            open(store)
            api.release()
            runCurrent()
            assertEquals(3, api.reads)
        }

    @Test
    fun row02ARowChangeThatNamesTheHeldAnnouncementDoesNotRead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            deliver(store, messageNew("m1"))
            deliver(store, messageNew("m2"))
            // A rename's channel:updated names the announcement held.
            deliver(store, channelUpdated(RULES, name = "ops-renamed"))
            deliver(store, channelUpdated(RULES, name = "ops"))
            assertEquals(1, api.reads, "messages, a rename")
        }

    @Test
    fun row03ARowNamingAnotherAnnouncementOrNoneReadsAlsoWhenNothingIsHeldOrItLandedWhileClosed() =
        runTest {
            val api = FakeApi()
            api.has = null
            api.failing = true
            val store = store(api)
            open(store)
            assertEquals(ChannelAnnouncementSlice.Unread, store.current(key))
            // Nothing held yet counts as different, a row naming none included.
            api.failing = false
            deliver(store, channelUpdated(null))
            assertEquals(2, api.reads)
            assertEquals(ChannelAnnouncementSlice(read = true, announcement = null), store.current(key))
            // Someone else sets it: the first channel:updated that names it.
            api.has = RULES
            deliver(store, channelUpdated(RULES))
            assertEquals(3, api.reads)
            assertEquals(RULES, store.shows())
            // Removed: once; the rows after it name none as well.
            api.has = null
            deliver(store, channelUpdated(null))
            deliver(store, channelUpdated(null, name = "ops-renamed"))
            assertEquals(4, api.reads)
            assertEquals(ChannelAnnouncementSlice(read = true, announcement = null), store.current(key))
            // Set while the channel is closed: nothing reads then, reopening within 30 s does.
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            api.has = OTHER
            deliver(store, channelUpdated(OTHER))
            assertEquals(4, api.reads, "not shown")
            open(store)
            assertEquals(5, api.reads)
            assertEquals(OTHER, store.shows())
            // The change line a replacement posts names the new one too; its channel:updated then matches.
            api.has = RULES
            deliver(store, messageNew("m3", change = "replaced", artifactId = RULES))
            deliver(store, channelUpdated(RULES))
            assertEquals(6, api.reads)
            assertEquals(RULES, store.shows())
        }

    @Test
    fun row04AFrameThatNamesNoAnnouncementDoesNotRead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            deliver(store, channelUpdated(RULES))
            // The id-only frame (a joint participant disconnected) and a row without the field.
            deliver(store, mapper.map(RealtimeSocketEvents.ChannelUpdated, """{"channelId":"ch-ops"}""")!!)
            deliver(store, mapper.map(RealtimeSocketEvents.ChannelUpdated, """{"channel":{"id":"ch-ops","serverId":"srv-1","name":"ops","archivedAt":null}}""")!!)
            // The same id again after them.
            deliver(store, channelUpdated(RULES))
            assertEquals(1, api.reads)
        }

    @Test
    fun row05AChangeWhileAReadIsInFlightReadsOnceMoreAfterIt() =
        runTest {
            val api = FakeApi()
            api.gated = true
            api.has = null
            val store = store(api)
            open(store)
            // Set while the open read is in flight: its answer predates it.
            api.has = RULES
            deliver(store, channelUpdated(RULES))
            deliver(store, channelUpdated(RULES, name = "ops-renamed"))
            assertEquals(1, api.reads, "shares the read in flight")
            api.releaseFirst()
            runCurrent()
            assertEquals(2, api.reads, "one more after it")
            api.release()
            runCurrent()
            assertEquals(RULES, store.shows())
            assertEquals(2, api.reads)
            // Its Artifact viewed, twice, while another read is in flight: one more read after it.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            assertEquals(3, api.reads)
            api.release()
            runCurrent()
            assertEquals(4, api.reads, "coalesced into one")
            api.release()
            runCurrent()
            assertEquals(4, api.reads)
        }

    @Test
    fun row06NoReadWhileIdle() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            advanceTimeBy(60_000L)
            advanceTimeBy(10 * 60_000L)
            assertEquals(1, api.reads)
        }

    @Test
    fun row07ItsArtifactViewedReadsAtOnceEveryTime() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            api.has = RULES
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            assertEquals(2, api.reads)
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            assertEquals(3, api.reads, "however recent the last read")
        }

    @Test
    fun row08AnotherArtifactViewedDoesNotRead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", OTHER))
            assertEquals(1, api.reads)
        }

    @Test
    fun row09ActivityChangedReadsAtMostOncePer5MinutesAndOnlyInFront() =
        runTest {
            val api = FakeApi()
            val foreground = MutableStateFlow(true)
            val store = store(api, foreground)
            open(store)
            deliver(store, activityChanged())
            advanceTimeBy(60_000L)
            deliver(store, activityChanged())
            assertEquals(1, api.reads, "within 5 minutes of the last read")
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_STALE_MILLIS)
            deliver(store, activityChanged())
            assertEquals(2, api.reads)
            deliver(store, activityChanged())
            assertEquals(2, api.reads)
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_STALE_MILLIS)
            foreground.value = false
            deliver(store, activityChanged())
            assertEquals(2, api.reads, "the app in the background")
        }

    @Test
    fun row10AnotherServersActivityChangedDoesNotRead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_STALE_MILLIS)
            deliver(store, activityChanged("srv-2"), serverId = "srv-2")
            deliver(store, activityChanged("srv-2"))
            assertEquals(1, api.reads)
        }

    @Test
    fun row11BackInFrontReadsOnlyWhenTheLastReadIs5MinutesOld() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            advanceTimeBy(60_000L)
            trigger(store, ChannelAnnouncementIntent.Visible(key))
            assertEquals(1, api.reads, "back after a minute")
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_STALE_MILLIS)
            trigger(store, ChannelAnnouncementIntent.Visible(key))
            assertEquals(2, api.reads, "back after more than 5 minutes")
        }

    @Test
    fun row12AReconnectReadsTheShownChannelOnceHoweverRecentTheLastRead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            api.has = OTHER
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            assertEquals(2, api.reads)
            assertEquals(OTHER, store.shows())
            // Sharing a read in flight.
            api.gated = true
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            api.release()
            runCurrent()
            assertEquals(3, api.reads)
            // A channel no longer shown: never.
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            assertEquals(3, api.reads)
        }

    @Test
    fun row12AfterADropTheAppDidNotCauseTheReadWaitsARandomMomentFirst() =
        runTest {
            val api = FakeApi()
            val store = store(api, jitter = { 7_000L })
            open(store)
            api.has = OTHER
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            // Dropped again while it waits: still one read.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            advanceTimeBy(6_999L)
            runCurrent()
            assertEquals(1, api.reads, "waits its jitter")
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, api.reads)
            assertEquals(OTHER, store.shows())
            advanceTimeBy(CHANNEL_ANNOUNCEMENT_RECONNECT_JITTER_MILLIS)
            runCurrent()
            assertEquals(2, api.reads)
        }

    @Test
    fun row12AReadAskedAfterTheSocketCameBackAndAnsweredMakesTheWaitingOneNeedless() =
        runTest {
            val api = FakeApi()
            val store = store(api, jitter = { 10_000L })
            open(store)
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            // Its Artifact viewed meanwhile: that read is asked after the socket came back.
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(2, api.reads, "no read after the wait")
            // One that fails answers nothing: the wait still ends in a read.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            api.failing = true
            trigger(store, ChannelAnnouncementIntent.ArtifactViewed("srv-1", RULES))
            api.failing = false
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(4, api.reads)
            // One asked before the drop and answered after it does not count either.
            api.gated = true
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            api.release()
            runCurrent()
            api.gated = false
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(6, api.reads)
            // A channel:updated naming another announcement meanwhile triggers a read that supersedes the wait.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            api.has = OTHER
            deliver(store, channelUpdated(OTHER))
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(7, api.reads, "channel:updated read supersedes the waiting reconnect")
            assertEquals(OTHER, store.shows())
            // Our own change answered after the socket came back supersedes the wait too.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            trigger(store, ChannelAnnouncementIntent.Change(key, RULES))
            api.updates.removeFirst().complete(
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{ "id": "ch-ops", "name": "ops", "announcement": ${announcementBody(RULES).removePrefix("{ \"announcement\": ").removeSuffix(" }")} }""",
                ),
            )
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(7, api.reads, "our own update supersedes the waiting reconnect")
            assertEquals(RULES, store.shows())
        }

    @Test
    fun row12TheWaitingReadEndsWithThePageOrAResetAndWaitsForTheAppInFront() =
        runTest {
            val api = FakeApi()
            val foreground = MutableStateFlow(true)
            val store = store(api, foreground, jitter = { 5_000L })
            open(store)
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(1, api.reads, "no page shows it")
            open(store)
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            store.dispatch(ChannelAnnouncementIntent.Reset)
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(1, api.reads, "reset")
            // The app in the background when the wait ends: it reads once the app is in front.
            trigger(store, ChannelAnnouncementIntent.Reconnected(key, afterDrop = true))
            foreground.value = false
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(1, api.reads, "in the background")
            foreground.value = true
            runCurrent()
            assertEquals(2, api.reads)
        }

    @Test
    fun row13ADroppedAnswerIsNoRead() =
        runTest {
            val api = FakeApi()
            api.gated = true
            val store = store(api)
            open(store)
            // At 10 s a set answers; the read asked before it answers at 20 s, and is dropped.
            advanceTimeBy(10_000L)
            trigger(store, ChannelAnnouncementIntent.Change(key, OTHER))
            api.updates.removeFirst().complete(HomeNetworkResponse(code = 200, bodyText = """{ "id": "ch-ops", "name": "ops", "announcement": ${announcementBody(OTHER).removePrefix("{ \"announcement\": ").removeSuffix(" }")} }"""))
            runCurrent()
            advanceTimeBy(10_000L)
            api.release()
            runCurrent()
            assertEquals(OTHER, store.shows(), "the set, not the older read")
            val mutation = store.state.value.mutations.getValue(key)
            assertEquals(ChannelAnnouncementMutationStatus.Succeeded, mutation.status)
            store.dispatch(ChannelAnnouncementIntent.ConsumeMutation(key, mutation.id))
            assertNull(store.state.value.mutations[key])
            // 31 s after the set and 21 s after the dropped answer: the last read is the set's.
            store.dispatch(ChannelAnnouncementIntent.Close(key))
            api.gated = false
            advanceTimeBy(21_000L)
            open(store)
            assertEquals(2, api.reads)
        }

    @Test
    fun row14AResetDropsHeldAnswersReadTimesAndReadsInFlightOrQueued() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            advanceTimeBy(60_000L)
            api.gated = true
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            // A change queued behind that read.
            deliver(store, channelUpdated(OTHER))
            assertEquals(2, api.reads)
            store.dispatch(ChannelAnnouncementIntent.Reset)
            assertEquals(ChannelAnnouncementSlice.Unread, store.current(key))
            // A minute after the last read, and with the old read still out: in front again reads.
            trigger(store, ChannelAnnouncementIntent.Visible(key))
            assertEquals(3, api.reads)
            // The old read ends: dropped, no queued read, and the new read is still the one in flight.
            api.releaseFirst()
            runCurrent()
            assertEquals(ChannelAnnouncementSlice.Unread, store.current(key))
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            assertEquals(3, api.reads)
            api.release()
            runCurrent()
            assertEquals(3, api.reads)
            assertEquals(RULES, store.shows())
        }

    // ---- What an answer or a change does -----------------------------------------------------------

    @Test
    fun aFailedReadKeepsWhatIsShown() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            api.failing = true
            trigger(store, ChannelAnnouncementIntent.Reconnected(key))
            assertEquals(2, api.reads)
            assertEquals(RULES, store.shows())
        }

    @Test
    fun aRefusedChangeKeepsTheAnnouncementAndCarriesTheRefusal() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            open(store)
            store.dispatch(ChannelAnnouncementIntent.Change(key, null))
            store.dispatch(ChannelAnnouncementIntent.Change(key, "pt_c"))
            runCurrent()
            assertEquals(listOf<String?>(null), api.updateCalls, "a second change waits for the first")
            api.updates.removeFirst().complete(
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{ "code": "channel_announcement_forbidden", "error": "Only the owner of #ops (one of its channel admins) or a server owner or admin can set or remove its announcement." }""",
                    responseHeaders = mapOf("x-trace-id" to listOf("0123456789abcdef0123456789abcdef")),
                ),
            )
            runCurrent()
            assertEquals(RULES, store.shows())
            val failed = assertIs<ChannelAnnouncementMutationStatus.Failed>(store.state.value.mutations[key]?.status)
            assertEquals("channel_announcement_forbidden", failed.error.code)
            assertEquals("0123456789abcdef0123456789abcdef", failed.error.traceId)
        }
}
