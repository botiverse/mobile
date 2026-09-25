package build.raft.app

import build.raft.app.components.externalBridgeBadgeVisible
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelBridgeWireDto
import build.raft.app.data.HomeChannelWireDto
import build.raft.app.data.HomeNetworkPayload
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.parseSlockChannelDetail
import build.raft.app.data.parseSlockHome
import build.raft.app.data.toConversationSummary
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// task #396: Slack Bridge P2 — `channel.bridge` wire readback → `[Slack]` badge in the Home
// channel list and the channel conversation header (Web Sidebar parity, PR #8125).
class SlockExternalBridgeBadgeTest {
    @Test
    fun channelListCarriesSlackBridgeProviderAndOmitsItWithoutBridge() {
        val result =
            parseSlockHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels =
                            listOf(
                                HomeChannelWireDto(
                                    id = "bridged-1",
                                    name = "slack-sync",
                                    type = "channel",
                                    bridge =
                                        HomeChannelBridgeWireDto(
                                            provider = "slack",
                                            providerConversationId = "C0123",
                                            state = "active"
                                        )
                                ),
                                HomeChannelWireDto(id = "plain-1", name = "general", type = "channel")
                            ),
                        directMessages = emptyList(),
                        unreadCounts = emptyMap(),
                        serverUnreadSummary = emptyList()
                    ),
                session = sampleSession()
            )

        assertTrue(result is DataResult.Content)
        val bridged = result.data.channels.first { it.channelId?.value == "bridged-1" }
        val plain = result.data.channels.first { it.channelId?.value == "plain-1" }
        assertEquals("slack", bridged.bridgeProvider)
        assertNull(plain.bridgeProvider)
    }

    @Test
    fun channelDetailDecodesServerBridgeShapeAndToleratesMissingOrBlankProvider() {
        val bridged =
            parseSlockChannelDetail(
                """{"id":"c1","name":"sync","type":"channel",""" +
                    """"bridge":{"provider":"slack","providerConversationId":"C0123","state":"paused"}}"""
            )
        val absent = parseSlockChannelDetail("""{"id":"c2","name":"general","type":"channel"}""")
        val nullBridge = parseSlockChannelDetail("""{"id":"c3","name":"general","type":"channel","bridge":null}""")
        val blank = parseSlockChannelDetail("""{"id":"c4","name":"general","type":"channel","bridge":{"provider":" "}}""")

        assertEquals("slack", bridged?.bridgeProvider)
        assertNull(absent?.bridgeProvider)
        assertNull(nullBridge?.bridgeProvider)
        assertNull(blank?.bridgeProvider)
    }

    @Test
    fun bridgeProviderSurvivesSummaryRoundTripAndReachesHomeItem() {
        val row = requireNotNull(
            parseSlockChannelDetail(
                """{"id":"c1","name":"sync","type":"channel","bridge":{"provider":"Slack","state":"active"}}"""
            )
        )
        val summary = row.toConversationSummary()
        assertEquals("slack", summary.bridgeProvider)
        assertEquals("slack", summary.toHomeChannelRow().bridgeProvider)

        val item = SharedHomeConversationRowProjector().project(row)
        assertEquals("slack", item.bridgeProvider)
        val plainItem =
            SharedHomeConversationRowProjector().project(
                requireNotNull(parseSlockChannelDetail("""{"id":"c2","name":"general","type":"channel"}"""))
            )
        assertNull(plainItem.bridgeProvider)
    }

    @Test
    fun channelHeaderDisplayModelCarriesBridgeProviderFromDirectory() {
        val row = requireNotNull(
            parseSlockChannelDetail(
                """{"id":"c1","name":"sync","type":"channel","bridge":{"provider":"slack","state":"active"}}"""
            )
        )
        val projection = requireNotNull(row.toSharedConversationDirectoryProjection(ServerId("server-1")))
        assertEquals("slack", projection.bridgeProvider)

        val plain = requireNotNull(
            parseSlockChannelDetail("""{"id":"c2","name":"general","type":"channel"}""")
                ?.toSharedConversationDirectoryProjection(ServerId("server-1"))
        )
        assertNull(plain.bridgeProvider)
    }

    @Test
    fun badgeOnlyRendersForKnownSlackProvider() {
        assertTrue(externalBridgeBadgeVisible("slack"))
        assertTrue(externalBridgeBadgeVisible("SLACK"))
        assertFalse(externalBridgeBadgeVisible(null))
        assertFalse(externalBridgeBadgeVisible(""))
        assertFalse(externalBridgeBadgeVisible("discord"))
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Community"))
        )
}
