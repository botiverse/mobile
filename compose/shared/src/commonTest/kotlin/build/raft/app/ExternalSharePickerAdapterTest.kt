package build.raft.app

import build.raft.app.data.ExternalShareEnvelope
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExternalSharePickerAdapterTest {
    @Test
    fun localDebouncedQueryKeepsTextButDisablesStaleRowsUntilStoreCatchesUp() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 7)

        val pending = selecting.toExternalSharePickerSurface(queryOverride = "search")

        assertEquals("search", pending.picker.query)
        assertEquals(RaftForwardPickerLoadState.Loading, pending.picker.loadState)
        assertFalse(pending.picker.interactive)
    }

    @Test
    fun staleOrTamperedRowTapFailsClosed() {
        val first = target("channel-a")
        val selecting = state(targets = listOf(first), generation = 9)
        val rendered = first.toRaftForwardTargetPickerItem()

        assertNull(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 8)
            )
        )
        assertNull(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(
                    rendered.copy(payload = target("channel-other")),
                    renderGeneration = 9
                )
            )
        )
        assertIs<ExternalShareIntent.ToggleTarget>(
            selecting.toExternalShareIntent(
                RaftForwardTargetPickerIntent.TargetSelected(rendered, renderGeneration = 9)
            )
        )
    }

    @Test
    fun concreteTargetMapsToTypedConversation() {
        val destination = target("channel-a")
        val identity = destination.toExternalShareConversationIdentityOrNull()
            ?: error("expected identity")
        assertEquals(ChannelId("channel-a"), identity.channelId)
        assertEquals(destination.channelKind, identity.kind)
    }

    private fun state(
        targets: List<ForwardMessageTarget>,
        selected: List<ForwardMessageTarget> = emptyList(),
        generation: Int
    ): ExternalShareState.SelectingTargets =
        ExternalShareState.SelectingTargets(
            envelope = ExternalShareEnvelope("token", 1L, "android", text = "hello"),
            accountId = "account",
            serverId = "server",
            targets = targets,
            selectedTargets = selected,
            requestGeneration = generation
        )

    private fun target(id: String) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId(id)
        )
}
