package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalShareStoreTest {
    @Test
    fun multiSelectContinuesToConfirmationAndEnforcesLimit() = runBlocking {
        val targets = listOf(target("one"), target("two"), target("three"))
        val store = store(scope = this, targets = targets, maximumTargetCount = 2)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        store.dispatch(ExternalShareIntent.ToggleTarget(targets[0]))
        store.dispatch(ExternalShareIntent.ToggleTarget(targets[1]))
        store.dispatch(ExternalShareIntent.ToggleTarget(targets[2]))

        val selecting = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals(listOf("one", "two"), selecting.selectedTargets.map { it.id })
        assertEquals("external_share_target_limit", selecting.selectionErrorCode)

        store.dispatch(ExternalShareIntent.Continue)
        val confirming = assertIs<ExternalShareState.Confirming>(store.state.value)
        assertEquals(listOf("one", "two"), confirming.selectedTargets.map { it.id })
        store.clear()
    }

    @Test
    fun partialFailureRetryNeverResendsSuccessfulTarget() = runBlocking {
        val targets = listOf(target("success"), target("retry"))
        val calls = mutableListOf<String>()
        var retryCalls = 0
        val sender =
            object : ExternalShareSender {
                override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> {
                    calls += request.target.id
                    return if (request.target.id == "retry" && retryCalls++ == 0) {
                        DataResult.Error(ScreenError("network_error"))
                    } else {
                        DataResult.Content(Unit)
                    }
                }
            }
        val store = store(scope = this, targets = targets, sender = sender)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        targets.forEach { store.dispatch(ExternalShareIntent.ToggleTarget(it)) }
        store.dispatch(ExternalShareIntent.Continue)
        store.dispatch(ExternalShareIntent.ChangeComment("Take a look"))
        store.dispatch(ExternalShareIntent.Send)
        yield()

        val first = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("success"), first.sentTargets.map { it.id })
        assertEquals(listOf("retry"), first.failedTargets.map { it.id })
        assertEquals(listOf("success", "retry"), calls)

        store.dispatch(ExternalShareIntent.RetryFailed)
        yield()
        val second = assertIs<ExternalShareState.Completed>(store.state.value)
        assertEquals(listOf("success", "retry"), second.sentTargets.map { it.id })
        assertTrue(second.failedTargets.isEmpty())
        assertEquals(listOf("success", "retry", "retry"), calls)
        store.clear()
    }

    @Test
    fun canceledOlderSearchCannotOverwriteNewerResults() = runBlocking {
        val oldResult = CompletableDeferred<DataResult<List<ForwardMessageTarget>>>()
        val newResult = CompletableDeferred<DataResult<List<ForwardMessageTarget>>>()
        val repository =
            object : StubTargetRepository(emptyList()) {
                override suspend fun searchTargets(
                    serverId: ServerId,
                    query: String,
                    limit: Int
                ): DataResult<List<ForwardMessageTarget>> =
                    when (query) {
                        "old" -> oldResult.await()
                        else -> newResult.await()
                    }
            }
        val store = store(scope = this, targetRepository = repository)

        store.dispatch(ExternalShareIntent.Load("share-1", Server.value))
        yield()
        store.dispatch(ExternalShareIntent.Search("old"))
        yield()
        store.dispatch(ExternalShareIntent.Search("new"))
        yield()
        newResult.complete(DataResult.Content(listOf(target("new"))))
        yield()
        oldResult.complete(DataResult.Content(listOf(target("old"))))
        yield()

        val state = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals("new", state.query)
        assertEquals(listOf("new"), state.targets.map { it.id })
        store.clear()
    }

    @Test
    fun invalidEnvelopeIdentityFailsClosedBeforeTargetLoad() = runBlocking {
        var envelopeCalls = 0
        var targetCalls = 0
        val store =
            ExternalShareStore(
                envelopeRepository =
                    object : ExternalShareEnvelopeRepository {
                        override suspend fun load(token: String): DataResult<ExternalShareEnvelope> {
                            envelopeCalls += 1
                            return DataResult.Content(envelope())
                        }

                        override suspend fun delete(token: String) = Unit
                    },
                targetRepository =
                    object : StubTargetRepository(emptyList()) {
                        override suspend fun recentTargets(
                            serverId: ServerId,
                            limit: Int
                        ): DataResult<List<ForwardMessageTarget>> {
                            targetCalls += 1
                            return super.recentTargets(serverId, limit)
                        }
                    },
                sender = SuccessfulSender,
                scope = this
            )

        store.dispatch(ExternalShareIntent.Load("", Server.value))

        val state = assertIs<ExternalShareState.Error>(store.state.value)
        assertEquals("external_share_identity_invalid", state.errorCode)
        assertEquals(0, envelopeCalls)
        assertEquals(0, targetCalls)
        store.clear()
    }

    private fun store(
        scope: CoroutineScope,
        targets: List<ForwardMessageTarget> = emptyList(),
        targetRepository: ForwardMessageTargetRepository = StubTargetRepository(targets),
        sender: ExternalShareSender = SuccessfulSender,
        maximumTargetCount: Int = ExternalShareMaximumTargetCount
    ): ExternalShareStore =
        ExternalShareStore(
            envelopeRepository =
                object : ExternalShareEnvelopeRepository {
                    override suspend fun load(token: String): DataResult<ExternalShareEnvelope> =
                        DataResult.Content(envelope(token))

                    override suspend fun delete(token: String) = Unit
                },
            targetRepository = targetRepository,
            sender = sender,
            scope = scope,
            maximumTargetCount = maximumTargetCount
        )

    private fun envelope(token: String = "share-1"): ExternalShareEnvelope =
        ExternalShareEnvelope(
            token = token,
            createdAtEpochMillis = 1L,
            sourcePlatform = "test",
            text = "Shared text"
        )

    private fun target(id: String): ForwardMessageTarget =
        ForwardMessageTarget(
            serverId = Server,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId("channel-$id"),
            channelKind = ConversationKind.Channel
        )

    private open class StubTargetRepository(
        private val targets: List<ForwardMessageTarget>
    ) : ForwardMessageTargetRepository {
        override suspend fun recentTargets(
            serverId: ServerId,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> = DataResult.Content(targets)

        override suspend fun searchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> = DataResult.Content(targets)

        override suspend fun resolveTargetChannel(
            sourceServerId: ServerId,
            target: ForwardMessageTarget,
            allowJoin: Boolean
        ): DataResult<ForwardMessageDestination> =
            DataResult.Content(
                ForwardMessageDestination(
                    serverId = target.serverId,
                    channelId = requireNotNull(target.channelId),
                    channelKind = target.channelKind
                )
            )

        override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit> =
            DataResult.Content(Unit)
    }

    private object SuccessfulSender : ExternalShareSender {
        override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> =
            DataResult.Content(Unit)
    }

    private companion object {
        val Server = ServerId("server-1")
    }
}
