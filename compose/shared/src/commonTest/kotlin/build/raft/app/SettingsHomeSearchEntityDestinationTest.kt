package build.raft.app

import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SettingsHomeSearchEntityDestinationTest {
    @Test
    fun selfAndOtherHumansProjectCanonicalMemberDestinations() {
        listOf(
            sender(id = "self-1", label = "Current User", isSelf = true),
            sender(id = "human-2", label = "Other Human"),
        ).forEach { sender ->
            val result = sender.toHomeSearchEntityResult(
                conversationIdentity = null,
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members",
            )!!

            val destination = assertIs<HomeSearchEntityDestination.Member>(result.destinationOrNull())
            assertEquals(sender.label, result.title)
            assertEquals(sender.id, result.memberId)
            assertEquals(
                MemberDetailIdentity(
                    memberId = MemberId(sender.id),
                    kind = MemberKind.Human,
                    sourceServerId = sender.sourceServerId,
                ),
                destination.identity,
            )
            assertEquals(result.memberIdentity, destination.identity)
        }
    }

    @Test
    fun missingMemberIdAndDisplayOnlyCandidatesAreUnavailable() {
        assertNull(
            sender(id = "", label = "Looks Like A Person")
                .toHomeSearchEntityResult(
                    conversationIdentity = null,
                    agentLabel = "Agent",
                    personLabel = "Person",
                    membersLabel = "Members",
                )
        )
        assertNull(
            SearchEntityResult(
                key = "display-only",
                type = SearchEntityType.Human,
                title = "Same Display Name",
                subtitle = "@same-handle",
                threadId = null,
                memberIdentity = null,
                conversationIdentity = null,
            ).destinationOrNull()
        )
    }

    @Test
    fun equalDisplayIdentityOnTwoServersKeepsDistinctCanonicalDestinations() {
        val serverA = sender(id = "human-a", label = "Same Name", handle = "same", serverId = "server-a")
            .toHomeSearchEntityResult(null, "Agent", "Person", "Members")!!
        val serverB = sender(id = "human-b", label = "Same Name", handle = "same", serverId = "server-b")
            .toHomeSearchEntityResult(null, "Agent", "Person", "Members")!!

        assertEquals(serverA.title, serverB.title)
        assertEquals(serverA.subtitle, serverB.subtitle)
        assertEquals(
            ServerId("server-a"),
            assertIs<HomeSearchEntityDestination.Member>(serverA.destinationOrNull()).identity.sourceServerId,
        )
        assertEquals(
            ServerId("server-b"),
            assertIs<HomeSearchEntityDestination.Member>(serverB.destinationOrNull()).identity.sourceServerId,
        )
    }

    @Test
    fun existingNonSelfDirectMessageStillOwnsTheClickDestination() {
        val conversation =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("dm-thread-2"),
                channelId = ChannelId("dm-channel-2"),
                kind = ConversationKind.DirectMessage,
                dmPeerIdentity =
                    MemberDetailIdentity(
                        memberId = MemberId("human-2"),
                        kind = MemberKind.Human,
                        sourceServerId = ServerId("server-a"),
                    ),
            )
        val result =
            sender(id = "human-2", label = "Other Human")
                .toHomeSearchEntityResult(conversation, "Agent", "Person", "Members")!!

        assertEquals(
            conversation,
            assertIs<HomeSearchEntityDestination.Conversation>(result.destinationOrNull()).identity,
        )
    }

    @Test
    fun replyThreadUsageIsAttributedToItsTypedParentConversation() {
        val channelReply =
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = ChannelId("reply-stream-channel"),
                threadChannelId = ChannelId("reply-stream-channel"),
                parentChannelId = ChannelId("channel-parent"),
                parentMessageId = MessageId("channel-parent-message"),
                parentChannelKind = "channel",
            )
        val dmReply =
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = ChannelId("reply-stream-dm"),
                threadChannelId = ChannelId("reply-stream-dm"),
                parentChannelId = ChannelId("dm-parent"),
                parentMessageId = MessageId("dm-parent-message"),
                parentChannelKind = "direct",
            )

        assertEquals(
            SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, "channel-parent"),
            channelReply.searchPersonalizationEntityKeyOrNull(),
        )
        assertEquals(
            SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.DirectMessage, "dm-parent"),
            dmReply.searchPersonalizationEntityKeyOrNull(),
        )
    }

    @Test
    fun memberDestinationSerializesCompleteIdentityForMemberDetailParser() {
        val identity =
            MemberDetailIdentity(
                memberId = MemberId("self-1"),
                kind = MemberKind.Human,
                sourceServerId = ServerId("server-a"),
            )

        val pageData = homeSearchMemberDetailPageData(identity, timeContext = null)!!

        assertEquals(identity, pageData.toRaftMemberDetailPayload().identity)
    }

    private fun sender(
        id: String,
        label: String,
        handle: String = "handle",
        serverId: String = "server-a",
        isSelf: Boolean = false,
    ): SearchSenderFilterItem =
        SearchSenderFilterItem(
            id = id,
            label = label,
            handle = handle,
            kind = MemberKind.Human,
            sourceServerId = ServerId(serverId),
            isSelf = isSelf,
        )
}
