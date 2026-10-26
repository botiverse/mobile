package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.canonicalThreadDataSource
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.SavedMessageStatusSnapshot
import build.raft.app.data.SavedStatusRepository
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

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
    fun fetchThreadPayloadDefersSavedStatusUntilSecondaryRefresh() = runTest {
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

        assertEquals(emptyList(), savedStatusRepository.checkedMessageIds)
        assertFalse(payload.messages.single().isSaved)

        val enriched = dataSource.enrichSavedStatus(payload)

        assertEquals(listOf(listOf(MessageId("m1"))), savedStatusRepository.checkedMessageIds)
        assertTrue(enriched.messages.single().isSaved)
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
    fun savedStatusCheckRebuildsIdsFromTheCurrentPayloadEachTime() = runTest {
        // Same request size is not the same request: a fixed-size window of fixed-length ids keeps the byte
        // count constant while its content changes (task #335 follow-up).
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = emptySet())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val template = payload.messages.single()
        val first = payload.copy(messages = listOf(template.copy(id = "a1"), template.copy(id = "a2")))
        val second = payload.copy(messages = listOf(template.copy(id = "a2"), template.copy(id = "a3")))

        dataSource.enrichSavedStatus(first)
        dataSource.enrichSavedStatus(second)

        assertEquals(
            listOf(listOf(MessageId("a1"), MessageId("a2")), listOf(MessageId("a2"), MessageId("a3"))),
            savedStatusRepository.checkedMessageIds
        )
    }
    @Test
    fun savedStatusCheckIsSkippedWhenOnlyLocalSendsAreOnScreen() = runTest {
        val savedStatusRepository = FakeSavedStatusRepository(savedIds = emptySet())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                savedStatusRepository = savedStatusRepository
            )
        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data
        val onlyLocal =
            payload.copy(
                messages = listOf(payload.messages.single().copy(id = "${ThreadTransientLocalSendIdPrefix}thread-3")),
                originMessage = null
            )

        dataSource.enrichSavedStatus(onlyLocal)

        assertEquals(emptyList(), savedStatusRepository.checkedMessageIds)
    }
    @Test
    fun warmSavedProjectionSurvivesPrimaryRefreshUntilSavedAuthorityCommits() = runTest {
        val savedLookupStarted = CompletableDeferred<Unit>()
        val releaseSavedLookup = CompletableDeferred<DataResult<SavedMessageStatusSnapshot>>()
        val savedStatusRepository =
            object : SavedStatusRepository {
                override suspend fun checkSavedMessages(
                    serverId: ServerId,
                    messageIds: List<MessageId>
                ): DataResult<SavedMessageStatusSnapshot> {
                    savedLookupStarted.complete(Unit)
                    return releaseSavedLookup.await()
                }
            }
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages = listOf(message(id = "m1", seq = 1, body = "fresh body").copy(isSaved = false))
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                savedStatusRepository = savedStatusRepository
            )
        val accepted =
            contentPayload("cached body").copy(
                messages = listOf(contentPayload("cached body").messages.single().copy(isSaved = true))
            )
        dataSource.cachePayloadForTest(accepted)
        // Production runs the store on viewModelScope (one thread); its state reducers are plain
        // read-modify-writes, so a multi-threaded scope lets a concurrent auxiliary commit overwrite
        // the Saved-authority commit and the projection never clears (CI flake on #2784).
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("fresh body", store.state.value.payload.messages.single().body)
        assertTrue(
            store.state.value.payload.messages.single().isSaved,
            "the messages response has no Saved authority and must not clear the accepted projection"
        )

        store.accept(SharedThreadIntent.FirstContentReady)
        savedLookupStarted.await()
        assertTrue(store.state.value.payload.messages.single().isSaved)

        releaseSavedLookup.complete(
            DataResult.Content(SavedMessageStatusSnapshot(savedIds = emptySet()))
        )
        withTimeout(5_000L) {
            while (store.state.value.payload.messages.single().isSaved) {
                delay(10L)
            }
        }

        assertFalse(store.state.value.payload.messages.single().isSaved)
        scope.cancel()
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
    fun setMessageReactionReturnsMutationSummaryWithoutWritingPayload() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))

        val result = dataSource.setMessageReaction("m1", "🔥", selected = true)

        val mutation = (result as DataResult.Content).data
        assertEquals(listOf("m1" to "🔥"), api.addedReactions)
        assertEquals("m1", mutation.messageId)
        assertEquals("🔥", mutation.reactions.single().emoji)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
    }
    @Test
    fun optimisticReactionSelectionDoesNotWriteCanonicalCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))

        val reaction = store.state.value.payload.messages.single().reactions.single()
        assertEquals("🔥", reaction.emoji)
        assertEquals(true, reaction.selected)
        assertEquals(emptyList(), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions)
        assertEquals(null, localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }
    @Test
    fun applyMessageMutationCachesCanonicalPayloadAndProjectsReadback() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val mutation =
            MessageMutationSummary(
                messageId = "m1",
                reactions =
                    listOf(
                        MessageReactionSummary(
                            emoji = "🔥",
                            count = 1,
                            reactorIds = listOf("u1"),
                            reactorNames = listOf("Artin")
                        )
                    )
            )
        store.accept(SharedThreadIntent.ApplyMessageMutation(mutation))

        val reaction = store.state.value.payload.messages.single().reactions.single()
        assertEquals("🔥", reaction.emoji)
        assertEquals(1, reaction.count)
        assertEquals(true, reaction.selected)
        assertEquals(listOf("u1"), reaction.reactorIds)
        assertEquals(listOf("Artin"), reaction.reactorNames)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals("reactable body", cachedMessage?.body)
        val cachedReaction = cachedMessage?.reactions?.single()
        assertEquals("🔥", cachedReaction?.emoji)
        assertEquals(1, cachedReaction?.count)
        assertEquals(listOf("u1"), cachedReaction?.reactorIds)
        assertEquals(listOf("Artin"), cachedReaction?.reactorNames)
        assertEquals(true, cachedReaction?.selected)
        scope.cancel()
    }
    @Test
    fun applyMessageMutationTrustsCanonicalEmptyReactionPayload() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val message =
            contentPayload("reactable body").messages.single().copy(
                reactions = listOf(RaftThreadReactionItem("🔥", count = 1, selected = true))
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(message)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(messageId = "m1", reactions = emptyList())
            )
        )

        assertEquals(emptyList(), store.state.value.payload.messages.single().reactions)
        assertEquals(emptyList(), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }
    @Test
    fun applyMessageMutationKeepsVisibleReactionOrder() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val message =
            contentPayload("reactable body").messages.single().copy(
                reactions =
                    listOf(
                        RaftThreadReactionItem("👍", count = 1, selected = true),
                        RaftThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(message)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(
                    messageId = "m1",
                    reactions =
                        listOf(
                            MessageReactionSummary(
                                emoji = "🔥",
                                count = 2,
                                reactorIds = listOf("u2", "u3"),
                                reactorNames = listOf("Cindy", "Artin")
                            ),
                            MessageReactionSummary(
                                emoji = "👍",
                                count = 1,
                                reactorIds = listOf("u1"),
                                reactorNames = listOf("Artin")
                            )
                        )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
        assertEquals(listOf("👍", "🔥"), dataSource.cachedPayload("channel-1")?.messages?.single()?.reactions?.map { it.emoji })
        scope.cancel()
    }
    @Test
    fun applyMessageReactionsKeepsVisibleReactionOrder() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val initialMessage =
            contentPayload("reactable body").messages.single().copy(
                reactions =
                    listOf(
                        RaftThreadReactionItem("👍", count = 1, selected = true),
                        RaftThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("reactable body").copy(messages = listOf(initialMessage)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyMessageReactions(
                "m1",
                listOf(
                    RaftThreadReactionItem(
                        "🔥",
                        count = 2,
                        selected = true,
                        reactorIds = listOf("u2", "u3"),
                        reactorNames = listOf("Cindy", "Artin")
                    ),
                    RaftThreadReactionItem(
                        "👍",
                        count = 1,
                        selected = true,
                        reactorIds = listOf("u1"),
                        reactorNames = listOf("Artin")
                    )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
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
