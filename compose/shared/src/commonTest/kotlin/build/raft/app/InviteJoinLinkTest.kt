package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InviteJoinLinkTest {
    @Test
    fun acceptsOnlyTheProductionSingleTokenPath() {
        assertEquals(
            SlockInviteJoinIntent("Z8fCNaIREN41HXqzYdg1cw"),
            parseSlockInviteJoinUrl("https://app.raft.build/join/Z8fCNaIREN41HXqzYdg1cw"),
        )
        assertEquals(
            SlockInviteJoinIntent("AB-Rt1m_9"),
            parseSlockInviteJoinUrl("https://app.raft.build/join/AB-Rt1m_9"),
        )
    }

    @Test
    fun enforcesTheTokenLengthBoundary() {
        val maxLengthToken = "a".repeat(256)
        val overLimitToken = "a".repeat(257)

        assertEquals(
            SlockInviteJoinIntent(maxLengthToken),
            parseSlockInviteJoinUrl("https://app.raft.build/join/$maxLengthToken"),
        )
        assertNull(parseSlockInviteJoinUrl("https://app.raft.build/join/$overLimitToken"))
    }

    @Test
    fun enforcesThePendingTokenNormalizationBoundary() {
        val maxLengthToken = "b".repeat(256)
        val overLimitToken = "b".repeat(257)

        assertEquals(maxLengthToken, normalizeSlockInviteTokenOrNull(maxLengthToken))
        assertNull(normalizeSlockInviteTokenOrNull(overLimitToken))
        listOf(
            null,
            "",
            " $maxLengthToken",
            "$maxLengthToken ",
            "a/b",
            "a%2Fb",
        ).forEach { raw -> assertNull(normalizeSlockInviteTokenOrNull(raw), raw) }
    }

    @Test
    fun rejectsNonProductionOrContentBearingUrls() {
        listOf(
            null,
            "",
            " https://app.raft.build/join/token",
            "https://app.raft.build/join/token ",
            "http://app.raft.build/join/token",
            "https://www.raft.build/join/token",
            "https://app.raft.build/join/token?next=1",
            "https://app.raft.build/join/token#fragment",
            "https://app.raft.build/join/token/extra",
            "https://app.raft.build/join/",
            "https://app.raft.build/join/a%2Fb",
            "https://app.raft.build/Join/token",
        ).forEach { raw -> assertNull(parseSlockInviteJoinUrl(raw), raw) }
    }
}
