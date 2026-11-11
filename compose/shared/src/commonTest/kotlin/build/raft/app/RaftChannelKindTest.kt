package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one channel-kind glyph chooser (web components/channel/channelKind.ts `channelKindIconProps`,
 * channelKindIcon.tsx `ChannelKindIcon`, sidebarChannelVisibility.ts `isSidebarExternalSectionChannel`;
 * botiverse/slock@44ce160f1, #13983), gated on the server's `external_channels_v1`.
 */
class RaftChannelKindTest {
    private data class Case(
        val type: String?,
        val onPublicPage: Boolean,
        val hasExternalRelation: Boolean,
        val off: RaftChannelKindGlyph,
        val on: RaftChannelKindGlyph,
    )

    @Test
    fun everyKindWithTheFlagOffAndOn() {
        val cases =
            listOf(
                Case("channel", false, false, RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.Hash),
                Case("channel", true, false, RaftChannelKindGlyph.PublicPage, RaftChannelKindGlyph.PublicPage),
                Case("private", false, false, RaftChannelKindGlyph.Lock, RaftChannelKindGlyph.Lock),
                // A joint channel is in the External section (guest servers) whatever its relation.
                Case("joint", false, false, RaftChannelKindGlyph.Joint, RaftChannelKindGlyph.External),
                // A Guest or an open external invite: public, private or published alike.
                Case("channel", false, true, RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.External),
                Case("channel", true, true, RaftChannelKindGlyph.PublicPage, RaftChannelKindGlyph.External),
                Case("private", false, true, RaftChannelKindGlyph.Lock, RaftChannelKindGlyph.External),
                Case("JOINT", false, true, RaftChannelKindGlyph.Joint, RaftChannelKindGlyph.External),
                // Only a regular channel goes on the public page; an unknown type draws `#`.
                Case("private", true, false, RaftChannelKindGlyph.Lock, RaftChannelKindGlyph.Lock),
                Case(null, false, false, RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.Hash),
                Case("dm", false, true, RaftChannelKindGlyph.Hash, RaftChannelKindGlyph.Hash),
            )
        for (case in cases) {
            val name = "${case.type} publicPage=${case.onPublicPage} external=${case.hasExternalRelation}"
            assertEquals(case.off, raftChannelKindGlyph(case.type, case.onPublicPage, case.hasExternalRelation, externalChannelsEnabled = false), "off: $name")
            assertEquals(case.on, raftChannelKindGlyph(case.type, case.onPublicPage, case.hasExternalRelation, externalChannelsEnabled = true), "on: $name")
        }
    }

    @Test
    fun theGlyphsKeepTheSidebarSymbolsAndIcons() {
        assertEquals(
            listOf("#", "lock", "git-branch", HomeChannelSymbolHashGlobe, "external-channel"),
            RaftChannelKindGlyph.entries.map { it.symbol },
        )
        assertEquals(RaftLucideIconName.ExternalChannel, RaftChannelKindGlyph.External.iconName)
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
        assertEquals(RaftChannelKindGlyph.Lock, vendor.raftChannelKindGlyph(serverPublicPageLive = true, externalChannelsEnabled = false))
        assertEquals(RaftChannelKindGlyph.External, vendor.raftChannelKindGlyph(serverPublicPageLive = true, externalChannelsEnabled = true))
        assertEquals(RaftChannelKindGlyph.Joint, joint.raftChannelKindGlyph(serverPublicPageLive = false, externalChannelsEnabled = false))
        assertEquals(RaftChannelKindGlyph.External, joint.raftChannelKindGlyph(serverPublicPageLive = false, externalChannelsEnabled = true))
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.raftChannelKindGlyph(serverPublicPageLive = true, externalChannelsEnabled = true))

        val item = RaftHomeConversationItem(id = "v", name = "vendor", kind = "channel", channelType = "private", hasExternalRelation = true)
        assertEquals("lock", item.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("external-channel", item.copy(externalChannelsEnabled = true).homeChannelSymbol(serverPublicPageLive = false))
        // Interaction identities keep the plain kind symbol.
        assertEquals("lock", item.copy(externalChannelsEnabled = true).homeChannelSymbol())
    }

    @Test
    fun aSurfaceThatKnowsOnlyTheIdTakesTheHomeRowsGlyphElseTheType() {
        val kinds =
            RaftChannelKinds(
                externalChannelsEnabled = true,
                glyphsByChannelId = mapOf("v" to RaftChannelKindGlyph.External, "l" to RaftChannelKindGlyph.PublicPage),
            )
        assertEquals(RaftChannelKindGlyph.External, kinds.glyph("v", "private"))
        assertEquals(RaftChannelKindGlyph.PublicPage, kinds.glyph("l", "channel"))
        // Not loaded here: by its type, the flag still making a joint channel External.
        assertEquals(RaftChannelKindGlyph.External, kinds.glyph("other", "joint"))
        assertEquals(RaftChannelKindGlyph.Lock, kinds.glyph(null, "private"))
        assertEquals(RaftChannelKindGlyph.Joint, RaftChannelKinds.Empty.glyph("other", "joint"))
    }

    @Test
    fun theChannelHeaderReadsTheDirectoryRowAndTheServersFlag() {
        val header =
            ThreadHeaderDisplayModel(
                title = "vendor",
                subtitle = null,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "private",
                channelHasExternalRelation = true,
            )
        assertEquals(RaftChannelKindGlyph.Lock, header.channelKindGlyph)
        assertEquals(RaftChannelKindGlyph.External, header.copy(externalChannelsEnabled = true).channelKindGlyph)
        // A reply thread's header is not the channel's.
        assertEquals(
            RaftChannelKindGlyph.Lock,
            header.copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread, externalChannelsEnabled = true).channelKindGlyph,
        )
        assertEquals(
            "lucide-external-channel",
            raftNativeNavBarImageAssetName(RaftNativeNavBarTitleLeading.Channel(RaftChannelKindGlyph.External).glyph.iconName),
        )
    }
}
