package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelBridgeOutboundWireDto
import build.raft.app.data.HomeChannelBridgeWireDto
import build.raft.app.data.HomeChannelWireDto
import build.raft.app.data.HomeNetworkPayload
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.parseRaftChannelDetail
import build.raft.app.data.parseRaftHome
import build.raft.app.data.slackSyncProblemOrNull
import build.raft.app.data.toConversationSummary
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toSharedConversationDirectoryProjection
import build.raft.app.model.RaftSlackSyncProblem
import build.raft.app.model.ServerId
import build.raft.app.settings.SharedHomeConversationRowProjector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Slack channel's `channel.bridge` (task #396; botiverse/slock e60211734 #16042): the provider makes it
 * a Slack channel (its glyph, its External section, no "Slack" label), and the rest says whether its sync
 * stopped, the warning Badge on its header and sidebar row (web slackSyncHeaderState.ts,
 * ChannelSlackSyncBadge.tsx). Both reach the Home row, the Home item, the directory and the header.
 */
class RaftSlackChannelBridgeTest {
    @Test
    fun channelListCarriesSlackBridgeProviderAndOmitsItWithoutBridge() {
        val result =
            parseRaftHome(
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
        assertNull(bridged.slackSyncProblem)
        assertNull(plain.bridgeProvider)
    }

    @Test
    fun channelDetailDecodesServerBridgeShapeAndToleratesMissingOrBlankProvider() {
        val bridged =
            parseRaftChannelDetail(
                """{"id":"c1","name":"sync","type":"channel",""" +
                    """"bridge":{"provider":"slack","providerConversationId":"C0123","state":"paused"}}"""
            )
        val absent = parseRaftChannelDetail("""{"id":"c2","name":"general","type":"channel"}""")
        val nullBridge = parseRaftChannelDetail("""{"id":"c3","name":"general","type":"channel","bridge":null}""")
        val blank = parseRaftChannelDetail("""{"id":"c4","name":"general","type":"channel","bridge":{"provider":" "}}""")

        assertEquals("slack", bridged?.bridgeProvider)
        // Paused with no reason is a pair still being set up: no badge (web slackSyncHeaderState).
        assertNull(bridged?.slackSyncProblem)
        assertNull(absent?.bridgeProvider)
        assertNull(nullBridge?.bridgeProvider)
        assertNull(blank?.bridgeProvider)
    }

    private data class SyncCase(val name: String, val bridge: HomeChannelBridgeWireDto?, val problem: RaftSlackSyncProblem?)

    @Test
    fun theSyncBadgeFollowsTheWebHeaderState() {
        val slack = HomeChannelBridgeWireDto(provider = "slack", providerConversationId = "C_PRODUCTION_GENERAL", state = "active")
        val cases =
            listOf(
                SyncCase("syncing", slack, null),
                SyncCase("not connected", null, null),
                // botiverse/slock e60211734 packages/server/src/services/slackBridgeProductionComposition.test.ts:
                // an installation waiting for reauthorization stops every pair it carries.
                SyncCase("install reauth", slack.copy(installState = "reauth_required"), RaftSlackSyncProblem.Paused),
                SyncCase("install revoked", slack.copy(installState = "revoked"), RaftSlackSyncProblem.Paused),
                SyncCase("quarantined", slack.copy(state = "quarantined"), RaftSlackSyncProblem.Paused),
                SyncCase(
                    "slack channel archived",
                    slack.copy(state = "paused", stateReason = "provider_channel_archived"),
                    RaftSlackSyncProblem.Paused,
                ),
                SyncCase("pair being set up", slack.copy(state = "paused", stateReason = "provisioning_preflight_pending"), null),
                SyncCase("paused without a reason", slack.copy(state = "paused"), null),
                SyncCase(
                    "held for Slack",
                    slack.copy(outbound = HomeChannelBridgeOutboundWireDto(status = "waiting", reason = "invite_app", since = "2026-11-21T10:00:00.000Z")),
                    RaftSlackSyncProblem.Waiting,
                ),
                SyncCase(
                    "held too long",
                    slack.copy(outbound = HomeChannelBridgeOutboundWireDto(status = "stalled", since = "2026-11-21T10:00:00.000Z")),
                    RaftSlackSyncProblem.Stalled,
                ),
                // A status the app does not know draws no badge (logged), as the web draws none.
                SyncCase("unknown hold", slack.copy(outbound = HomeChannelBridgeOutboundWireDto(status = "parked")), null),
                SyncCase("another provider", slack.copy(provider = "discord", installState = "revoked"), null),
            )
        for (case in cases) {
            assertEquals(case.problem, case.bridge.slackSyncProblemOrNull(), case.name)
        }
    }

    @Test
    fun aStoppedSyncReachesTheHomeRowTheItemAndTheDirectory() {
        val row =
            requireNotNull(
                parseRaftChannelDetail(
                    """{"id":"c1","name":"sync","type":"private","bridge":{"provider":"slack",""" +
                        """"providerConversationId":"C0123","state":"active","outbound":{"status":"stalled","since":"2026-11-21T10:00:00.000Z"}}}"""
                )
            )
        assertEquals(RaftSlackSyncProblem.Stalled, row.slackSyncProblem)
        assertEquals(RaftSlackSyncProblem.Stalled, row.toConversationSummary().toHomeChannelRow().slackSyncProblem)

        val item = SharedHomeConversationRowProjector().project(row)
        assertEquals(RaftSlackSyncProblem.Stalled, item.slackSyncProblem)

        // The directory row is what the channel header reads (ThreadPayloadTest covers the header).
        val projection = requireNotNull(row.toSharedConversationDirectoryProjection(ServerId("server-1")))
        assertEquals(RaftSlackSyncProblem.Stalled, projection.slackSyncProblem)
        assertEquals("slack", projection.bridgeProvider)
    }

    @Test
    fun onlyTheSlackProviderMakesASlackChannel() {
        assertTrue(isRaftSlackChannel("slack"))
        assertTrue(isRaftSlackChannel("SLACK"))
        assertFalse(isRaftSlackChannel(null))
        assertFalse(isRaftSlackChannel(""))
        assertFalse(isRaftSlackChannel("discord"))
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
