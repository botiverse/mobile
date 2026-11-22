package build.raft.app.image

import build.raft.app.data.RaftAvatarContentKind
import build.raft.app.data.raftAvatarContentIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Hands dd79da45: avatars are cached by Coil under a CONTENT key, so the disk entry written in one
 * launch is hit in the next one regardless of which server, host or endpoint the URL came from.
 */
class RaftAvatarImageCacheKeyTest {
    @Test
    fun foreignHostCannotClaimARaftAvatarContentKey() {
        val real = "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val spoof = "https://evil.example/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val spoofLookalike = "https://cdn.raft.build.evil.example/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val spoofUserInfo = "https://cdn.raft.build@evil.example/avatars/users/0123456789abcdef0123456789abcdef.webp"

        listOf(spoof, spoofLookalike, spoofUserInfo).forEach { url ->
            assertNotEquals(raftNetworkImageCacheKey(real), raftNetworkImageCacheKey(url), url)
            assertEquals(RaftAvatarContentKind.ExternalUrl, raftAvatarContentIdentity(url).kind, url)
            assertTrue(raftNetworkImageExpires(url), "a foreign host's entry must expire: $url")
        }
        assertEquals(RaftAvatarContentKind.RaftImmutable, raftAvatarContentIdentity(real).kind)
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
}
