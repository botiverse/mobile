package build.raft.app

import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsHomeSearchEntityPinTest {
    @Test
    fun activeChannelDmHumanAndAgentExposeTypedPinTargets() {
        assertEquals(
            SearchEntityPinTarget("channel-1", "channel", "channel-1"),
            entity(SearchEntityType.Channel).let(::searchEntityPinTarget)
        )
        assertEquals(
            SearchEntityPinTarget("channel-1"),
            entity(SearchEntityType.DirectMessage).let(::searchEntityPinTarget)
        )
        assertEquals(
            SearchEntityPinTarget("channel-1", "human", "member-1"),
            entity(SearchEntityType.Human).let(::searchEntityPinTarget)
        )
        assertEquals(
            SearchEntityPinTarget("member-1", "agent", "member-1"),
            entity(SearchEntityType.Agent, threadId = null).let(::searchEntityPinTarget)
        )
    }

    @Test
    fun archivedOrUnresolvedEntitiesDoNotExposePinTargets() {
        assertNull(entity(SearchEntityType.Channel, archived = true).let(::searchEntityPinTarget))
        assertNull(entity(SearchEntityType.Channel, threadId = " ").let(::searchEntityPinTarget))
        assertNull(entity(SearchEntityType.DirectMessage, threadId = null).let(::searchEntityPinTarget))
        assertNull(entity(SearchEntityType.Human, memberId = null).let(::searchEntityPinTarget))
        assertNull(entity(SearchEntityType.Agent, memberId = " ").let(::searchEntityPinTarget))
    }

    @Test
    fun pinMenuSelectionDispatchesExactlyOneCanonicalToggle() {
        var pinCount = 0
        var toggledTarget: SearchEntityPinTarget? = null
        val target = SearchEntityPinTarget("dm-1", "agent", "agent-1")

        dispatchSearchEntityPinMenuSelection(
            actionId = "toggle-pin",
            target = target,
            onTogglePin = {
                pinCount += 1
                toggledTarget = it
            }
        )
        assertEquals(1, pinCount)
        assertEquals(target, toggledTarget)
    }

    @Test
    fun unknownOrDisabledPinMenuSelectionDoesNothing() {
        var pinCount = 0

        dispatchSearchEntityPinMenuSelection(
            actionId = "other",
            target = SearchEntityPinTarget("channel-1"),
            onTogglePin = { pinCount += 1 }
        )
        dispatchSearchEntityPinMenuSelection(
            actionId = "toggle-pin",
            target = SearchEntityPinTarget("channel-1"),
            onTogglePin = null
        )

        assertEquals(0, pinCount)
    }

    @Test
    fun threadSettingsPinTargetUsesCanonicalChannelAndDmPeerIdentity() {
        val channel =
            RaftThreadScreenPayload(
                instanceId = "channel",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "thread-1",
                streamChannelId = "stream-1"
            )
        val dm = channel.copy(surfaceKind = RaftThreadSurfaceKind.DirectMessage)
        val reply = channel.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread)
        val agentProjection = dmProjection(MemberKind.Agent, "agent-1")
        val humanProjection = dmProjection(MemberKind.Human, "human-1")

        assertEquals(
            ThreadConversationPinTarget("stream-1", "channel", "stream-1"),
            channel.threadConversationPinTarget(null)
        )
        assertEquals(
            ThreadConversationPinTarget("dm-canonical", "agent", "agent-1"),
            dm.threadConversationPinTarget(agentProjection)
        )
        assertEquals(
            ThreadConversationPinTarget("dm-canonical", "human", "human-1"),
            dm.threadConversationPinTarget(humanProjection)
        )
        assertNull(reply.threadConversationPinTarget(agentProjection))
    }

    @Test
    fun dmPinStateMatchesTypedAndLegacyCanonicalPins() {
        val target = ThreadConversationPinTarget("dm-canonical", "human", "human-1")

        assertTrue(
            target.toUiState(
                SettingsServerSidebarOrderSnapshot(
                    pinned = listOf(SettingsServerSidebarPinnedItem("human", "human-1"))
                )
            ).pinned
        )
        assertTrue(
            target.toUiState(
                SettingsServerSidebarOrderSnapshot(pinnedOrder = listOf("dm-canonical"))
            ).pinned
        )
        assertFalse(
            target.toUiState(
                SettingsServerSidebarOrderSnapshot(
                    pinned = listOf(SettingsServerSidebarPinnedItem("agent", "human-1"))
                )
            ).pinned
        )
        assertFalse(target.toUiState(null).available)
    }

    @Test
    fun dmHeaderAlwaysShowsSettingsAndMovesMuteOutOfInlineActions() {
        assertTrue(
            threadHeaderSettingsActionVisible(
                payloadShowSettingsAction = false,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage
            )
        )
        assertFalse(threadHeaderInlineActivityMuteVisible(RaftThreadSurfaceKind.DirectMessage))
        assertTrue(threadHeaderInlineActivityMuteVisible(RaftThreadSurfaceKind.Channel))
        assertFalse(
            threadHeaderSettingsActionVisible(
                payloadShowSettingsAction = false,
                surfaceKind = RaftThreadSurfaceKind.Channel
            )
        )
        assertFalse(
            threadSettingsUsesStandalonePage(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                newDesignSettingsEnabled = true
            )
        )
        assertTrue(
            threadSettingsUsesStandalonePage(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                newDesignSettingsEnabled = true
            )
        )
    }

    @Test
    fun pinnedChannelProjectionUsesCanonicalTypedResolver() {
        val order =
            SettingsServerSidebarOrderSnapshot(
                pinned =
                    listOf(
                        SettingsServerSidebarPinnedItem("channel", "channel-2"),
                        SettingsServerSidebarPinnedItem("agent", "agent-1"),
                        SettingsServerSidebarPinnedItem("CHANNEL", "channel-1"),
                        SettingsServerSidebarPinnedItem("channel", " ")
                    ),
                pinnedChannelIds = listOf("legacy-channel")
            )

        assertEquals(setOf("channel-2", "channel-1"), order.pinnedChannelConversationIds())
    }

    @Test
    fun typedPinnedProjectionMatchesChannelDmHumanAndAgentRows() {
        val pinned =
            listOf(
                SettingsServerSidebarPinnedItem("channel", "channel-1"),
                SettingsServerSidebarPinnedItem("human", "human-1"),
                SettingsServerSidebarPinnedItem("agent", "agent-1")
            )

        assertTrue(entity(SearchEntityType.Channel).isPinnedBy(pinned))
        assertTrue(entity(SearchEntityType.DirectMessage, memberId = "human-1").isPinnedBy(pinned))
        assertTrue(entity(SearchEntityType.Human, threadId = null, memberId = "human-1").isPinnedBy(pinned))
        assertTrue(entity(SearchEntityType.Agent, threadId = null, memberId = "agent-1").isPinnedBy(pinned))
    }

    private fun entity(
        type: SearchEntityType,
        threadId: String? = "channel-1",
        memberId: String? = "member-1",
        archived: Boolean = false
    ): SearchEntityResult =
        SearchEntityResult(
            key = "entity:$type",
            type = type,
            title = "Title",
            subtitle = "Subtitle",
            threadId = threadId,
            memberIdentity =
                memberId?.let {
                    MemberDetailIdentity(
                        memberId = MemberId(it),
                        kind = if (type == SearchEntityType.Agent) MemberKind.Agent else MemberKind.Human,
                        sourceServerId = ServerId("server-1"),
                    )
                },
            conversationIdentity = null,
            archived = archived
        )

    private fun dmProjection(kind: MemberKind, memberId: String): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-1",
            canonicalChannelId = "dm-canonical",
            aliases = setOf("dm-canonical", "stream-1"),
            conversationKind = ConversationKind.DirectMessage,
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            channelKind = "direct_message",
            title = "Peer",
            peerIdentity =
                MemberDetailIdentity(
                    memberId = MemberId(memberId),
                    kind = kind,
                    sourceServerId = ServerId("server-1")
                )
        )
}
