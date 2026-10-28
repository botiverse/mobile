package build.raft.app.data

import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Task v2: the enter snapshot of a task conversation (`GET /channels/{conv}` → `type: "task"` +
 * `parentChannelId`, then `GET /messages/channel/{conv}`; botiverse/slock `routes/channels.ts`,
 * `routes/messages.ts` @ 95703ec22e319f526dedf6c899751929b71062d7) is a `task` window keyed by the
 * conversation, converted under the task's channel. No reply-count lookup: a task conversation's
 * messages never host threads.
 */
class CanonicalMessageV2TaskConversationSnapshotTest {
    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)

    @Test
    fun taskConversationSnapshotLoadsAsATaskWindowUnderItsParentChannel() = runTest {
        val network =
            RecordingNetworkClient(
                pageBody = canonicalV2TestPage(channelId = "conv-1"),
                channelIds = setOf("conv-1"),
                channelType = "task",
                parentChannelId = "channel-1",
            )

        // Phase 2b: the kind (and a task's parent) comes from `channels/{id}` only when the page cannot name it.
        val fetcher = CanonicalMessageV2RestSnapshotFetcher(network)
        assertEquals("task" to "channel-1", fetcher.topLevelKind(partition, "conv-1"))
        val page = assertNotNull(enteredPageFromBody("conv-1", canonicalV2TestPage(channelId = "conv-1")))
        val rows = fetcher.convertPageRows(partition, "conv-1", "task", page.rawMessages, taskParentChannelId = "channel-1")

        val context = assertIs<CanonicalConversationContextV2.TopLevel>(rows.envelopes.single().context)
        assertEquals(TopLevelScopeRefV2.Task(partition.serverId, "conv-1", "channel-1"), context.scope)
        assertEquals(listOf("channels/conv-1"), network.paths)
    }

    @Test
    fun taskConversationWithoutItsParentChannelIsNotPersisted() = runTest {
        val network =
            RecordingNetworkClient(
                pageBody = canonicalV2TestPage(channelId = "conv-1"),
                channelIds = setOf("conv-1"),
                channelType = "task",
            )

        assertEquals(null, CanonicalMessageV2RestSnapshotFetcher(network).topLevelKind(partition, "conv-1"))
        assertFalse("messages/channel/conv-1" in network.paths)
    }
}
