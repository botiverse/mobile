package build.raft.app.image

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftImageDiskPolicyTest {
    @Test
    fun captivePortalAndErrorBodiesAreNotImages() {
        assertNull(raftSniffImageMimeType("<!DOCTYPE html><html><body>Sign in to Wi-Fi</body></html>".encodeToByteArray()))
        assertNull(raftSniffImageMimeType("<?xml version=\"1.0\"?><html><svg/></html>".encodeToByteArray()))
        assertNull(raftSniffImageMimeType("{\"error\":\"not found\"}".encodeToByteArray()))
        assertNull(raftSniffImageMimeType(ByteArray(0)))
    }

    @Test
    fun externalEntriesExpireImmutableOnesDoNot() {
        val ttl = RAFT_IMAGE_EXTERNAL_DISK_TTL_MILLIS
        val stored = RaftImageDiskMetadata("image/png", 1_000L)
        assertTrue(raftImageDiskEntryFresh(stored, expires = true, nowEpochMillis = 1_000L + ttl - 1))
        assertFalse(raftImageDiskEntryFresh(stored, expires = true, nowEpochMillis = 1_000L + ttl))
        // A clock that moved backwards does not keep an entry forever.
        assertFalse(raftImageDiskEntryFresh(stored, expires = true, nowEpochMillis = 0L))
        // Legacy entries without a store time revalidate.
        assertFalse(raftImageDiskEntryFresh(RaftImageDiskMetadata("image/png", null), expires = true, nowEpochMillis = 1L))
        assertTrue(raftImageDiskEntryFresh(RaftImageDiskMetadata("image/png", null), expires = false, nowEpochMillis = 1L))
    }
}
