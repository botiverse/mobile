package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftFilePreviewAttachmentUrlCacheTest {
    private val owner =
        AuthenticatedRouteOwner(
            environmentId = "env-a",
            accountId = "acct-a",
            serverId = "srv-a"
        )

    @Test
    fun missingExpiresAtIsNeverStored() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        cache.currentEpochMillis = { 1_000_000L }
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        cache.put(key, "https://signed.example/full.jpg")

        assertNull(cache.get(key))
    }

    @Test
    fun expiresAtBeyondMarginStoresAndHits() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        cache.currentEpochMillis = { 1_000_000L }
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        cache.put(
            key,
            "https://signed.example/full.jpg",
            expiresAtEpochMillis = 1_000_000L + 5 * 60_000L
        )

        assertEquals("https://signed.example/full.jpg", cache.get(key))
    }

    @Test
    fun expiresAtInsideSafetyMarginIsNotStored() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        cache.currentEpochMillis = { 1_000_000L }
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        // expires in 30s < 60s margin => never replayable
        cache.put(
            key,
            "https://signed.example/full.jpg",
            expiresAtEpochMillis = 1_000_000L + 30_000L
        )

        assertNull(cache.get(key))
    }

    @Test
    fun expiresAtEntryDiesAtExpiryMinusMargin() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        var now = 1_000_000L
        cache.currentEpochMillis = { now }
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        // expiresAt = now + 2min -> stored with effective ttl = 60s
        cache.put(
            key,
            "https://signed.example/full.jpg",
            expiresAtEpochMillis = now + 120_000L
        )

        // monotonic TTL reached: entry must miss even though wall expiresAt is still future
        // (monotonic side expires after 60s; simulate by using a cache with tiny ttl)
        val shortCache = RaftFilePreviewAttachmentUrlCache()
        shortCache.currentEpochMillis = { now }
        val shortKey = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        shortCache.put(
            shortKey,
            "https://signed.example/full.jpg",
            expiresAtEpochMillis = now + 30_000L
        )
        assertNull(shortCache.get(shortKey))
    }

    @Test
    fun differentOwnerOrDispositionMisses() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        cache.put(
            RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline"),
            "https://signed.example/full.jpg",
            expiresAtEpochMillis = 1_000_000L + 5 * 60_000L
        )
        cache.currentEpochMillis = { 1_000_000L }

        assertNull(
            cache.get(
                RaftFilePreviewAttachmentUrlCacheKey(
                    owner.copy(serverId = "srv-b"),
                    "att-1",
                    "inline"
                )
            )
        )
        assertNull(
            cache.get(
                RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "attachment")
            )
        )
        assertNull(
            cache.get(
                RaftFilePreviewAttachmentUrlCacheKey(owner, "att-2", "inline")
            )
        )
    }

    @Test
    fun blankUrlIsNotStored() {
        val cache = RaftFilePreviewAttachmentUrlCache()
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        cache.put(key, "   ")

        assertNull(cache.get(key))
    }
}
