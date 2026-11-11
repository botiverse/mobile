package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * External channel invite links (slock `emailService.externalChannelInviteUrl`:
 * `{app}/external-invite/{encodeURIComponent(token)}`; web `externalLinkSignIn`).
 */
class RaftExternalInviteLinkTest {
    @Test
    fun theInviteLinkOnARaftOriginGivesItsToken() {
        assertEquals("tok_AbC-123", parseRaftExternalInviteLink("https://app.raft.build/external-invite/tok_AbC-123", appHosts = emptySet()))
        assertEquals("tok", parseRaftExternalInviteLink("https://open.raft.build/external-invite/tok/", appHosts = emptySet()))
        assertEquals("a b", parseRaftExternalInviteLink("https://app.raft.build/external-invite/a%20b?utm=x", appHosts = emptySet()))
        // The Developer environment's web host, only when configured.
        assertEquals("t", parseRaftExternalInviteLink("http://localhost:5173/external-invite/t", "http://localhost:5173", emptySet()))
        assertNull(parseRaftExternalInviteLink("http://localhost:5173/external-invite/t", appHosts = emptySet()))
    }

    @Test
    fun otherPathsAndHostsAreNotClaimed() {
        listOf(
            "https://app.raft.build/external-invite/",
            "https://app.raft.build/external-invite",
            "https://app.raft.build/external-invite/t/extra",
            "https://app.raft.build/s/acme/external-invite/t",
            "https://app.raft.build/server-connect-requests/r1",
            "https://example.com/external-invite/t",
            "https://app.raft.build/external-invite/%ZZ",
        ).forEach { url -> assertNull(parseRaftExternalInviteLink(url, appHosts = emptySet()), url) }
    }

    @Test
    fun theInboxKeepsTheNewestLinkUntilTakenOnce() {
        var now = 0L
        val inbox = RaftExternalInviteLinkInbox(nowEpochMillis = { now })
        inbox.submit("old", "android_view")
        val newest = inbox.submit("new", "android_view")
        assertEquals("new", inbox.take(newest.id)?.token)
        assertNull(inbox.take(newest.id))
        val late = inbox.submit("late", "android_view")
        now = RaftMessageLinkInbox.DefaultTtlMillis + 1
        assertNull(inbox.take(late.id))
    }
}
