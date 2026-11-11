package build.raft.app.data

import build.raft.app.data.realtime.RealtimeLocalEvents
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The channel announcement slice (web `channelAnnouncementStore.ts`): one read at a time per
 * channel, an answer older than the latest write dropped, a failure keeping what is shown, and the
 * realtime events that read it again.
 */
class SharedChannelAnnouncementStoreTest {
    private val key = ChannelAnnouncementKey("srv-1", "ch-ops")

    private fun body(artifactId: String?, seen: Boolean = false): String =
        if (artifactId == null) {
            """{ "announcement": null }"""
        } else {
            """{ "announcement": { "artifactId": "$artifactId", "title": "Ops rules", "version": 1, "updatedAt": "2026-11-10T09:30:00.000Z", "updatedBy": { "type": "user", "id": "u1", "name": "Martin" }, "note": null, "seen": $seen } }"""
        }

    private fun channelBody(artifactId: String?): String =
        """{ "id": "ch-ops", "name": "ops", "announcement": ${body(artifactId).removePrefix("{ \"announcement\": ").removeSuffix(" }")} }"""

    /** Each request waits on the next answer the test hands it. */
    private class FakeApi : RaftChannelAnnouncementApi {
        val reads = ArrayDeque<CompletableDeferred<HomeNetworkResponse>>()
        val updates = ArrayDeque<CompletableDeferred<HomeNetworkResponse>>()
        val readCalls = mutableListOf<DataRequestPriority>()
        val updateCalls = mutableListOf<String?>()

        override suspend fun read(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse {
            readCalls += priority
            val answer = CompletableDeferred<HomeNetworkResponse>()
            reads.addLast(answer)
            return answer.await()
        }

        override suspend fun update(serverId: ServerId, channelId: ChannelId, artifactId: String?): HomeNetworkResponse {
            updateCalls += artifactId
            val answer = CompletableDeferred<HomeNetworkResponse>()
            updates.addLast(answer)
            return answer.await()
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    private fun load(store: SharedChannelAnnouncementStore, reason: String = "open") =
        store.dispatch(ChannelAnnouncementIntent.Load(key, reason, DataRequestPriority.Visible))

    @Test
    fun aReadFillsTheSliceAndNoneIsKnownNone() =
        runTest {
            val api = FakeApi()
            val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), this)
            assertEquals(ChannelAnnouncementSlice.Unread, store.current(key))
            load(store)
            advanceUntilIdle()
            api.reads.removeFirst().complete(ok(body("pt_a")))
            advanceUntilIdle()
            assertEquals("pt_a", store.current(key).announcement?.artifactId)

            load(store, "poll")
            advanceUntilIdle()
            api.reads.removeFirst().complete(ok(body(null)))
            advanceUntilIdle()
            assertEquals(ChannelAnnouncementSlice(read = true, announcement = null), store.current(key))
        }

    @Test
    fun readsWhileOneIsInFlightCoalesceIntoOneMoreRead() =
        runTest {
            val api = FakeApi()
            val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), this)
            load(store)
            advanceUntilIdle()
            load(store, "channel_updated")
            load(store, "activity_changed")
            advanceUntilIdle()
            assertEquals(1, api.readCalls.size, "one request at a time per channel")
            api.reads.removeFirst().complete(ok(body("pt_a")))
            advanceUntilIdle()
            assertEquals(2, api.readCalls.size, "then exactly one more, for what changed meanwhile")
            api.reads.removeFirst().complete(ok(body("pt_a", seen = true)))
            advanceUntilIdle()
            assertEquals(true, store.current(key).announcement?.seen)
            assertEquals(2, api.readCalls.size)
        }

    @Test
    fun aFailedReadKeepsWhatIsShown() =
        runTest {
            val api = FakeApi()
            val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), this)
            load(store)
            advanceUntilIdle()
            api.reads.removeFirst().complete(ok(body("pt_a")))
            advanceUntilIdle()
            load(store, "poll")
            advanceUntilIdle()
            api.reads.removeFirst().complete(HomeNetworkResponse(code = 500, bodyText = null))
            advanceUntilIdle()
            assertEquals("pt_a", store.current(key).announcement?.artifactId)
        }

    @Test
    fun aReadOlderThanASetIsDropped() =
        runTest {
            val api = FakeApi()
            val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), this)
            load(store)
            advanceUntilIdle()
            store.dispatch(ChannelAnnouncementIntent.Change(key, "pt_b"))
            advanceUntilIdle()
            assertEquals(listOf<String?>("pt_b"), api.updateCalls)
            api.updates.removeFirst().complete(ok(channelBody("pt_b")))
            advanceUntilIdle()
            // The read that started before the set answers last, with the old announcement.
            api.reads.removeFirst().complete(ok(body("pt_a")))
            advanceUntilIdle()
            assertEquals("pt_b", store.current(key).announcement?.artifactId)
            val mutation = store.state.value.mutations[key]!!
            assertEquals(ChannelAnnouncementMutationStatus.Succeeded, mutation.status)
            store.dispatch(ChannelAnnouncementIntent.ConsumeMutation(key, mutation.id))
            assertNull(store.state.value.mutations[key])
        }

    @Test
    fun aRefusedChangeKeepsTheAnnouncementAndCarriesTheRefusal() =
        runTest {
            val api = FakeApi()
            val store = SharedChannelAnnouncementStore(ChannelAnnouncementRepository(api), this)
            load(store)
            advanceUntilIdle()
            api.reads.removeFirst().complete(ok(body("pt_a")))
            advanceUntilIdle()
            store.dispatch(ChannelAnnouncementIntent.Change(key, null))
            store.dispatch(ChannelAnnouncementIntent.Change(key, "pt_c"))
            advanceUntilIdle()
            assertEquals(listOf<String?>(null), api.updateCalls, "a second change waits for the first")
            api.updates.removeFirst().complete(
                HomeNetworkResponse(
                    code = 403,
                    bodyText = null,
                    errorBodyText = """{ "code": "channel_announcement_forbidden", "error": "Only the owner of #ops (one of its channel admins) or a server owner or admin can set or remove its announcement." }""",
                    responseHeaders = mapOf("x-trace-id" to listOf("0123456789abcdef0123456789abcdef")),
                ),
            )
            advanceUntilIdle()
            assertEquals("pt_a", store.current(key).announcement?.artifactId)
            val failed = assertIs<ChannelAnnouncementMutationStatus.Failed>(store.state.value.mutations[key]?.status)
            assertEquals("channel_announcement_forbidden", failed.error.code)
            assertEquals("0123456789abcdef0123456789abcdef", failed.error.traceId)
        }

    @Test
    fun realtimeEventsThatReadItAgain() {
        fun reason(event: build.raft.app.model.RealtimeEvent, serverId: String = "srv-1") = channelAnnouncementRealtimeReloadReason(key, serverId, event)
        val channelUpdated = RealtimeRefreshRequired(RealtimeRefreshScope.Home, "channel:updated", ServerId("srv-1"), ChannelId("ch-ops"))
        assertEquals("channel_updated", reason(channelUpdated))
        assertNull(reason(channelUpdated.copy(channelId = ChannelId("ch-other"))))
        assertNull(reason(channelUpdated.copy(scope = RealtimeRefreshScope.ChannelMembers)))
        assertNull(reason(channelUpdated, serverId = "srv-2"))
        assertEquals("catch_up", reason(RealtimeRefreshRequired(RealtimeRefreshScope.Home, RealtimeLocalEvents.CatchUpRequired, ServerId("srv-1"))))
        assertEquals("activity_changed", reason(ActivityChanged(ServerId("srv-1"), "object")))
        assertNull(reason(ActivityChanged(ServerId("srv-2"), "object"), serverId = "srv-2"))
    }
}
