package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentPublicDownloadTest {
    @Test
    fun publicDownloadDisplayNamePreservesSafeOriginalName() {
        assertEquals(
            "release notes.pdf",
            publicDownloadDisplayName("folder/release notes.pdf", "pdf")
        )
    }

    @Test
    fun publicDownloadDisplayNameSanitizesPathAndAddsMimeExtension() {
        assertEquals(
            "report_2026.pdf",
            publicDownloadDisplayName("../report:2026", "pdf")
        )
    }

    @Test
    fun publicDownloadDisplayNameFallsBackForOnlyUnsafeCharacters() {
        assertEquals(
            "attachment.zip",
            publicDownloadDisplayName("///", "zip")
        )
    }
}
