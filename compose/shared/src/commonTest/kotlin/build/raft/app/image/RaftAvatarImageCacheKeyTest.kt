package build.raft.app.image

import build.raft.app.RaftAvatarFailureCooldown
import build.raft.app.raftAvatarImageCandidates
import build.raft.app.data.RaftAvatarContentKind
import build.raft.app.data.RaftAvatarTrustedHosts
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
    fun raftAvatarKeyIgnoresHostQueryAndEndpoint() {
        val cdn = "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val keys =
            listOf(
                cdn,
                "$cdn?v=1700000000&sig=abc",
                "https://api.raft.build/api/avatars/users/0123456789abcdef0123456789abcdef.webp",
                "https://app.raft.build/api/avatars/users/0123456789ABCDEF0123456789ABCDEF.webp#frag",
            ).map(::raftNetworkImageCacheKey).toSet()

        assertEquals(1, keys.size, keys.toString())
        assertEquals(keys.single(), raftNetworkImageCacheKey(cdn), "the key is a pure function of the content")
    }

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
    fun activeEnvironmentHostsAreTrustedForContentKeys() {
        val stagingCdn = "https://cdn.botiverse.dev/avatars/agents/0123456789abcdef0123456789abcdef.webp"
        val stagingApi = "https://api-aws-staging.botiverse.dev/api/avatars/agents/0123456789abcdef0123456789abcdef.webp"
        try {
            RaftAvatarTrustedHosts.configure(apiBaseUrl = null, webUrl = null)
            assertEquals(RaftAvatarContentKind.ExternalUrl, raftAvatarContentIdentity(stagingApi).kind)

            RaftAvatarTrustedHosts.configure(
                apiBaseUrl = "https://api-aws-staging.botiverse.dev/api/",
                webUrl = "https://raft-app-staging.botiverse.dev",
            )
            assertEquals(RaftAvatarContentKind.RaftImmutable, raftAvatarContentIdentity(stagingApi).kind)
            assertEquals(raftNetworkImageCacheKey(stagingApi), raftNetworkImageCacheKey(stagingCdn))
        } finally {
            RaftAvatarTrustedHosts.configure(apiBaseUrl = null, webUrl = null)
        }
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
    fun failureCooldownIsSharedByContentAndBacksOff() {
        var now = 0L
        val cooldown = RaftAvatarFailureCooldown(baseCooldownMillis = 1_000L, maxCooldownMillis = 3_000L, nowMillis = { now })
        val key = raftNetworkImageCacheKey("https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp")
        val apiKey = raftNetworkImageCacheKey("https://api.raft.build/api/avatars/users/0123456789abcdef0123456789abcdef.webp")

        assertEquals(0L, cooldown.retryDelayMillis(key))
        cooldown.recordFailure(key)
        assertEquals(1_000L, cooldown.retryDelayMillis(apiKey), "same content, same cooldown")
        now = 1_000L
        assertEquals(0L, cooldown.retryDelayMillis(key))
        cooldown.recordFailure(key)
        assertEquals(2_000L, cooldown.retryDelayMillis(key))
        cooldown.recordFailure(key)
        assertEquals(3_000L, cooldown.retryDelayMillis(key), "capped")
        cooldown.recordSuccess(key)
        assertEquals(0L, cooldown.retryDelayMillis(key))
    }
}
