package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockKuiklyNetworkModuleTest {
    @Test
    fun multipartFileContentDispositionLetsKtorProvideFormFieldName() {
        val header =
                slockMultipartFileContentDisposition(
                        fileName = "picked\"photo\r\n.png"
                )

        assertEquals(
                "filename=\"picked'photo.png\"",
                header
        )
    }
}
