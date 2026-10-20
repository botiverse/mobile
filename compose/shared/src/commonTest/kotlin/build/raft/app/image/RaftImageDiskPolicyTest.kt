package build.raft.app.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftImageDiskPolicyTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun sniffsEveryDecodableFormat() {
        assertEquals("image/png", raftSniffImageMimeType(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A)))
        assertEquals("image/jpeg", raftSniffImageMimeType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/gif", raftSniffImageMimeType("GIF89a....".encodeToByteArray()))
        assertEquals("image/webp", raftSniffImageMimeType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertEquals("image/heic", raftSniffImageMimeType("\u0000\u0000\u0000\u0018ftypheic".encodeToByteArray()))
        assertEquals("image/avif", raftSniffImageMimeType("\u0000\u0000\u0000\u001cftypavif".encodeToByteArray()))
        assertEquals("image/svg+xml", raftSniffImageMimeType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()))
        assertEquals(
            "image/svg+xml",
            raftSniffImageMimeType("﻿<?xml version=\"1.0\"?>\n<!-- x -->\n<svg></svg>".encodeToByteArray()),
        )
    }

    @Test
    fun captivePortalAndErrorBodiesAreNotImages() {
        assertNull(raftSniffImageMimeType("<!DOCTYPE html><html><body>Sign in to Wi-Fi</body></html>".encodeToByteArray()))
        assertNull(raftSniffImageMimeType("<?xml version=\"1.0\"?><html><svg/></html>".encodeToByteArray()))
        assertNull(raftSniffImageMimeType("{\"error\":\"not found\"}".encodeToByteArray()))
        assertNull(raftSniffImageMimeType(ByteArray(0)))
    }

    @Test
    fun metadataRoundTripsAndReadsLegacyMimeOnlyEntries() {
        val metadata = RaftImageDiskMetadata("image/png", 1_700_000_000_000)
        assertEquals(metadata, RaftImageDiskMetadata.decode(metadata.encode()))
        assertEquals(RaftImageDiskMetadata("image/jpeg", null), RaftImageDiskMetadata.decode("image/jpeg"))
        assertEquals(RaftImageDiskMetadata(null, null), RaftImageDiskMetadata.decode(null))
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

    @Test
    fun onlyRaftImmutableAvatarUrlsNeverExpire() {
        assertTrue(raftNetworkImageExpires("https://www.gravatar.com/avatar/0123456789abcdef0123456789abcdef?s=96"))
        assertTrue(raftNetworkImageExpires("https://example.com/logo.png"))
        assertFalse(raftNetworkImageExpires("https://app.raft.build/api/avatars/users/0123456789abcdef0123456789abcdef.webp"))
    }
}
