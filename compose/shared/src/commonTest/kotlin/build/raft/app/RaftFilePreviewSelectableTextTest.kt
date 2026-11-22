package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftFilePreviewSelectableTextTest {
    @Test
    fun markdownAndTextPreviewsExposeTheirSourceText() {
        assertEquals(
            "# Title\n\nbody",
            RaftFilePreviewPayload(kind = "markdown", content = "# Title\n\nbody").selectableSourceText()
        )
        assertEquals("plain", RaftFilePreviewPayload(kind = "text", content = "plain").selectableSourceText())
    }
}
