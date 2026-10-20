package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two legal account-switch interleavings around reply access validation.
 * Explicit admission barriers are required because the access flight and parent-context
 * resolution run in independently owned production coroutine scopes.
 */
class SharedReplyThreadAccessAuthorityTest {
    @Test
    fun authorityChangeBeforeAccessSnapshotCompletionFailsClosedWithoutScopedWrite() = runBlocking {
        val authStore = InMemoryRaftAuthSessionStore(accountSession("account-1", "server-1"))
        val messageStore = SharedThreadMessageStore()
        val releaseReply = CompletableDeferred<Unit>()
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot().copy(parentMessage = null)),
                releaseReply = releaseReply,
            )
        val dataSource = replyThreadAccessDataSource(authStore, messageStore, repository)
        val parentMessageId = MessageId("m1")
        val accountOneScopeId = captureMessageStoreScopeId(dataSource)

        val fetch = async { dataSource.fetchThreadPayload(unresolvedReplyThreadAccessPayload(), persistResult = false) }
        repository.replyStarted.await()
        val accountTwoScopeId = publishAccountSwitch(dataSource, authStore)
        assertTrue(accountOneScopeId != accountTwoScopeId)
        releaseReply.complete(Unit)

        val result = assertIs<DataResult.Error>(fetch.await())
        assertEquals("thread_reply_access_authority_changed", result.error.code)
        assertTrue(result.error.retryable)
        assertNull(messageStore.getScoped(accountOneScopeId, ChannelId("channel-1"), parentMessageId))
        assertNull(messageStore.getScoped(accountTwoScopeId, ChannelId("channel-1"), parentMessageId))
    }

    @Test
    fun parentCompletionAfterOwnerChangeFailsClosedAndWritesOnlyToCapturedIdentity() = runBlocking {
        val authStore = InMemoryRaftAuthSessionStore(accountSession("account-1", "server-1"))
        val messageStore = SharedThreadMessageStore()
        val releaseParent = CompletableDeferred<Unit>()
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult =
                    DataResult.Content(
                        replyThreadAccessSnapshot(body = "account one reply").copy(parentMessage = null)
                    ),
                parentResult = DataResult.Content(replyThreadAccessSnapshot()),
                releaseParent = releaseParent,
            )
        val dataSource = replyThreadAccessDataSource(authStore, messageStore, repository)
        val accountOneScopeId = captureMessageStoreScopeId(dataSource)
        val accessValidated = CompletableDeferred<Unit>()
        val releaseAccessValidation = CompletableDeferred<Unit>()
        dataSource.onReplyThreadAccessValidatedForTest = {
            accessValidated.complete(Unit)
            releaseAccessValidation.await()
        }

        val fetch = async { dataSource.fetchThreadPayload(unresolvedReplyThreadAccessPayload(), persistResult = false) }
        repository.parentStarted.await()
        accessValidated.await()
        val accountTwoScopeId = publishAccountSwitch(dataSource, authStore)
        assertTrue(accountOneScopeId != accountTwoScopeId)
        releaseAccessValidation.complete(Unit)
        releaseParent.complete(Unit)

        val result = assertIs<DataResult.Error>(fetch.await())
        assertEquals("thread_reply_cache_provenance_stale", result.error.code)
        assertEquals(false, result.error.retryable)
        val parentMessageId = MessageId("m1")
        assertEquals(
            "parent body",
            messageStore.getScoped(
                accountOneScopeId,
                ChannelId("channel-1"),
                parentMessageId,
            )?.body,
        )
        assertNull(
            messageStore.getScoped(
                accountTwoScopeId,
                ChannelId("channel-1"),
                parentMessageId,
            )
        )
    }

    private fun replyThreadAccessDataSource(
        sessionStore: RaftAuthSessionStore,
        messageStore: SharedThreadMessageStore,
        repository: ReplyThreadAccessConversationRepositoryRecorder,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(),
            sessionStore = sessionStore,
            conversationRepository = repository,
            messageStore = messageStore,
            cacheEnvironmentIdProvider = { EnvironmentId },
            replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, EnvironmentId),
        )

    private suspend fun publishAccountSwitch(
        dataSource: SharedThreadDataSource,
        authStore: InMemoryRaftAuthSessionStore,
    ): String {
        authStore.saveSession(accountSession("account-2", "server-2"))
        return captureMessageStoreScopeId(dataSource)
    }

    private suspend fun captureMessageStoreScopeId(dataSource: SharedThreadDataSource): String =
        requireNotNull(dataSource.captureReplyCacheProducerContext())
            .provenance
            .messageStoreScopeId
            ?: error("reply access authority must expose a shared message-store scope")

    private fun accountSession(userId: String, serverId: String) =
        authSessionFixture(userId = userId, selectedServerId = serverId)

    private companion object {
        const val EnvironmentId = "https://prod.example.test"
    }
}
