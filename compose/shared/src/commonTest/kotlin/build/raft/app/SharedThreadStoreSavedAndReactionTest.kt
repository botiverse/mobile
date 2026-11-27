package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SavedMessageStatusSnapshot
import build.raft.app.data.SavedStatusRepository
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStoreSavedAndReactionTest {
    @Test
    fun sendMessageForwardsAttachmentIds() = runTest {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.sendMessage(
            channelId = "channel-1",
            body = "with files",
            asTask = false,
            attachmentIds = listOf("att-1", "att-2"),
            randomId = "rid-1"
        )

        assertEquals(listOf("att-1", "att-2"), api.lastAttachmentIds)
        assertEquals("rid-1", api.lastRandomId)
    }
    @Test
    fun saveMessageCallsThreadApi() = runTest {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.saveMessage("m1")

        assertEquals(listOf("m1"), api.savedMessageIds)
    }
    @Test
    fun unsaveMessageCallsThreadApi() = runTest {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        dataSource.unsaveMessage("m1")

        assertEquals(listOf("m1"), api.unsavedMessageIds)
    }
    @Test
    fun savedStatusCheckNeverSendsLocalSendIds() = runTest {
        // task #335: local sends have no server row and non-UUID ids; the server's saved/check used to 500
        // on them, dropping the saved markers of the whole screen.
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = setOf(MessageId("m1")))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val serverMessage = payload.messages.single()
        val withLocalSends =
            payload.copy(
                messages =
                    payload.messages +
                        serverMessage.copy(id = "${ThreadTransientLocalSendIdPrefix}thread-1", deliveryState = ThreadMessageDeliverySending) +
                        serverMessage.copy(id = "${ThreadTransientLocalSendIdPrefix}thread-2", deliveryState = ThreadMessageDeliveryFailed)
            )

        val enriched = dataSource.enrichSavedStatus(withLocalSends)

        assertEquals(listOf(listOf(MessageId(serverMessage.id))), savedStatusRepository.checkedMessageIds)
        assertEquals(serverMessage.id == "m1", enriched.messages.first().isSaved)
        assertEquals(3, enriched.messages.size, "local sends stay in the timeline, only the lookup skips them")
    }
    @Test
    fun applyMessageSavedPatchesPayloadAndCache() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("saveable body"))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertFalse(store.state.value.payload.messages.single().isSaved)

        store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))

        assertTrue(store.state.value.payload.messages.single().isSaved)
        assertTrue(dataSource.cachedPayload("channel-1")?.messages?.single()?.isSaved == true)
        val cached = localStore.readThread(ThreadId("channel-1"))
        assertNull(cached)
        scope.cancel()
    }
    @Test
    fun setMessageReactionRemovesSelectedReaction() = runTest {
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val result = dataSource.setMessageReaction("m1", "🔥", selected = false)

        assertTrue(result is DataResult.Content)
        assertEquals(emptyList(), api.addedReactions)
        assertEquals(listOf("m1" to "🔥"), api.removedReactions)
        assertEquals(emptyList(), result.data.reactions)
    }
    @Test
    fun applyMessageReactionsRollsBackOptimisticSelection() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val originalReactions = listOf(RaftThreadReactionItem("🔥", count = 1, selected = true))
        val message = contentPayload("reactable body").messages.single()
        dataSource.cachePayloadForTest(
            contentPayload("reactable body").copy(
                messages = listOf(message.copy(reactions = originalReactions))
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = false))
        store.accept(SharedThreadIntent.ApplyMessageReactions("m1", originalReactions))

        assertEquals(originalReactions, store.state.value.payload.messages.single().reactions)
        scope.cancel()
    }
    private class FakeSavedStatusRepository(
        private val savedIds: Set<MessageId>
    ) : SavedStatusRepository {
        val checkedMessageIds: MutableList<List<MessageId>> = mutableListOf()

        override suspend fun checkSavedMessages(
            serverId: ServerId,
            messageIds: List<MessageId>
        ): DataResult<SavedMessageStatusSnapshot> {
            checkedMessageIds += messageIds
            return DataResult.Content(SavedMessageStatusSnapshot(savedIds))
        }
    }
}
