package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockKuiklyNetworkModuleTest {
    @Test
    fun multipartFileContentDispositionKeepsFormFieldNameForStreamedFiles() {
        val header =
                slockMultipartFileContentDisposition(
                        fieldName = "files",
                        fileName = "picked\"photo\r\n.png"
                )

        assertEquals(
                "form-data; name=\"files\"; filename=\"picked'photo.png\"",
                header
        )
    }
}
