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

    @Test
    fun theGlyphsKeepTheSidebarSymbolsAndIcons() {
        assertEquals(
            listOf(
                "#",
                "lock",
                "git-branch",
                "public-page-channel",
                "external-channel",
                "external-channel-private",
                "slack-channel",
                "slack-channel-private",
            ),
            RaftChannelKindGlyph.entries.map { it.symbol },
        )
        // Each glyph is one icon (web `SinglePathIcon`): the globe and two people as Lucide draws them,
        // and the drawings with the corner lock.
        assertEquals(
            listOf(
                RaftLucideIconName.Hash,
                RaftLucideIconName.Lock,
                RaftLucideIconName.GitBranch,
                RaftLucideIconName.PublicPageChannel,
                RaftLucideIconName.ExternalChannel,
                RaftLucideIconName.ExternalChannelPrivate,
                RaftLucideIconName.SlackChannel,
                RaftLucideIconName.SlackChannelPrivate,
            ),
            RaftChannelKindGlyph.entries.map { it.iconName },
        )
        assertEquals(listOf(RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackPrivate), RaftChannelKindGlyph.entries.filter { it.isSlack })
        // Only the channel record knows people from outside and Slack.
        assertEquals(
            listOf(RaftChannelKindGlyph.External, RaftChannelKindGlyph.ExternalPrivate, RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackPrivate),
            RaftChannelKindGlyph.entries.filter { it.needsChannelRecord },
        )
        assertEquals(
            listOf("channel", "private", "joint", "channel", "external", "external", "slack", "slack"),
            RaftChannelKindGlyph.entries.map { it.wireChannelKind() },
        )
        RaftChannelKindGlyph.entries.forEach { assertEquals(it, RaftChannelKindGlyph.fromSymbol(it.symbol)) }
        // The type-only helpers other surfaces keep go through the chooser.
        assertEquals("git-branch", "joint".toRaftChannelSymbol())
        assertEquals(RaftLucideIconName.Lock, "private".threadChannelHeaderIcon())
    }

    @Test
    fun aHomeRowAndASidebarItemPickTheSameGlyph() {
        val vendor =
            HomeChannelRow(threadId = ThreadId("v"), channelId = ChannelId("v"), name = "vendor", channelType = "private", hasExternalRelation = true)
        val joint =
            HomeChannelRow(threadId = ThreadId("j"), channelId = ChannelId("j"), name = "partners", channelType = "channel", kind = ConversationKind.Joint)
        val lobby =
            HomeChannelRow(threadId = ThreadId("l"), channelId = ChannelId("l"), name = "lobby", channelType = "channel", guestVisible = true)
        assertEquals(RaftChannelKindGlyph.ExternalPrivate, vendor.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.External, joint.raftChannelKindGlyph(serverPublicPageLive = false))
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.Hash, lobby.raftChannelKindGlyph(serverPublicPageLive = false))
        // A Guest in a channel anyone can read on the web: the globe.
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.copy(hasExternalRelation = true).raftChannelKindGlyph(serverPublicPageLive = true))

        val item = RaftHomeConversationItem(id = "v", name = "vendor", kind = "channel", channelType = "private", hasExternalRelation = true)
        assertEquals("external-channel-private", item.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("external-channel", item.copy(channelType = "channel").homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("lock", item.copy(hasExternalRelation = false).homeChannelSymbol(serverPublicPageLive = false))
        // Interaction identities keep the plain kind symbol.
        assertEquals("lock", item.homeChannelSymbol())

        // A Slack channel: the Slack mark on the Home row and the sidebar item alike, with the lock only
        // when it is private.
        assertEquals(RaftChannelKindGlyph.Slack, lobby.copy(bridgeProvider = "slack").raftChannelKindGlyph(serverPublicPageLive = false))
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.copy(bridgeProvider = "slack").raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.Slack, vendor.copy(channelType = "channel", bridgeProvider = "slack").raftChannelKindGlyph(false))
        assertEquals("slack-channel-private", item.copy(bridgeProvider = "slack").homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("slack-channel", item.copy(channelType = "channel", bridgeProvider = "slack").homeChannelSymbol(serverPublicPageLive = false))
    }

    @Test
    fun aSurfaceThatKnowsOnlyTheIdTakesTheHomeRowsGlyphElseTheType() {
        val kinds =
            RaftChannelKinds(
                glyphsByChannelId = mapOf("v" to RaftChannelKindGlyph.ExternalPrivate, "l" to RaftChannelKindGlyph.PublicPage),
            )
        assertEquals(RaftChannelKindGlyph.ExternalPrivate, kinds.glyph("v", "private"))
        assertEquals(RaftChannelKindGlyph.PublicPage, kinds.glyph("l", "channel"))
        // Not loaded here: by its type alone (web `<ChannelKindIcon type={type} />`): a joint channel
        // keeps git-branch, a channel `#`.
        assertEquals(RaftChannelKindGlyph.Joint, kinds.glyph("other", "joint"))
        assertEquals(RaftChannelKindGlyph.Hash, kinds.glyph("other", "channel"))
        assertEquals(RaftChannelKindGlyph.Lock, kinds.glyph(null, "private"))
        assertEquals(RaftChannelKindGlyph.Joint, RaftChannelKinds.Empty.glyph("other", "joint"))
    }

    @Test
    fun theChannelHeaderReadsTheDirectoryRow() {
        val header =
            ThreadHeaderDisplayModel(
                title = "vendor",
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                channelHasExternalRelation = true,
            )
        assertEquals(RaftChannelKindGlyph.ExternalPrivate, header.channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.External, header.copy(channelKind = "channel").channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.Lock, header.copy(channelHasExternalRelation = false).channelKindGlyph)
        // A reply thread's header is not the channel's.
        assertEquals(
            RaftChannelKindGlyph.Lock,
            header.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread).channelKindGlyph,
        )
        // The iOS bar's tile draws the same icon from the asset catalog.
        assertEquals(
            "lucide-external-channel-private",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(header.channelKindGlyph).glyph.iconName),
        )
        // A Slack channel's header: the Slack mark, with its lock when private.
        val slack = header.copy(channelKind = "channel", channelHasExternalRelation = false, bridgeProvider = "slack")
        assertEquals(RaftChannelKindGlyph.Slack, slack.channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.Slack, slack.copy(channelHasExternalRelation = true).channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.SlackPrivate, slack.copy(channelKind = "private").channelKindGlyph)
        assertEquals(
            "lucide-slack-channel-private",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(slack.copy(channelKind = "private").channelKindGlyph).glyph.iconName),
        )
        // Anyone on the web: the globe, over Slack.
        val published = slack.copy(channelGuestVisible = true, serverPublicPageLive = true)
        assertEquals(RaftChannelKindGlyph.PublicPage, published.channelKindGlyph)
        assertEquals(
            "lucide-public-page-channel",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(published.channelKindGlyph).glyph.iconName),
        )
        // A reply thread's header is not the channel's, Slack or not.
        assertEquals(RaftChannelKindGlyph.Hash, slack.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread).channelKindGlyph)
    }
}
