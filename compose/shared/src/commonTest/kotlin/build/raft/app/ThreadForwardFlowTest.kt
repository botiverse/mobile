package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.ForwardMessageExecuteRequest
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ThreadForwardFlowTest {
    @Test
    fun pickerExcludesSourceConversationAndKeepsOtherDestinationKinds() {
        val source = ChannelId("channel-source")
        val targets =
            listOf(
                target("source", ForwardMessageTargetKind.RecentChannel, source),
                target("channel", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-other")),
                target("dm", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("dm-other")),
                ForwardMessageTarget(
                    kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                    id = "agent-1",
                    title = "Agent One",
                    memberId = MemberId("agent-1")
                )
            )

        assertEquals(
            listOf("channel", "dm", "agent-1"),
            targets.threadForwardPickerTargets(source).map { it.id }
        )
    }

    @Test
    fun singleTargetForwardPreservesMergedSelectionAndNote() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForward(
                repository = repository,
                sourceChannelId = ChannelId("source"),
                target = selected,
                messageIds = listOf("message-1", "message-2"),
                mode = ForwardMessageSendMode.Merged,
                note = "Selected note"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(1, repository.requests.size)
        val request = repository.requests.single()
        assertEquals(ChannelId("channel-selected"), request.targetChannelId)
        assertEquals(listOf("message-1", "message-2"), request.messageIds)
        assertEquals(ForwardMessageSendMode.Merged, request.mode)
        assertEquals("Selected note", request.note)
    }

    @Test
    fun singleTargetForwardReportsFailureWithoutRetrying() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("channel-selected"))
        val repository =
            RecordingForwardRepository(
                forwardResults =
                    mapOf(
                        ChannelId("channel-selected") to
                            DataResult.Error(ScreenError(code = "selected_failed", retryable = true))
                    )
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                sourceChannelId = ChannelId("source"),
                target = selected,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("selected_failed", outcome.failureCode)
        assertEquals(listOf(ChannelId("channel-selected")), repository.requests.map { it.targetChannelId })
    }

    @Test
    fun candidateResolvingToSourceConversationIsRejectedWithoutForwardRequest() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                kind = ForwardMessageTargetKind.HumanDirectMessageCandidate,
                id = "user-source",
                title = "Source peer",
                memberId = MemberId("user-source")
            )
        val sourceChannelId = ChannelId("dm-source")
        val repository =
            RecordingForwardRepository(
                resolvedChannels = mapOf(candidate.id to sourceChannelId)
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                sourceChannelId = sourceChannelId,
                target = candidate,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_source_target_forbidden", outcome.failureCode)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun candidateResolvingToDifferentConversationForwardsExactlyOnce() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-other",
                title = "Other agent",
                memberId = MemberId("agent-other")
            )
        val resolvedChannelId = ChannelId("dm-other")
        val repository =
            RecordingForwardRepository(
                resolvedChannels = mapOf(candidate.id to resolvedChannelId)
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                sourceChannelId = ChannelId("dm-source"),
                target = candidate,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "Review this"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(listOf(resolvedChannelId), repository.requests.map { it.targetChannelId })
    }

    private fun target(
        id: String,
        kind: ForwardMessageTargetKind,
        channelId: ChannelId
    ): ForwardMessageTarget =
        ForwardMessageTarget(
            kind = kind,
            id = id,
            title = id,
            channelId = channelId,
            canForwardNow = true
        )
}

private class RecordingForwardRepository(
    private val resolvedChannels: Map<String, ChannelId> = emptyMap(),
    private val forwardResults: Map<ChannelId, DataResult<Unit>> = emptyMap()
) : ForwardMessageTargetRepository {
    val requests = mutableListOf<ForwardMessageExecuteRequest>()

    override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
        DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun searchTargets(
        serverId: ServerId,
        query: String,
        limit: Int
    ): DataResult<List<ForwardMessageTarget>> = DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun resolveTargetChannel(
        target: ForwardMessageTarget,
        allowJoin: Boolean
    ): DataResult<ChannelId> =
        resolvedChannels[target.id]?.let { DataResult.Content(it) }
            ?: target.channelId?.let { DataResult.Content(it) }
            ?: DataResult.Error(ScreenError(code = "missing_channel", retryable = false))

    override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit> {
        requests += request
        return forwardResults[request.targetChannelId] ?: DataResult.Content(Unit)
    }
}
