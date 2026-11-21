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
 * `isSlackChannel` / `isSidebarExternalSectionChannel`; botiverse/slock@44ce160f1, #13983, and
 * e60211734, #16042: a Slack channel draws the Slack mark and sits in External).
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
                Case("channel", false, false, RaftChannelKindGlyph.Hash),
                Case("channel", true, false, RaftChannelKindGlyph.PublicPage),
                Case("private", false, false, RaftChannelKindGlyph.Lock),
                // A joint channel is in the External section (guest servers) whatever its relation.
                Case("joint", false, false, RaftChannelKindGlyph.External),
                // A Guest or an open external invite: public, private or published alike.
                Case("channel", false, true, RaftChannelKindGlyph.External),
                Case("channel", true, true, RaftChannelKindGlyph.External),
                Case("private", false, true, RaftChannelKindGlyph.External),
                Case("JOINT", false, true, RaftChannelKindGlyph.External),
                // Only a regular channel goes on the public page; an unknown type draws `#`.
                Case("private", true, false, RaftChannelKindGlyph.Lock),
                Case(null, false, false, RaftChannelKindGlyph.Hash),
                Case("dm", false, true, RaftChannelKindGlyph.Hash),
                // Connected to Slack (#16042): the Slack mark is the glyph and wins over the globe and the
                // arrow; the corner lock when it is not public or has people from outside, and the
                // private label only for a private channel.
                Case("channel", false, false, RaftChannelKindGlyph.Slack, "slack"),
                Case("channel", true, false, RaftChannelKindGlyph.Slack, "slack"),
                Case("private", false, false, RaftChannelKindGlyph.SlackPrivate, "slack"),
                Case("channel", false, true, RaftChannelKindGlyph.SlackLocked, "slack"),
                Case("private", false, true, RaftChannelKindGlyph.SlackPrivate, "SLACK"),
                Case("joint", false, false, RaftChannelKindGlyph.SlackLocked, "slack"),
                // Another provider is not Slack; only a channel draws the Slack mark.
                Case("channel", false, false, RaftChannelKindGlyph.Hash, "discord"),
                Case("dm", false, false, RaftChannelKindGlyph.Hash, "slack"),
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
        // `external` applies to a public, private or joint channel only.
        assertEquals(RaftChannelKindGlyph.External, raftChannelKindGlyph("joint", external = true))
        assertEquals(RaftChannelKindGlyph.Hash, raftChannelKindGlyph("dm", external = true))
    }

    @Test
    fun theGlyphsKeepTheSidebarSymbolsAndIcons() {
        assertEquals(
            listOf(
                "#",
                "lock",
                "git-branch",
                HomeChannelSymbolHashGlobe,
                "external-channel",
                "slack-channel",
                "slack-channel-locked",
                "slack-channel-private",
            ),
            RaftChannelKindGlyph.entries.map { it.symbol },
        )
        assertEquals(RaftLucideIconName.ExternalChannel, RaftChannelKindGlyph.External.iconName)
        // The Slack mark, and the mark with the corner lock (private, joint or with people from outside).
        assertEquals(RaftLucideIconName.SlackChannel, RaftChannelKindGlyph.Slack.iconName)
        assertEquals(RaftLucideIconName.SlackChannelLocked, RaftChannelKindGlyph.SlackLocked.iconName)
        assertEquals(RaftLucideIconName.SlackChannelLocked, RaftChannelKindGlyph.SlackPrivate.iconName)
        assertEquals(
            listOf(RaftChannelKindGlyph.Slack, RaftChannelKindGlyph.SlackLocked, RaftChannelKindGlyph.SlackPrivate),
            RaftChannelKindGlyph.entries.filter { it.isSlack },
        )
        assertEquals("slack", RaftChannelKindGlyph.SlackPrivate.wireChannelKind())
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
        assertEquals(RaftChannelKindGlyph.External, vendor.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.External, joint.raftChannelKindGlyph(serverPublicPageLive = false))
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.Hash, lobby.raftChannelKindGlyph(serverPublicPageLive = false))

        val item = RaftHomeConversationItem(id = "v", name = "vendor", kind = "channel", channelType = "private", hasExternalRelation = true)
        assertEquals("external-channel", item.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("lock", item.copy(hasExternalRelation = false).homeChannelSymbol(serverPublicPageLive = false))
        // Interaction identities keep the plain kind symbol.
        assertEquals("lock", item.homeChannelSymbol())

        // A Slack channel: the Slack mark on the Home row and the sidebar item alike.
        val sync = lobby.copy(bridgeProvider = "slack")
        assertEquals(RaftChannelKindGlyph.Slack, sync.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.SlackLocked, vendor.copy(channelType = "channel", bridgeProvider = "slack").raftChannelKindGlyph(false))
        assertEquals("slack-channel-private", item.copy(bridgeProvider = "slack").homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("slack-channel", item.copy(channelType = "channel", hasExternalRelation = false, bridgeProvider = "slack").homeChannelSymbol(serverPublicPageLive = false))
        assertEquals(RaftChannelKindGlyph.SlackPrivate, RaftChannelKindGlyph.fromSymbol("slack-channel-private"))
    }

    @Test
    fun aSurfaceThatKnowsOnlyTheIdTakesTheHomeRowsGlyphElseTheType() {
        val kinds =
            RaftChannelKinds(
                glyphsByChannelId = mapOf("v" to RaftChannelKindGlyph.External, "l" to RaftChannelKindGlyph.PublicPage),
            )
        assertEquals(RaftChannelKindGlyph.External, kinds.glyph("v", "private"))
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
        assertEquals(RaftChannelKindGlyph.External, header.channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.Lock, header.copy(channelHasExternalRelation = false).channelKindGlyph)
        // A reply thread's header is not the channel's.
        assertEquals(
            RaftChannelKindGlyph.Lock,
            header.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread).channelKindGlyph,
        )
        assertEquals(
            "lucide-external-channel",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.External).glyph.iconName),
        )
        // A Slack channel's header: the Slack mark, with its lock when private (the iOS bar's asset too).
        val slack = header.copy(channelKind = "channel", channelHasExternalRelation = false, bridgeProvider = "slack")
        assertEquals(RaftChannelKindGlyph.Slack, slack.channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.SlackPrivate, slack.copy(channelKind = "private").channelKindGlyph)
        assertEquals(
            "lucide-slack-channel-locked",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(slack.copy(channelKind = "private").channelKindGlyph).glyph.iconName),
        )
        // A reply thread's header is not the channel's, Slack or not.
        assertEquals(RaftChannelKindGlyph.Hash, slack.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread).channelKindGlyph)
    }
}
