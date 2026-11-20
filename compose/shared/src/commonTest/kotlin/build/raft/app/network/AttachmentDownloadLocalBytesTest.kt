package build.raft.app.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #proj-mobile:#202: a page's saved bytes are served once, for their `raft-local:` address only. */
class AttachmentDownloadLocalBytesTest {
    @AfterTest
    fun reset() = AttachmentDownloadLocalBytes.clearForTest()

    @Test
    fun onlyLocalAddressesAreKept() {
        val source = AttachmentDownloadLocalBytes.Source(byteArrayOf(1, 2), "a.txt", "text/plain")
        AttachmentDownloadLocalBytes.register("d1", "https://app.raft.build/x", source)
        assertNull(AttachmentDownloadLocalBytes.take("d1", "https://app.raft.build/x"))
        assertTrue(AttachmentDownloadLocalBytes.isLocal("raft-local://download/x"))
        assertFalse(AttachmentDownloadLocalBytes.isLocal("https://app.raft.build/x"))
    }

    @Test
    fun takenOnceForTheSameAddress() {
        val source = AttachmentDownloadLocalBytes.Source(byteArrayOf(1, 2), "a.txt", null)
        AttachmentDownloadLocalBytes.register("d1", "raft-local://download/x", source)
        assertNull(AttachmentDownloadLocalBytes.take("d1", "raft-local://download/y"))
        AttachmentDownloadLocalBytes.register("d1", "raft-local://download/x", source)
        assertEquals(source, AttachmentDownloadLocalBytes.take("d1", "raft-local://download/x"))
        assertNull(AttachmentDownloadLocalBytes.take("d1", "raft-local://download/x"))
    }

    @Test
    fun contentDispositionRoundTripsAChineseName() {
        val header = raftAttachmentContentDisposition("周报 v3.zip")
        assertEquals("周报 v3.zip", raftContentDispositionFileName(header))
    }
}
