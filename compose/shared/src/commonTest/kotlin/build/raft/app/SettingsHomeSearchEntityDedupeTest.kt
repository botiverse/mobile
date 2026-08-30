package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SearchSenderFilterItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #238: the same entity was returned twice by entity search.
 *
 * A DM row and its member row are the same principal reached through two disjoint key
 * namespaces (`conversation:<threadId>` vs `sender:<server>:<kind>:<memberId>`), so the trailing
 * `distinctBy { result.key }` was structurally unable to collapse them. Reproduction condition:
 * only an entity that is *both* in the member directory *and* already has a DM conversation
 * duplicates - which is why the reporter hit it immediately on himself and nobody else had.
 *
 * The fixture dimension that matters is `presenceMemberId` present/blank, not human/agent: the
 * reported screenshot was a human whose DM did resolve through the presence index.
 */
class SettingsHomeSearchEntityDedupeTest {
    @Test
    fun humanMemberWithExistingDirectMessageCollapsesToOneRow() {
        val candidates = candidates(senderFilters = listOf(humanSender()))

        val artinRows = candidates.filter { it.result.title == "Artin Zaker" }
        assertEquals(1, artinRows.size, "expected exactly one row for a member that also has a DM")

        val row = artinRows.single()
        assertEquals(SearchEntityType.Human, row.result.type)
        // The surviving row must keep the DM launch affordance, not just look de-duplicated.
        assertEquals("thread-dm-human", row.result.threadId)
        assertNotNull(row.result.conversationIdentity)
        // and must stay reachable by what used to match the dropped DM row.
        assertTrue(
            row.searchFields.any { it.value == "thread-dm-human" },
            "member row should absorb the DM row's search fields"
        )
    }

    @Test
    fun agentMemberWithExistingDirectMessageCollapsesToOneRow() {
        val candidates = candidates(senderFilters = listOf(agentSender()))

        val agentRows = candidates.filter { it.result.title == "Alex" }
        assertEquals(1, agentRows.size, "expected exactly one row for an agent that also has a DM")
        assertEquals(SearchEntityType.Agent, agentRows.single().result.type)
        assertEquals("thread-dm-agent", agentRows.single().result.threadId)
    }

    /**
     * Negative control. Without it, an implementation that simply drops every duplicate would
     * satisfy every "must not appear twice" assertion above while showing nothing at all.
     */
    @Test
    fun memberWithoutDirectMessageStillProducesOneRow() {
        val candidates = candidates(senderFilters = listOf(memberWithoutDmSender()))

        val rows = candidates.filter { it.result.title == "No Dm Person" }
        assertEquals(1, rows.size, "a member with no DM must still appear exactly once")
        assertEquals(SearchEntityType.Human, rows.single().result.type)
    }

    /**
     * A member id is only unique within its own server. A joint channel surfaces members from
     * other servers, so an unscoped identity key would hand a foreign member the local DM's
     * launch identity - merging two different people into one row.
     */
    @Test
    fun memberFromAnotherServerDoesNotInheritLocalDirectMessage() {
        val candidates = candidates(senderFilters = listOf(foreignServerSenderSharingMemberId()))

        val foreign = candidates.single { it.result.title == "Foreign Namesake" }
        assertEquals(
            null,
            foreign.result.threadId,
            "a same-id member from another server must not absorb the local DM"
        )
        // The local DM row therefore survives on its own.
        assertTrue(
            candidates.any { it.result.type == SearchEntityType.DirectMessage && it.result.threadId == "thread-dm-human" },
            "unabsorbed DM row must remain visible"
        )
    }

    /**
     * A blank wire `peerId` becomes `MemberId("")`, which passed the old null-only check and sat
     * in the identity index as a key that can only ever join unrelated rows.
     *
     * Honest scope: this one is a **guard, not a reproduction** - it passes both before and after
     * the fix. A blank id cannot currently reach a wrong merge through this entry point, because
     * `memberDetailIdentity()` rejects a blank member id, so no member row is produced to collide
     * with. It pins the behaviour so a future caller cannot reintroduce an empty merge key.
     */
    @Test
    fun blankPeerIdIsNotUsableAsAnIdentityKey() {
        val candidates =
            candidates(
                directMessages =
                    listOf(
                        directMessageRow(
                            threadId = "thread-dm-blank-a",
                            name = "Blank A",
                            peerId = "",
                            peerType = "human"
                        ),
                        directMessageRow(
                            threadId = "thread-dm-blank-b",
                            name = "Blank B",
                            peerId = "   ",
                            peerType = "human"
                        )
                    ),
                senderFilters = listOf(memberWithoutDmSender())
            )

        // Both blank-peer DMs stay distinct rows; neither is merged into the other or into a member.
        assertTrue(candidates.any { it.result.threadId == "thread-dm-blank-a" })
        assertTrue(candidates.any { it.result.threadId == "thread-dm-blank-b" })
        val member = candidates.single { it.result.title == "No Dm Person" }
        assertEquals(null, member.result.threadId, "a blank peer id must never resolve to a member")
    }

    /**
     * The identity index is keyed on `presenceMemberId` alone, deliberately **without**
     * `MemberKind`. That is a design decision, not an omission: a DM row's peer kind comes from
     * the wire `peerType`, and `memberDetailIdentityFromWire()` returns null for any value it
     * cannot strictly parse - so keying on kind would silently narrow the merge to DMs whose
     * `peerType` happens to be well-formed.
     *
     * This case pins that decision. The DM below carries a usable `presenceMemberId` and a
     * malformed `peerType`, so its own `dmPeerIdentity` is null while the index still resolves.
     * It discriminates: against an implementation that required a parseable kind, the member and
     * its DM would fall into different namespaces again and the row count would go back to two.
     */
    @Test
    fun malformedPeerTypeStillCollapsesWithItsMemberRow() {
        val candidates =
            candidates(
                directMessages =
                    listOf(
                        directMessageRow(
                            threadId = "thread-dm-malformed-kind",
                            // Same display name as the member, as production does - otherwise the
                            // count assertion below passes for the wrong reason.
                            name = "Artin Zaker",
                            peerId = "member-artin",
                            peerType = "HUMANOID"
                        )
                    ),
                senderFilters = listOf(humanSender())
            )

        val rows = candidates.filter { it.result.title == "Artin Zaker" }
        assertEquals(
            1,
            rows.size,
            "a malformed peerType must not split an entity back into two rows"
        )
        // The surviving row still reaches the DM, which is what the kind-free key buys.
        assertEquals("thread-dm-malformed-kind", rows.single().result.threadId)
        assertNotNull(rows.single().result.conversationIdentity)
    }

    /**
     * The owner scope must be a typed key, not a concatenated `"$serverId:$memberId"` string.
     * With concatenation, server `"server-1:member"` + member `"artin"` and server `"server-1"` +
     * member `"member:artin"` both flatten to `"server-1:member:artin"` and merge two different
     * people. This case fails against a concatenated key and passes against a typed one, so it
     * discriminates the implementation rather than merely restating the row count.
     */
    @Test
    fun scopeKeyIsNotDelimiterAmbiguous() {
        val candidates =
            candidates(
                directMessages =
                    listOf(
                        directMessageRow(
                            threadId = "thread-dm-colon",
                            name = "Colon Peer",
                            peerId = "member:artin",
                            peerType = "human"
                        )
                    ),
                senderFilters =
                    listOf(
                        SearchSenderFilterItem(
                            id = "artin",
                            label = "Different Person",
                            handle = "artin",
                            kind = MemberKind.Human,
                            sourceServerId = ServerId("server-1:member"),
                        )
                    )
            )

        val person = candidates.single { it.result.title == "Different Person" }
        assertEquals(
            null,
            person.result.threadId,
            "a delimiter-collided scope must not merge two distinct members"
        )
    }

    private fun candidates(
        directMessages: List<HomeDirectMessageRow> = defaultDirectMessages(),
        senderFilters: List<SearchSenderFilterItem>,
    ) = homeSearchEntityCandidates(
        homeState = homeState(directMessages),
        senderFilters = senderFilters,
        channelLabel = "Channels",
        dmLabel = "DMs",
        agentLabel = "Agents",
        personLabel = "People",
        membersLabel = "Members",
    )

    private fun humanSender() =
        SearchSenderFilterItem(
            id = "member-artin",
            label = "Artin Zaker",
            handle = "artin",
            kind = MemberKind.Human,
            sourceServerId = ServerId("server-1"),
        )

    private fun agentSender() =
        SearchSenderFilterItem(
            id = "member-alex",
            label = "Alex",
            handle = "alex",
            kind = MemberKind.Agent,
            sourceServerId = ServerId("server-1"),
        )

    private fun memberWithoutDmSender() =
        SearchSenderFilterItem(
            id = "member-no-dm",
            label = "No Dm Person",
            handle = "nodm",
            kind = MemberKind.Human,
            sourceServerId = ServerId("server-1"),
        )

    private fun foreignServerSenderSharingMemberId() =
        SearchSenderFilterItem(
            id = "member-artin",
            label = "Foreign Namesake",
            handle = "artin-elsewhere",
            kind = MemberKind.Human,
            sourceServerId = ServerId("server-2"),
        )

    // The DM row's title is the peer's display name in production - that is exactly why the
    // reported screenshot showed two rows both reading "artin". A fixture that gave the DM a
    // distinguishable name would make the duplicate assertions below vacuously true.
    private fun defaultDirectMessages() =
        listOf(
            directMessageRow(
                threadId = "thread-dm-human",
                name = "Artin Zaker",
                peerId = "member-artin",
                peerType = "human"
            ),
            directMessageRow(
                threadId = "thread-dm-agent",
                name = "Alex",
                peerId = "member-alex",
                peerType = "agent"
            )
        )

    private fun directMessageRow(
        threadId: String,
        name: String,
        peerId: String,
        peerType: String,
    ) = HomeDirectMessageRow(
        threadId = ThreadId(threadId),
        channelId = ChannelId(threadId),
        name = name,
        kind = ConversationKind.DirectMessage,
        directMessagePeerType = peerType,
        presenceMemberId = MemberId(peerId),
    )

    private fun homeState(directMessages: List<HomeDirectMessageRow>): SharedHomeState =
        SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = ServerSummary(ServerId("server-1"), "Slock Community"),
                            currentMember =
                                MemberProfile(
                                    id = MemberId("user-1"),
                                    displayName = "Current",
                                    handle = "current",
                                    kind = MemberKind.Human
                                ),
                            channels =
                                listOf(
                                    HomeChannelRow(
                                        threadId = ThreadId("thread-channel"),
                                        channelId = ChannelId("channel-1"),
                                        name = "all-staff-plus",
                                        subtitle = "staff+ level"
                                    )
                                ),
                            directMessages = directMessages,
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
