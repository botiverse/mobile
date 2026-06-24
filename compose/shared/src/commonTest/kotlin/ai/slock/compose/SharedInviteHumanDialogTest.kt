package ai.slock.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedInviteHumanDialogTest {
    @Test
    fun normalizeInviteHumanJoinUrlAddsRaftAppOriginToRelativePaths() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("/join/AB-Rt1m"))
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("join/AB-Rt1m"))
    }

    @Test
    fun normalizeInviteHumanJoinUrlUsesConfiguredWebOriginForRelativePaths() {
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/botiverse/join/AB-Rt1m",
            normalizeInviteHumanJoinUrl("/join/AB-Rt1m", "https://raft-app-staging.botiverse.dev/s/botiverse/")
        )
        assertEquals(
            "https://localhost:5173/join/AB-Rt1m",
            normalizeInviteHumanJoinUrl("join/AB-Rt1m", "https://localhost:5173")
        )
    }

    @Test
    fun normalizeInviteHumanJoinUrlPreservesAbsoluteUrls() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", normalizeInviteHumanJoinUrl("https://app.raft.build/join/AB-Rt1m"))
        assertEquals("http://localhost:5173/join/AB-Rt1m", normalizeInviteHumanJoinUrl("http://localhost:5173/join/AB-Rt1m"))
    }

    @Test
    fun normalizeInviteHumanJoinUrlReturnsNullForBlank() {
        assertNull(normalizeInviteHumanJoinUrl(null))
        assertNull(normalizeInviteHumanJoinUrl("  "))
    }

    @Test
    fun inviteHumanJoinUrlCopyPayloadMatchesDisplayedJoinUrl() {
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("/join/AB-Rt1m"))
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("join/AB-Rt1m"))
        assertEquals(
            "https://raft-app-staging.botiverse.dev/s/botiverse/join/AB-Rt1m",
            inviteHumanJoinUrlCopyPayload("/join/AB-Rt1m", "https://raft-app-staging.botiverse.dev/s/botiverse/")
        )
        assertEquals("https://app.raft.build/join/AB-Rt1m", inviteHumanJoinUrlCopyPayload("https://app.raft.build/join/AB-Rt1m"))
        assertNull(inviteHumanJoinUrlCopyPayload("  "))
    }
}
