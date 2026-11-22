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
}
