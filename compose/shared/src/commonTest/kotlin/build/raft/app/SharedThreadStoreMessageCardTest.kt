package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.canonicalThreadDataSource
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.messageCardRowJson
import build.raft.app.data.parseSharedMessageRow
import build.raft.app.data.readMessageCardFromMetadata
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * RFC 082: a card message's update replaces the message in the open conversation, whether it
 * comes as `message:updated` or as the sync log's `message` row (both the message list row,
 * botiverse/slock 1e9266662), and an older revision never replaces a newer one.
 */
class SharedThreadStoreMessageCardTest {
    private fun socketUpdate(state: String, revision: Int, updatedAt: String): MessageUpdated =
        assertIs<MessageUpdated>(
            RealtimeEventMapper(diagnosticsLogger = {})
                .map(RealtimeSocketEvents.MessageUpdated, messageCardRowJson(state = state, revision = revision, updatedAt = updatedAt))
        )

    private fun syncUpdate(state: String, revision: Int, updatedAt: String): MessageUpdated =
        MessageUpdated(
            assertNotNull(
                parseSharedMessageRow(
                    Json.parseToJsonElement(messageCardRowJson(state = state, revision = revision, updatedAt = updatedAt)).jsonObject
                )
            )
        )

    @Test
    fun cardUpdatesReplaceTheHeldMessageAndAnOlderRevisionIsIgnored() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        // As an older build holds it: the fallback text only.
        dataSource.cachePayloadForTest(contentPayload("Raft App 1.19.0 (11900001) · In review"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        fun held() = store.state.value.payload.messages.single()

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(socketUpdate("Testing", 2, "2026-11-03T12:10:00.000Z")))
        assertEquals("Raft App 1.19.0 (11900001) · Testing", held().body)
        assertEquals(2, readMessageCardFromMetadata(held().metadata)?.revision)

        // The phone was away: the sync log's row brings revision 3.
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(syncUpdate("Ready for testing", 3, "2026-11-03T12:11:00.000Z")))
        assertEquals("Raft App 1.19.0 (11900001) · Ready for testing", held().body)
        assertEquals(3, readMessageCardFromMetadata(held().metadata)?.revision)

        // A late live frame of revision 2 crosses it: revision 3 stays.
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(socketUpdate("Testing", 2, "2026-11-03T12:10:00.000Z")))
        assertEquals("Raft App 1.19.0 (11900001) · Ready for testing", held().body)
        assertEquals(3, readMessageCardFromMetadata(held().metadata)?.revision)
        scope.cancel()
    }
}
