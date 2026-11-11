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
 * botiverse/slock@44ce160f1, #13983). Not gated by `external_channels_v1`, like the web
 * (sidebarChannelVisibility.ts `isSidebarExternalOnlyChannel`, plan §2a).
 */
class RaftChannelKindTest {
    private data class Case(
        val type: String?,
        val onPublicPage: Boolean,
        val hasExternalRelation: Boolean,
        val glyph: RaftChannelKindGlyph,
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
            )
        for (case in cases) {
            val name = "${case.type} publicPage=${case.onPublicPage} external=${case.hasExternalRelation}"
            assertEquals(case.glyph, raftChannelKindGlyph(case.type, case.onPublicPage, case.hasExternalRelation), name)
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
        assertEquals(RaftChannelKindGlyph.External, vendor.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.External, joint.raftChannelKindGlyph(serverPublicPageLive = false))
        assertEquals(RaftChannelKindGlyph.PublicPage, lobby.raftChannelKindGlyph(serverPublicPageLive = true))
        assertEquals(RaftChannelKindGlyph.Hash, lobby.raftChannelKindGlyph(serverPublicPageLive = false))

        val item = RaftHomeConversationItem(id = "v", name = "vendor", kind = "channel", channelType = "private", hasExternalRelation = true)
        assertEquals("external-channel", item.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("lock", item.copy(hasExternalRelation = false).homeChannelSymbol(serverPublicPageLive = false))
        // Interaction identities keep the plain kind symbol.
        assertEquals("lock", item.homeChannelSymbol())
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
    }
}
