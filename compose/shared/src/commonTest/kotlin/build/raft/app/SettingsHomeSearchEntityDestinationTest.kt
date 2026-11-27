package build.raft.app

import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.settings.HomeSearchEntityDestination
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SearchEntityType
import build.raft.app.settings.destinationOrNull
import build.raft.app.settings.toHomeSearchEntityResult
import build.raft.app.thread.key
import build.raft.app.toHomeSearchEntityResult
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
