package build.raft.app

import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.getRuntimeDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the @mention profile card shows for a directory profile (web `ProfilePreviewCardContent`). */
class RaftProfilePreviewCardTest {
    private val working =
        SharedServerMemberStatus(status = AgentStatus.Working, presence = AgentStatus.Working.presence, runtime = "claude", external = false)
            .toRaftAgentStatusIndicatorState()

    @Test
    fun aPersonShowsNameHandleAndDescriptionOnly() {
        val model =
            raftProfilePreviewCardModel(
                MemberProfile(id = MemberId("u1"), displayName = "Ada Lovelace", handle = "ada", kind = MemberKind.Human, description = " Designs the checklists "),
                status = working,
                readReceipt = RaftProfilePreviewReadReceipt.Read,
            )
        assertEquals(MemberKind.Human, model.kind)
        assertEquals("Ada Lovelace", model.displayName)
        assertEquals("ada", model.handle)
        assertEquals("Designs the checklists", model.description)
        // People carry no presence, runtime or read receipt on the card.
        assertNull(model.status)
        assertNull(model.runtimeLabel)
        assertNull(model.readReceipt)
    }

    @Test
    fun anAgentShowsItsStatusRuntimeModelAndReadReceipt() {
        val model =
            raftProfilePreviewCardModel(
                MemberProfile(
                    id = MemberId("a1"),
                    displayName = "Bugen",
                    handle = "bugen",
                    kind = MemberKind.Agent,
                    roleDescription = "claude",
                    model = "claude-sonnet-4-6",
                ),
                status = working,
                readReceipt = RaftProfilePreviewReadReceipt.Unread,
            )
        assertEquals(working, model.status)
        assertEquals(getRuntimeDisplayName("claude"), model.runtimeLabel)
        assertNotNull(model.modelLabel)
        assertEquals(RaftProfilePreviewReadReceipt.Unread, model.readReceipt)
        assertNull(model.appBot)
    }

    @Test
    fun anAppBotIsNamedAfterItsAppWithNoPresenceRuntimeOrDescription() {
        val model =
            raftProfilePreviewCardModel(
                MemberProfile(
                    id = MemberId("a2"),
                    displayName = "stamp-bot",
                    handle = "stamp",
                    kind = MemberKind.Agent,
                    description = "Old snapshot",
                    roleDescription = "claude",
                    model = "claude-sonnet-4-6",
                    managedByAppInstallId = "install-1",
                    managedByAppName = "Stamp",
                ),
                status = working,
                readReceipt = RaftProfilePreviewReadReceipt.Read,
            )
        assertEquals("Stamp", model.displayName)
        assertEquals("Stamp", model.appBot?.appName)
        assertNull(model.status)
        assertNull(model.runtimeLabel)
        assertNull(model.modelLabel)
        assertNull(model.readReceipt)
        assertNull(model.description)
    }

    @Test
    fun aMemberTheDirectoryDoesNotKnowKeepsTheMentionsHandle() {
        val model = RaftProfilePreviewCardModel.unavailable("a3", MemberKind.Agent, fallbackHandle = "@volta")
        assertTrue(model.unavailable)
        assertEquals("@volta", model.displayName)
        assertEquals("", RaftProfilePreviewCardModel.unavailable("a4", MemberKind.Human, fallbackHandle = null).displayName)
    }
}
