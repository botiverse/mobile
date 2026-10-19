package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockFilePreviewSelectableTextTest {
    @Test
    fun markdownAndTextPreviewsExposeTheirSourceText() {
        assertEquals(
            "# Title\n\nbody",
            SlockFilePreviewPayload(kind = "markdown", content = "# Title\n\nbody").selectableSourceText()
        )
        assertEquals("plain", SlockFilePreviewPayload(kind = "text", content = "plain").selectableSourceText())
    }

    @Test
    fun otherKindsAndBlankContentOfferNoSelection() {
        assertNull(SlockFilePreviewPayload(kind = "markdown", content = "  \n").selectableSourceText())
        assertNull(SlockFilePreviewPayload(kind = "html", content = "<p>x</p>").selectableSourceText())
        assertNull(SlockFilePreviewPayload(kind = "image", content = "x").selectableSourceText())
    }
}
