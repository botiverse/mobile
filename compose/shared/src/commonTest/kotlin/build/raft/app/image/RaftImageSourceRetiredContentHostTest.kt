package build.raft.app.image

import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RaftImageSourceRetiredContentHostTest {
    @Test
    fun storedThumbnailOnRetiredHostLoadsFromCdnUnderTheCdnCacheKey() {
        // A message row stored before 2026-11-09 still carries the old R2 public domain.
        val stored =
            Attachment(
                id = AttachmentId("att-1"),
                name = "photo.png",
                mimeType = "image/png",
                sizeLabel = "12 KB",
                thumbnailUrl = " https://content.raft.build/attachments/srv-1/att-1/thumb.webp?v=2 "
            )
        val current = "https://cdn.raft.build/attachments/srv-1/att-1/thumb.webp?v=2"

        val source = assertIs<RaftImageSource.Network>(raftImageSourceOf(stored.thumbnailUrl!!))

        assertEquals(current, source.url)
        val fresh = assertIs<RaftImageSource.Network>(raftImageSourceOf(current))
        assertEquals(fresh.cacheKey, source.cacheKey, "old and new payloads must share one cache entry")
    }

    @Test
    fun avatarOnRetiredHostSharesTheContentAddressedCdnAvatarKey() {
        val hash = "0123456789abcdef0123456789abcdef"
        val old = assertIs<RaftImageSource.Network>(raftImageSourceOf("https://content.raft.build/avatars/users/$hash.webp"))
        val cdn = assertIs<RaftImageSource.Network>(raftImageSourceOf("https://cdn.raft.build/avatars/users/$hash.webp"))

        assertEquals("https://cdn.raft.build/avatars/users/$hash.webp", old.url)
        assertEquals(cdn.cacheKey, old.cacheKey)
    }

    @Test
    fun otherHttpImagesKeepTheirUrl() {
        listOf(
            "http://content.raft.build/a.png",
            "https://img.content.raft.build/a.png",
            "https://slock-cdn-staging.botiverse.dev/a.png",
        ).forEach { url ->
            assertEquals(url, assertIs<RaftImageSource.Network>(raftImageSourceOf(url)).url, url)
        }
    }
}
