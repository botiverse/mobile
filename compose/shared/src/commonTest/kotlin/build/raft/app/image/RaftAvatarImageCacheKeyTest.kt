package build.raft.app.image

import build.raft.app.RaftAvatarImageAttempt
import build.raft.app.RaftAvatarImageMaxRetries
import build.raft.app.raftAvatarImageCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Hands dd79da45: avatars are cached by Coil under a CONTENT key, so the disk entry written in one
 * launch is hit in the next one regardless of which server, host or endpoint the URL came from.
 */
class RaftAvatarImageCacheKeyTest {
    @Test
    fun raftAvatarKeyIgnoresHostQueryAndEndpoint() {
        val cdn = "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val keys =
            listOf(
                cdn,
                "$cdn?v=1700000000&sig=abc",
                "https://api.raft.build/api/avatars/users/0123456789abcdef0123456789abcdef.webp",
                "https://cdn-eu.raft.build/avatars/users/0123456789ABCDEF0123456789ABCDEF.webp#frag",
            ).map(::raftNetworkImageCacheKey).toSet()

        assertEquals(1, keys.size, keys.toString())
        assertEquals(keys.single(), raftNetworkImageCacheKey(cdn), "the key is a pure function of the content")
    }

    @Test
    fun differentContentOrNamespaceGetsADifferentKey() {
        val base = raftNetworkImageCacheKey("https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp")
        assertNotEquals(
            base,
            raftNetworkImageCacheKey("https://cdn.raft.build/avatars/users/fedcba9876543210fedcba9876543210.webp")
        )
        assertNotEquals(
            base,
            raftNetworkImageCacheKey("https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp")
        )
    }

    @Test
    fun gravatarKeyIsTheHashPlusItsQuery() {
        val hash = "0123456789abcdef0123456789abcdef"
        assertEquals(
            raftNetworkImageCacheKey("https://www.gravatar.com/avatar/$hash?d=identicon"),
            raftNetworkImageCacheKey("https://secure.gravatar.com/avatar/$hash?d=identicon"),
        )
        assertNotEquals(
            raftNetworkImageCacheKey("https://www.gravatar.com/avatar/$hash?d=identicon"),
            raftNetworkImageCacheKey("https://www.gravatar.com/avatar/$hash?d=identicon&s=256"),
        )
    }

    @Test
    fun cdnAvatarFallsBackToTheApiHostWithTheSameCacheKey() {
        val cdn = "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp"
        val candidates = raftAvatarImageCandidates(cdn, "https://api.raft.build/api")

        assertEquals(
            listOf(cdn, "https://api.raft.build/api/avatars/agents/0123456789abcdef0123456789abcdef.webp"),
            candidates,
        )
        assertEquals(1, candidates.map(::raftNetworkImageCacheKey).toSet().size)
        assertEquals(listOf("https://example.com/a.png"), raftAvatarImageCandidates("https://example.com/a.png", "https://api.raft.build/api"))
    }

    @Test
    fun failedLoadsWalkCandidatesThenRetryARoundedNumberOfTimes() {
        var attempt: RaftAvatarImageAttempt? = RaftAvatarImageAttempt()
        val seen = mutableListOf<RaftAvatarImageAttempt>()
        while (attempt != null) {
            seen += attempt
            attempt = attempt.next(candidateCount = 2)
        }

        assertEquals((RaftAvatarImageMaxRetries + 1) * 2, seen.size)
        assertEquals(RaftAvatarImageAttempt(candidateIndex = 1, retry = 0), seen[1])
        assertEquals(RaftAvatarImageAttempt(candidateIndex = 0, retry = 1), seen[2])
        assertNull(RaftAvatarImageAttempt(candidateIndex = 0, retry = RaftAvatarImageMaxRetries).next(candidateCount = 1))
    }
}
