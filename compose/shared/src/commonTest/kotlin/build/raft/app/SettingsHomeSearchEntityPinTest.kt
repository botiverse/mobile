package build.raft.app

import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.settings.SearchEntityPinTarget
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SearchEntityType
import build.raft.app.settings.dispatchSearchEntityPinMenuSelection
import build.raft.app.settings.searchEntityPinTarget
import build.raft.app.thread.ThreadConversationPinTarget
import build.raft.app.thread.key
import build.raft.app.thread.toUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
