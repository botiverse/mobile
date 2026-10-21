package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedConversationIdentityHubTest {
    @Test
    fun canonicalDestinationDetailOverridesHomeAndOpenedDmCandidates() {
        val hub = SharedConversationIdentityHub()
        val authority = authority(authGeneration = 7, selectionGeneration = 11)
        val candidate = dmProjection(name = "Candidate Cindy", avatar = "pixel:candidate")
        val canonical = dmProjection(name = "Canonical Cindy", avatar = "pixel:canonical")

        assertTrue(
            hub.publishCandidate(
                authority = authority.copy(selectionGeneration = 0),
                revision = 1,
                next = listOf(candidate).toSharedConversationDirectoryState(),
            )
        )
        assertEquals("Candidate Cindy", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)

        assertTrue(hub.publishCanonical(authority, canonical))
        assertEquals("Canonical Cindy", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)
        assertEquals("pixel:canonical", hub.resolveProjection(ServerId("server-1"), "dm-1")?.avatarWireValue)

        assertTrue(
            hub.publishCandidate(
                authority = authority.copy(selectionGeneration = 0),
                revision = 2,
                next =
                    listOf(dmProjection(name = "Later candidate", avatar = "pixel:later"))
                        .toSharedConversationDirectoryState(),
            )
        )
        assertEquals(
            "Canonical Cindy",
            hub.resolveProjection(ServerId("server-1"), "dm-1")?.title,
            "A later Home/#2045 candidate must not regain authority over destination detail",
        )
    }

    @Test
    fun staleEnvironmentAccountAuthAndSelectionPublicationsFailClosed() {
        val hub = SharedConversationIdentityHub()
        val current = authority(authGeneration = 8, selectionGeneration = 20)
        assertTrue(hub.publishCanonical(current, dmProjection("Current", "pixel:current")))

        assertFalse(
            hub.publishCanonical(
                current.copy(environmentId = "https://other.example", authGeneration = 7),
                dmProjection("Wrong environment", null),
            )
        )
        assertFalse(
            hub.publishCanonical(
                current.copy(accountId = "account-old", authGeneration = 7),
                dmProjection("Wrong account", null),
            )
        )
        assertFalse(
            hub.publishCanonical(
                current.copy(authGeneration = 7),
                dmProjection("Old auth", null),
            )
        )
        assertFalse(
            hub.publishCanonical(
                current.copy(selectionGeneration = 19),
                dmProjection("Old selection", null),
            )
        )
        assertEquals("Current", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)

        assertTrue(
            hub.publishCanonical(
                current.copy(selectionGeneration = 21),
                dmProjection("New selection", "pixel:new"),
            )
        )
        assertEquals("New selection", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)

        assertTrue(
            hub.publishCandidate(
                authority =
                    current.copy(
                        accountId = "account-2",
                        authGeneration = 9,
                        selectionGeneration = 0,
                    ),
                revision = 1,
                next =
                    listOf(dmProjection("Second account", null))
                        .toSharedConversationDirectoryState(),
            )
        )
        assertEquals("Second account", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)
        assertFalse(
            hub.publishCanonical(
                current.copy(authGeneration = 8, selectionGeneration = 22),
                dmProjection("Late first account", null),
            )
        )
        assertEquals("Second account", hub.resolveProjection(ServerId("server-1"), "dm-1")?.title)
    }

    @Test
    fun clearDropsEveryShellOwnedCandidateAndCanonicalProjection() {
        val hub = SharedConversationIdentityHub()
        assertTrue(hub.publishCanonical(authority(), dmProjection("Cindy", "pixel:cindy")))

        hub.clear()

        assertNull(hub.resolveProjection(ServerId("server-1"), "dm-1"))
    }

    private fun authority(
        authGeneration: Long = 1,
        selectionGeneration: Long = 1,
    ): SharedConversationIdentityAuthority =
        SharedConversationIdentityAuthority(
            environmentId = "https://api.example.test",
            accountId = "account-1",
            serverId = "server-1",
            authGeneration = authGeneration,
            selectionGeneration = selectionGeneration,
        )

    private fun dmProjection(
        name: String,
        avatar: String?,
    ): SharedConversationDirectoryProjection =
        ConversationSummary(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            kind = ConversationKind.DirectMessage,
            name = name,
            directMessagePeerType = "human",
            directMessagePeerAvatarWireValue = avatar,
            presenceMemberId = MemberId("peer-1"),
        ).toCanonicalConversationDirectoryProjection(ServerId("server-1"))!!
}
