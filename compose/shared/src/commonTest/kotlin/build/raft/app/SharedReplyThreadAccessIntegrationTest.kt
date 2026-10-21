package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedReplyThreadAccessIntegrationTest {
    @Test
    fun unresolvedReentryAcrossPageDataSourcesJoinsOneAppScopeFlight() = runBlocking {
        withPortableAppScope { appScope ->
            val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
            val release = CompletableDeferred<Unit>()
            val firstRepository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                    releaseReply = release
                )
            val secondRepository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult =
                        DataResult.Error(
                            ScreenError(code = "second_page_must_not_start", retryable = false)
                        )
                )
            fun dataSource(repository: ConversationRepository) =
                replyThreadAccessDataSource(
                    repository = repository,
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = payloadStore
                )
            val base = unresolvedReplyThreadAccessPayload()
            val first = async { dataSource(firstRepository).fetchThreadPayload(base, persistResult = false) }
            firstRepository.replyStarted.await()

            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    dataSource(secondRepository).fetchThreadPayload(base, persistResult = false)
                }

            assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), firstRepository.replyRequests)
            assertTrue(secondRepository.replyRequests.isEmpty())
            release.complete(Unit)

            assertIs<DataResult.Content<RaftThreadScreenPayload>>(first.await())
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(second.await())
            assertTrue(secondRepository.replyRequests.isEmpty())
        }
    }

    @Test
    fun accessFlightFailsClosedWhenAuthBindingChangesDuringFetch() = runBlocking {
        withPortableAppScope { appScope ->
            val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
            val sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1"))
            val release = CompletableDeferred<Unit>()
            val repository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                    releaseReply = release
                )
            val dataSource = replyThreadAccessDataSource(repository, sessionStore, payloadStore)
            val base = unresolvedReplyThreadAccessPayload()
            val first = async { dataSource.fetchThreadPayload(base, persistResult = false) }
            repository.replyStarted.await()

            sessionStore.updateTokens(
                accessToken = "rotated-access",
                refreshToken = "rotated-refresh"
            )
            release.complete(Unit)

            val firstResult = assertIs<DataResult.Error>(first.await())
            assertEquals("thread_reply_access_authority_changed", firstResult.error.code)

            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.fetchThreadPayload(base, persistResult = false)
            )
            assertEquals(2, repository.replyRequests.size)
        }
    }

    @Test
    fun loadedRepliesDoNotWaitForHungParentContextProducerAndOriginStaysLoading() = runBlocking {
        withPortableAppScope { appScope ->
            val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
            val replyRelease = CompletableDeferred<Unit>()
            val parentRelease = CompletableDeferred<Unit>()
            val diagnostics = mutableListOf<String>()
            val repository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult =
                        DataResult.Content(
                            replyThreadAccessSnapshot().copy(parentMessage = null)
                        ),
                    releaseReply = replyRelease,
                    releaseParent = parentRelease
                )
            val dataSource =
                replyThreadAccessDataSource(
                    repository = repository,
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = payloadStore,
                    replyOriginHydrationGraceMillis = 0L,
                    diagnosticsLogger = diagnostics::add
                )

            val store =
                SharedThreadStore(
                    dataSource = dataSource,
                    scope = appScope,
                    diagnosticsLogger = diagnostics::add,
                )

            val start =
                async {
                    store.accept(SharedThreadIntent.Start(unresolvedReplyThreadAccessPayload()))
                }
            repository.parentStarted.await()
            replyRelease.complete(Unit)
            start.await()

            assertEquals("content", store.state.value.payload.status)
            assertEquals(listOf("reply body"), store.state.value.payload.messages.map { it.body })
            assertEquals("loading", store.state.value.payload.originStatus)
            assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
            assertFalse(store.state.value.writeActionsEnabled)
            assertFalse(store.state.value.payload.joined)
            assertFalse(store.state.value.payload.canSend)
            assertFalse(store.state.value.payload.composer.enabled)
            assertNull(
                store.state.value.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = store.state.value.writeActionsEnabled
                )
            )
            assertEquals(1, repository.parentRequests.size)
            assertEquals(MessageId("m1"), repository.parentRequests.single().first)
            assertEquals(ChannelId("channel-1"), repository.parentRequests.single().second)
            assertFalse(parentRelease.isCompleted)
            assertTrue(
                diagnostics.any { line ->
                    line.startsWith("thread_parent_context_grace_expired ") &&
                        line.contains("hasReplyContent=true")
                }
            )
            assertTrue(
                diagnostics.any { line ->
                    line.contains("thread_refresh_result") &&
                        line.contains("result=content") &&
                        line.contains("messages=1")
                }
            )
        }
    }

    @Test
    fun canonicalHistorySurvivesTransientRefreshButPermissionRevocationClearsIt() = runBlocking {
        withPortableAppScope { appScope ->
            val repository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Content(replyThreadAccessSnapshot())
                )
            val dataSource =
                replyThreadAccessDataSource(
                    repository = repository,
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = appScope)

            store.accept(SharedThreadIntent.Start(unresolvedReplyThreadAccessPayload()))
            assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
            assertEquals(listOf("reply body"), store.state.value.payload.messages.map { it.body })

            repository.replyResult =
                DataResult.Error(
                    ScreenError(code = "reply_refresh_offline", retryable = true)
                )
            store.accept(SharedThreadIntent.Refresh)

            assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
            assertEquals("content", store.state.value.payload.status)
            assertEquals(listOf("reply body"), store.state.value.payload.messages.map { it.body })

            repository.replyResult =
                DataResult.NoPermission(
                    build.raft.app.data.PermissionBlocker(code = "thread_forbidden")
                )
            store.accept(SharedThreadIntent.Refresh)

            assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)
            assertEquals("no_permission", store.state.value.payload.status)
            assertTrue(store.state.value.payload.messages.isEmpty())
            assertFalse(store.state.value.writeActionsEnabled)
            assertFalse(store.state.value.payload.joined)
            assertFalse(store.state.value.payload.canSend)
            assertFalse(store.state.value.payload.composer.enabled)
            assertNull(
                store.state.value.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = store.state.value.writeActionsEnabled
                )
            )
        }
    }

    @Test
    fun readOnlyReplyContentBecomingEmptyCannotReenableParentWrites() = runBlocking {
        withPortableAppScope { appScope ->
            val repository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Content(replyThreadAccessSnapshot())
                )
            val dataSource =
                replyThreadAccessDataSource(
                    repository = repository,
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = appScope)

            store.accept(SharedThreadIntent.Start(unresolvedReplyThreadAccessPayload()))
            assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
            assertFalse(store.state.value.writeActionsEnabled)

            repository.replyResult = DataResult.Empty(build.raft.app.data.EmptyReason.NoMessages)
            store.accept(SharedThreadIntent.Refresh)

            assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
            assertEquals("empty", store.state.value.payload.status)
            assertTrue(store.state.value.payload.messages.isEmpty())
            assertFalse(store.state.value.writeActionsEnabled)
            assertFalse(store.state.value.payload.joined)
            assertFalse(store.state.value.payload.canSend)
            assertFalse(store.state.value.payload.composer.enabled)
            assertNull(
                store.state.value.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = store.state.value.writeActionsEnabled
                )
            )
        }
    }

    @Test
    fun failureReentryRendersFailClosedErrorAndExplicitRetryStartsOneSuccessor() = runBlocking {
        withPortableAppScope { appScope ->
            val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L)
            val base = unresolvedReplyThreadAccessPayload()
            val failingDataSource =
                replyThreadAccessDataSource(
                    repository =
                        ReplyThreadAccessConversationRepositoryRecorder(
                            replyResult =
                                DataResult.Error(
                                    ScreenError(code = "thread_reply_network", retryable = true)
                                )
                        ),
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = payloadStore
                )
            assertIs<DataResult.Error>(failingDataSource.fetchThreadPayload(base, persistResult = false))
            assertEquals(
                "thread_reply_network",
                failingDataSource.rememberedReplyThreadAccessFailure(base)?.code
            )

            val retryRepository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "retry body"))
                )
            val retryDataSource =
                replyThreadAccessDataSource(
                    repository = retryRepository,
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    payloadStore = payloadStore
                )
            val store = SharedThreadStore(dataSource = retryDataSource, scope = appScope)

            store.accept(SharedThreadIntent.Start(base))

            assertEquals(ThreadAccessResolution.Error, store.state.value.accessResolution)
            assertFalse(store.state.value.canonicalAccessResolved)
            assertEquals("thread_reply_network", store.state.value.payload.errorCode)
            assertFalse(store.state.value.writeActionsEnabled)
            assertFalse(store.state.value.payload.joined)
            assertFalse(store.state.value.payload.canSend)
            assertFalse(store.state.value.payload.composer.enabled)
            assertNull(
                store.state.value.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = store.state.value.writeActionsEnabled
                )
            )
            assertTrue(retryRepository.replyRequests.isEmpty())

            store.accept(SharedThreadIntent.Refresh)

            assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), retryRepository.replyRequests)
            assertNull(retryDataSource.rememberedReplyThreadAccessFailure(base))
        }
    }

    // commonTest avoids kotlinx-coroutines-test; the explicit barriers above keep these
    // tests deterministic while this independently owned scope preserves the production lifetime.
    private suspend fun <T> withPortableAppScope(block: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return try {
            block(scope)
        } finally {
            scope.cancel()
        }
    }

    private fun replyThreadAccessDataSource(
        repository: ConversationRepository,
        sessionStore: RaftAuthSessionStore,
        payloadStore: SharedThreadPayloadStore,
        replyOriginHydrationGraceMillis: Long = 300L,
        diagnosticsLogger: (String) -> Unit = {},
    ): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            payloadStore = payloadStore,
            cacheEnvironmentIdProvider = { "https://prod.example.test" },
            replyOriginHydrationGraceMillis = replyOriginHydrationGraceMillis,
            diagnosticsLogger = diagnosticsLogger,
        )
}
