package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftFilePreviewSelectableTextTest {
    @Test
    fun markdownAndTextPreviewsExposeTheirSourceText() {
        assertEquals(
            "# Title\n\nbody",
            RaftFilePreviewPayload(kind = "markdown", content = "# Title\n\nbody").selectableSourceText()
        )
        assertEquals("plain", RaftFilePreviewPayload(kind = "text", content = "plain").selectableSourceText())
    }

    @Test
    fun otherKindsAndBlankContentOfferNoSelection() {
        assertNull(RaftFilePreviewPayload(kind = "markdown", content = "  \n").selectableSourceText())
        assertNull(RaftFilePreviewPayload(kind = "html", content = "<p>x</p>").selectableSourceText())
        assertNull(RaftFilePreviewPayload(kind = "image", content = "x").selectableSourceText())
    }
}
