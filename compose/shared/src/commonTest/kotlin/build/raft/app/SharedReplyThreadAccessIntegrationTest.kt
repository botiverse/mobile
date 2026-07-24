package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedReplyThreadAccessIntegrationTest {
    @Test
    fun unresolvedReentryAcrossPageDataSourcesJoinsOneAppScopeFlight() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
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
                sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                payloadStore = payloadStore
            )
        val base = unresolvedReplyThreadAccessPayload()
        val first = async { dataSource(firstRepository).fetchThreadPayload(base, persistResult = false) }
        runCurrent()
        assertTrue(firstRepository.replyStarted.isCompleted)

        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                dataSource(secondRepository).fetchThreadPayload(base, persistResult = false)
            }
        runCurrent()

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), firstRepository.replyRequests)
        assertTrue(secondRepository.replyRequests.isEmpty())
        release.complete(Unit)
        advanceUntilIdle()

        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(first.await())
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(second.await())
        assertTrue(secondRepository.replyRequests.isEmpty())
    }

    @Test
    fun accessFlightFailsClosedWhenAuthBindingChangesDuringFetch() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        val sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1"))
        val release = CompletableDeferred<Unit>()
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                releaseReply = release
            )
        val dataSource = replyThreadAccessDataSource(repository, sessionStore, payloadStore)
        val base = unresolvedReplyThreadAccessPayload()
        val first = async { dataSource.fetchThreadPayload(base, persistResult = false) }
        runCurrent()
        assertTrue(repository.replyStarted.isCompleted)

        sessionStore.updateTokens(
            accessToken = "rotated-access",
            refreshToken = "rotated-refresh"
        )
        release.complete(Unit)
        advanceUntilIdle()

        val firstResult = assertIs<DataResult.Error>(first.await())
        assertEquals("thread_reply_access_authority_changed", firstResult.error.code)

        val successor = async { dataSource.fetchThreadPayload(base, persistResult = false) }
        advanceUntilIdle()
        assertIs<DataResult.Content<KuiklyThreadScreenPayload>>(successor.await())
        assertEquals(2, repository.replyRequests.size)
    }

    @Test
    fun failureReentryRendersFailClosedErrorAndExplicitRetryStartsOneSuccessor() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
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
                sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                payloadStore = payloadStore
            )
        val failed = async { failingDataSource.fetchThreadPayload(base, persistResult = false) }
        advanceUntilIdle()
        assertIs<DataResult.Error>(failed.await())
        assertEquals("thread_reply_network", failingDataSource.rememberedReplyThreadAccessFailure(base)?.code)

        val retryRepository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "retry body"))
            )
        val retryDataSource =
            replyThreadAccessDataSource(
                repository = retryRepository,
                sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                payloadStore = payloadStore
            )
        val store = SharedThreadStore(dataSource = retryDataSource, scope = backgroundScope)

        store.accept(SharedThreadIntent.Start(base))
        runCurrent()

        assertEquals(ThreadAccessResolution.Error, store.state.value.accessResolution)
        assertFalse(store.state.value.canonicalAccessResolved)
        assertEquals("thread_reply_network", store.state.value.payload.errorCode)
        assertTrue(retryRepository.replyRequests.isEmpty())

        store.accept(SharedThreadIntent.Refresh)
        advanceUntilIdle()

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), retryRepository.replyRequests)
        assertNull(retryDataSource.rememberedReplyThreadAccessFailure(base))
    }

    private fun replyThreadAccessDataSource(
        repository: ConversationRepository,
        sessionStore: SlockAuthSessionStore,
        payloadStore: SharedThreadPayloadStore
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            payloadStore = payloadStore,
            cacheEnvironmentIdProvider = { "https://prod.example.test" }
        )
}
