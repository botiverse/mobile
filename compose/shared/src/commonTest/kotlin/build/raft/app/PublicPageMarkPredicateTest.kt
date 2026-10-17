package build.raft.app

import build.raft.app.components.channelOnPublicPage
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.authSessionFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun globeIsOnlyForRegularChannels() {
        val shownKinds = listOf("channel", "Channel", "private", "joint", "dm", null).filter { kind ->
            channelOnPublicPage(serverPublicPageLive = true, channelKind = kind, guestVisible = true, archived = false)
        }
        assertEquals(listOf<String?>("channel", "Channel"), shownKinds)
    }

    @Test
    fun homeRowSymbolSwapsHashForHashGlobeOnlyOnALiveServer() {
        val publicChannel = SlockHomeConversationItem(id = "c1", name = "news", kind = "channel", channelType = "channel", guestVisible = true)

        assertEquals(HomeChannelSymbolHashGlobe, publicChannel.homeChannelSymbol(serverPublicPageLive = true))
        assertEquals("#", publicChannel.homeChannelSymbol(serverPublicPageLive = false))
        assertEquals("#", publicChannel.copy(archived = true).homeChannelSymbol(serverPublicPageLive = true))
        assertEquals("lock", publicChannel.copy(channelType = "private").homeChannelSymbol(serverPublicPageLive = true))
        // Interaction identities use the plain symbol and never change with the public mark.
        assertEquals("#", publicChannel.homeChannelSymbol())
    }

    @Test
    fun switcherItemsCarryPublicPageLiveFromTheSessionServerList() {
        val session = authSessionFixture(
            servers = listOf(
                StoredAuthServer(id = "server-1", name = "One", slug = "one", publicPageLive = true),
                StoredAuthServer(id = "server-2", name = "Two", slug = "two"),
            )
        )
        // Even a selected-server summary from another source (no publicPageLive) defers to the session row.
        val selected = session.servers.first().toServerSummary().copy(publicPageLive = false)

        val items = sharedHomeSwitcherItems(
            session = session,
            selectedServer = selected,
            serverUnreadCounts = emptyMap(),
            activityExpectedOwner = null,
            activityProjection = HomeServerActivityUnreadProjection(),
        )

        assertEquals(mapOf("server-1" to true, "server-2" to false), items.associate { it.id to it.publicPageLive })
        assertTrue(homeHeaderServerIdentity("server-1", "One", items).publicPageLive)
    }

    @Test
    fun threadHeaderGlobeAppliesOnlyToTheChannelSurface() {
        val channelHeader = ThreadHeaderDisplayModel(
            title = "news",
            subtitle = null,
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelKind = "channel",
            channelGuestVisible = true,
            serverPublicPageLive = true,
        )

        assertTrue(channelHeader.channelOnPublicPage)
        assertFalse(channelHeader.copy(serverPublicPageLive = false).channelOnPublicPage)
        assertFalse(channelHeader.copy(channelArchived = true).channelOnPublicPage)
        assertFalse(channelHeader.copy(surfaceKind = KuiklyThreadSurfaceKind.ReplyThread).channelOnPublicPage)
    }
}
