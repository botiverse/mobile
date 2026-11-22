package build.raft.app

import build.raft.app.components.channelOnPublicPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web parity (botiverse/slock@9df0d1b3f): a channel shows the public-page globe iff
 * server.publicPageLive && channel.guestVisible && !channel.archivedAt, and only for
 * type "channel" (packages/web/src/components/layout/Sidebar.tsx:894,
 * components/channel/channelKindIcon.tsx:22, components/message/ChatPanel.tsx:1366).
 */
class PublicPageMarkPredicateTest {
    @Test
    fun globeRequiresLiveServerGuestVisibleAndNotArchived() {
        data class Case(val live: Boolean, val guestVisible: Boolean, val archived: Boolean)
        val cases = listOf(false, true).flatMap { live ->
            listOf(false, true).flatMap { guestVisible ->
                listOf(false, true).map { archived -> Case(live, guestVisible, archived) }
            }
        }
        val shown = cases.filter { case ->
            channelOnPublicPage(
                serverPublicPageLive = case.live,
                channelKind = "channel",
                guestVisible = case.guestVisible,
                archived = case.archived,
            )
        }
        assertEquals(listOf(Case(live = true, guestVisible = true, archived = false)), shown)
    }

    @Test
    fun homeRowSymbolSwapsHashForTheGlobeOnlyOnALiveServer() {
        val publicChannel = RaftHomeConversationItem(id = "c1", name = "news", kind = "channel", channelType = "channel", guestVisible = true)

        assertEquals(RaftChannelKindGlyph.PublicPage.symbol, publicChannel.homeChannelSymbol(serverPublicPageLive = true))
        assertEquals("#", publicChannel.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("#", publicChannel.copy(archived = true).homeChannelSymbol(serverPublicPageLive = true))
        assertEquals("lock", publicChannel.copy(channelType = "private").homeChannelSymbol(serverPublicPageLive = true))
        // Interaction identities use the plain symbol and never change with the public mark.
        assertEquals("#", publicChannel.homeChannelSymbol())
    }
}
