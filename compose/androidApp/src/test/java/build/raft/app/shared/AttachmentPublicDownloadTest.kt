package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPublicDownloadTest {
    @Test
    fun publicDownloadDisplayNameSanitizesPathAndAddsMimeExtension() {
        assertEquals(
            "report_2026.pdf",
            publicDownloadDisplayName("../report:2026", "pdf")
        )
    }
}
