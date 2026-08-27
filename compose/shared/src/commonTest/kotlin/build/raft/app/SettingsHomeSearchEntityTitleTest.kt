package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #246: when the leading avatar/icon already expresses the channel type,
 * the displayed entity name must keep only the name itself — no concatenated
 * "#" prefix (double-hash). Canonical target/routing values are unaffected.
 */
class SettingsHomeSearchEntityTitleTest {
    @Test
    fun channelEntityRowTitleKeepsNameWithoutHashPrefix() {
        val candidates = homeSearchEntityCandidates(homeState(), emptyList(), "Channels", "DMs", "Agents", "People", "Members")

        val channel = candidates.single { it.result.key.startsWith("conversation:") && it.result.type == SearchEntityType.Channel }
        assertEquals("all-staff-plus", channel.result.title)
        assertFalse(channel.result.title.startsWith("#"))

        val dm = candidates.single { it.result.type == SearchEntityType.DirectMessage }
        assertEquals("alex (bot)", dm.result.title)
    }

    @Test
    fun channelFilterOptionLabelKeepsNameWithoutHashPrefix() {
        val labels = homeSearchChannelFilters(homeState()).map { it.label }

        assertTrue(labels.contains("all-staff-plus"))
        assertFalse(labels.any { it.startsWith("#") })
    }

    private fun homeState(): SharedHomeState =
        SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = ServerSummary(ServerId("server-1"), "Slock Community"),
                            currentMember =
                                MemberProfile(
                                    id = MemberId("user-1"),
                                    displayName = "Artin",
                                    handle = "artin",
                                    kind = MemberKind.Human
                                ),
                            channels =
                                listOf(
                                    HomeChannelRow(
                                        threadId = ThreadId("thread-1"),
                                        channelId = ChannelId("channel-1"),
                                        name = "all-staff-plus",
                                        subtitle = "staff+ level"
                                    )
                                ),
                            directMessages =
                                listOf(
                                    HomeDirectMessageRow(
                                        threadId = ThreadId("thread-2"),
                                        channelId = ChannelId("channel-2"),
                                        name = "alex (bot)",
                                        kind = ConversationKind.DirectMessage,
                                        directMessagePeerType = "agent"
                                    )
                                ),
                            unreadTotal = 0
                        ),
                    session = testSession()
                )
        )

    private fun testSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.test"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1"
        )
}
