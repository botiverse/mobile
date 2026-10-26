package build.raft.app

import build.raft.app.data.KnownConversationSource
import build.raft.app.data.RaftKnownEntityResolver
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** web useActionCardDetail: the ids an action card stores show as names, never as UUIDs. */
class ThreadActionCardNamesTest {
    private val adaId = "11111111-1111-4111-8111-111111111111"
    private val lumenId = "22222222-2222-4222-8222-222222222222"
    private val strangerId = "33333333-3333-4333-8333-333333333333"

    private val entry =
        SharedServerMembersEntry(
            serverId = "server-1",
            members =
                listOf(
                    MemberProfile(id = MemberId(adaId), displayName = "Ada Lovelace", handle = "ada", kind = MemberKind.Human, presence = Presence.Active),
                    MemberProfile(id = MemberId(lumenId), displayName = "", handle = "lumen", kind = MemberKind.Agent, presence = Presence.Active),
                ),
        )
    private val names = threadActionCardNameLookup(members = entry, serverId = "server-1")

    @Test
    fun peopleResolveToTheirDisplayName() {
        assertEquals("Ada Lovelace", names.displayName(ThreadActionCardEntityKind.Human, adaId, "Unknown member"))
    }

    @Test
    fun agentsResolveToTheirNameWhenTheyHaveNoDisplayName() {
        assertEquals("lumen", names.displayName(ThreadActionCardEntityKind.Agent, lumenId, "Unknown agent"))
    }

    @Test
    fun anIdOfTheWrongKindIsNotResolved() {
        // A person's id on an agents list is not that person.
        assertNull(names.name(ThreadActionCardEntityKind.Agent, adaId))
        assertEquals("Unknown agent", names.displayName(ThreadActionCardEntityKind.Agent, adaId, "Unknown agent"))
    }

    @Test
    fun anUnknownIdShowsTheFallbackNeverTheUuid() {
        assertEquals("Unknown member", names.displayName(ThreadActionCardEntityKind.Human, strangerId, "Unknown member"))
        assertEquals("Unknown agent", ThreadActionCardNameLookup.None.displayName(ThreadActionCardEntityKind.Agent, lumenId, "Unknown agent"))
    }

    @Test
    fun aStoredNameThatIsNotAnIdIsShownAsItIs() {
        assertEquals("Will", names.displayName(ThreadActionCardEntityKind.Human, "Will", "Unknown member"))
    }

    @Test
    fun channelsNeedTheKnownConversationsToResolve() {
        // No account / resolver: the channel stays unknown (the card then omits its row, as web does).
        assertNull(names.name(ThreadActionCardEntityKind.Channel, strangerId))
    }

    @Test
    fun channelsResolveFromTheConversationsTheAppKnowsForThisAccountAndServer() {
        val channelId = "44444444-4444-4444-8444-444444444444"
        val known =
            RaftKnownEntityResolver(
                listOf(
                    KnownConversationSource { scope, id ->
                        if (scope.accountId != "account-1" || id != channelId) {
                            null
                        } else {
                            SharedConversationDirectoryProjection(
                                serverId = scope.serverId.value,
                                canonicalChannelId = channelId,
                                aliases = emptySet(),
                                conversationKind = ConversationKind.Channel,
                                surfaceKind = "channel",
                                channelKind = "channel",
                                title = "#release-review",
                            )
                        }
                    }
                )
            )
        val withChannels =
            threadActionCardNameLookup(members = entry, serverId = "server-1", knownEntities = known, accountId = "account-1")

        assertEquals("release-review", withChannels.name(ThreadActionCardEntityKind.Channel, channelId))
        assertNull(
            threadActionCardNameLookup(members = entry, serverId = "server-1", knownEntities = known, accountId = "account-2")
                .name(ThreadActionCardEntityKind.Channel, channelId)
        )
    }
}
