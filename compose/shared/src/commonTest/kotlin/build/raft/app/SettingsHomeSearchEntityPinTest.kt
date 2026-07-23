package build.raft.app

import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsHomeSearchEntityPinTest {
    @Test
    fun onlyActiveChannelsExposePinConversationId() {
        assertEquals("channel-1", entity(SearchEntityType.Channel).let(::searchEntityPinConversationId))
        assertNull(entity(SearchEntityType.Channel, archived = true).let(::searchEntityPinConversationId))
        assertNull(entity(SearchEntityType.Channel, threadId = " ").let(::searchEntityPinConversationId))
        assertNull(entity(SearchEntityType.DirectMessage).let(::searchEntityPinConversationId))
        assertNull(entity(SearchEntityType.Human).let(::searchEntityPinConversationId))
        assertNull(entity(SearchEntityType.Agent).let(::searchEntityPinConversationId))
    }

    @Test
    fun pinMenuSelectionDispatchesExactlyOneCanonicalToggle() {
        var pinCount = 0
        var toggledId: String? = null

        dispatchSearchEntityPinMenuSelection(
            actionId = "toggle-pin",
            conversationId = "channel-1",
            onTogglePin = {
                pinCount += 1
                toggledId = it
            }
        )
        assertEquals(1, pinCount)
        assertEquals("channel-1", toggledId)
    }

    @Test
    fun unknownOrDisabledPinMenuSelectionDoesNothing() {
        var pinCount = 0

        dispatchSearchEntityPinMenuSelection(
            actionId = "other",
            conversationId = "channel-1",
            onTogglePin = { pinCount += 1 }
        )
        dispatchSearchEntityPinMenuSelection(
            actionId = "toggle-pin",
            conversationId = "channel-1",
            onTogglePin = null
        )

        assertEquals(0, pinCount)
    }

    @Test
    fun channelSettingsPinTargetUsesCanonicalChannelIdentityOnly() {
        val channel =
            KuiklyThreadScreenPayload(
                instanceId = "channel",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "thread-1",
                streamChannelId = "stream-1"
            )
        val dm = channel.copy(surfaceKind = KuiklyThreadSurfaceKind.DirectMessage)
        val reply = channel.copy(surfaceKind = KuiklyThreadSurfaceKind.ReplyThread)

        assertEquals("stream-1", channel.threadChannelPinTargetId(null))
        assertNull(dm.threadChannelPinTargetId(null))
        assertNull(reply.threadChannelPinTargetId(null))
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

    private fun entity(
        type: SearchEntityType,
        threadId: String? = "channel-1",
        archived: Boolean = false
    ): SearchEntityResult =
        SearchEntityResult(
            key = "entity:$type",
            type = type,
            title = "Title",
            subtitle = "Subtitle",
            threadId = threadId,
            memberId = null,
            conversationIdentity = null,
            archived = archived
        )
}
