package build.raft.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentMediaStorePendingRowsTest {
    @Test
    fun pendingNameCarriesAppOwnedOperationToken() {
        val name = pendingAttachmentMediaStoreDisplayName("abc123", "report.pdf")

        assertTrue(isAppOwnedPendingAttachmentName(name))
        assertTrue(name.startsWith(".raft-pending-abc123-"))
        assertTrue(name.endsWith("report.pdf"))
    }

    @Test
    fun publishedDisplayNameIsNotEligibleForRestartCleanup() {
        assertFalse(isAppOwnedPendingAttachmentName("report.pdf"))
        assertFalse(isAppOwnedPendingAttachmentName("raft-pending-report.pdf"))
    }
}
