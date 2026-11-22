package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one channel-kind glyph chooser (web components/channel/channelKind.ts `channelKindIconProps`,
 * channelKindIcon.tsx `ChannelKindIcon`, sidebarChannelVisibility.ts `isExternalMembersChannel` /
 * `isSlackChannel` / `isSidebarExternalSectionChannel`; botiverse/slock 6b0965841, #16060, and its
 * tests/channelKindIcon.behavior.test.tsx): the glyph is the widest group that can read the
 * channel (the web > Slack > people from outside > members > the server), and the corner holds only
 * the lock, for a channel private in the server.
 */
class RaftChannelKindTest {
    private data class Case(
        val type: String?,
        val onPublicPage: Boolean,
        val hasExternalRelation: Boolean,
        val glyph: RaftChannelKindGlyph,
        val bridgeProvider: String? = null,
    )

    @Test
    fun everyKind() {
        val cases =
            listOf(
                // The whole server, its members only.
                Case("channel", false, false, RaftChannelKindGlyph.Hash),
                Case("private", false, false, RaftChannelKindGlyph.Lock),
                // Anyone on the web: the globe, for a regular channel only.
                Case("channel", true, false, RaftChannelKindGlyph.PublicPage),
                Case("private", true, false, RaftChannelKindGlyph.Lock),
                // People from outside (a Guest, a guest server or an open invite; every joint channel):
                // two people, with the corner lock only when the channel is private.
                Case("channel", false, true, RaftChannelKindGlyph.External),
                Case("joint", false, false, RaftChannelKindGlyph.External),
                Case("JOINT", false, true, RaftChannelKindGlyph.External),
                Case("private", false, true, RaftChannelKindGlyph.ExternalPrivate),
                // People in Slack: the Slack mark, the corner lock only when private (no longer for a
                // public Slack channel with Guests, nor a joint one).
                Case("channel", false, false, RaftChannelKindGlyph.Slack, "slack"),
                Case("channel", false, true, RaftChannelKindGlyph.Slack, "slack"),
                Case("joint", false, false, RaftChannelKindGlyph.Slack, "slack"),
                Case("private", false, false, RaftChannelKindGlyph.SlackPrivate, "slack"),
                Case("private", false, true, RaftChannelKindGlyph.SlackPrivate, "SLACK"),
                // The bigger audience wins: the globe over Slack and people from outside, Slack over
                // people from outside.
                Case("channel", true, true, RaftChannelKindGlyph.PublicPage, "slack"),
                Case("channel", true, true, RaftChannelKindGlyph.PublicPage),
                Case("channel", true, false, RaftChannelKindGlyph.PublicPage, "slack"),
                // Another provider is not Slack; an unknown type draws `#`.
                Case("channel", false, false, RaftChannelKindGlyph.Hash, "discord"),
                Case(null, false, false, RaftChannelKindGlyph.Hash),
                Case("dm", false, true, RaftChannelKindGlyph.Hash, "slack"),
            )
        for (case in cases) {
            val name = "${case.type} publicPage=${case.onPublicPage} external=${case.hasExternalRelation} bridge=${case.bridgeProvider}"
            assertEquals(
                case.glyph,
                raftChannelRecordKindGlyph(case.type, case.onPublicPage, case.hasExternalRelation, case.bridgeProvider),
                name,
            )
        }
    }

    @Test
    fun slackAndExternalChannelsSitInExternalButOnlyOutsidePeopleMakeAnExternalChannel() {
        // web sidebarChannelVisibility.test.ts "a Slack channel sits in External and its icon carries
        // the Slack mark, not the external arrow" (botiverse/slock e60211734).
        assertTrue(isRaftExternalSectionChannel("channel", hasExternalRelation = false, bridgeProvider = "slack"))
        assertTrue(isRaftExternalSectionChannel("private", hasExternalRelation = false, bridgeProvider = "slack"))
        assertFalse(isRaftExternalSectionChannel("private", hasExternalRelation = false, bridgeProvider = null))
        assertTrue(isRaftExternalSectionChannel("private", hasExternalRelation = true, bridgeProvider = null))
        assertTrue(isRaftExternalSectionChannel("joint", hasExternalRelation = false, bridgeProvider = null))
        assertFalse(isRaftExternalSectionChannel("channel", hasExternalRelation = false, bridgeProvider = "discord"))
        // Its details don't tag it External: nobody from outside is a member.
        assertFalse(isRaftExternalMembersChannel("private", hasExternalRelation = false))
        assertEquals(RaftChannelKindGlyph.SlackPrivate, raftChannelRecordKindGlyph("private", bridgeProvider = "slack"))

        val slack = HomeChannelRow(threadId = ThreadId("s"), channelId = ChannelId("s"), name = "partners-sync", channelType = "private", bridgeProvider = "slack")
        val joint = HomeChannelRow(threadId = ThreadId("j"), channelId = ChannelId("j"), name = "partners", channelType = "channel", kind = ConversationKind.Joint)
        val guests = HomeChannelRow(threadId = ThreadId("g"), channelId = ChannelId("g"), name = "vendor", channelType = "channel", hasExternalRelation = true)
        val plain = HomeChannelRow(threadId = ThreadId("p"), channelId = ChannelId("p"), name = "general", channelType = "channel")
        assertEquals(listOf(true, true, true, false), listOf(slack, joint, guests, plain).map { it.isHomeExternalSectionChannel() })

        val slackItem = RaftHomeConversationItem(id = "s", name = "partners-sync", kind = "channel", channelType = "private", bridgeProvider = "slack")
        assertTrue(slackItem.isHomeExternalSectionChannel())
        assertTrue(RaftHomeConversationItem(id = "j", name = "partners", kind = JointChannelKindWireValue).isHomeExternalSectionChannel())
        assertTrue(RaftHomeConversationItem(id = "g", name = "vendor", kind = "channel", hasExternalRelation = true).isHomeExternalSectionChannel())
        assertFalse(slackItem.copy(bridgeProvider = null).isHomeExternalSectionChannel())
        // A DM never sits in External.
        assertFalse(RaftHomeConversationItem(id = "d", name = "ada", kind = RaftThreadSurfaceKind.DirectMessage, bridgeProvider = "slack").isHomeExternalSectionChannel())
    }

    @Test
    fun theTypeAloneIsTheWebFallbackAndNeverExternal() {
        // web `<ChannelKindIcon type={type} />` for a channel that is not loaded: no `external`.
        assertEquals(RaftChannelKindGlyph.Joint, raftChannelKindGlyph("joint"))
        assertEquals(RaftChannelKindGlyph.Lock, raftChannelKindGlyph("private"))
        assertEquals(RaftChannelKindGlyph.Hash, raftChannelKindGlyph("channel"))
        assertEquals(RaftChannelKindGlyph.PublicPage, raftChannelKindGlyph("channel", onPublicPage = true))
        // `external` and `slack` apply to a public, private or joint channel only.
        assertEquals(RaftChannelKindGlyph.External, raftChannelKindGlyph("joint", external = true))
        assertEquals(RaftChannelKindGlyph.ExternalPrivate, raftChannelKindGlyph("private", external = true))
        assertEquals(RaftChannelKindGlyph.Hash, raftChannelKindGlyph("dm", external = true, slack = true))
    }
}
