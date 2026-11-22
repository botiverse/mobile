package build.raft.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentMediaStorePendingRowsTest {
    @Test
    fun publishedDisplayNameIsNotEligibleForRestartCleanup() {
        assertFalse(isAppOwnedPendingAttachmentName("report.pdf"))
        assertFalse(isAppOwnedPendingAttachmentName("raft-pending-report.pdf"))
    }
}
