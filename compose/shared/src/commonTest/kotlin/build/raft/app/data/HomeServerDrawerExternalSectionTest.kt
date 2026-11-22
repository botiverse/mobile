package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server drawer's sections follow Home's sidebar (web sidebarChannelVisibility.ts
 * `isSidebarExternalSectionChannel`, botiverse/slock e60211734 #16042): External holds the joint and
 * external channels and the channels connected to Slack; Channels the rest.
 */
class HomeServerDrawerExternalSectionTest {
    @Test
    fun slackAndExternalChannelsAreListedUnderExternal() {
        fun channel(id: String, type: String = "channel", kind: ConversationKind = ConversationKind.Channel, bridgeProvider: String? = null, external: Boolean = false) =
            HomeChannelRow(
                threadId = ThreadId(id),
                channelId = ChannelId(id),
                channelType = type,
                kind = kind,
                name = id,
                bridgeProvider = bridgeProvider,
                hasExternalRelation = external,
            )
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Server One"),
                currentMember = MemberProfile(id = MemberId("viewer-1"), displayName = "Viewer", handle = "viewer@example.com", kind = MemberKind.Human),
                channels =
                    listOf(
                        channel("general"),
                        channel("secret", type = "private"),
                        channel("partners-sync", type = "private", bridgeProvider = "slack"),
                        channel("vendor", external = true),
                        channel("partners", kind = ConversationKind.Joint),
                    ),
                directMessages = emptyList(),
                unreadTotal = 0,
            )

        val bySection =
            snapshot.toHomeServerDrawerNormalizedContent().rows
                .filterIsInstance<HomeServerDrawerNormalizedRow.Conversation>()
                .groupBy({ it.section }, { it.conversation.threadId.value })
                .mapValues { (_, ids) -> ids.toSet() }

        assertEquals(setOf("general", "secret"), bySection[HomeServerDrawerConversationSection.Channel])
        assertEquals(setOf("partners-sync", "vendor", "partners"), bySection[HomeServerDrawerConversationSection.JointChannel])
    }
}
