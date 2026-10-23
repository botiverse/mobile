package build.raft.app.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RaftImageSourceDataUriTest {
    @Test
    fun percentEncodedSvgDataUriDecodesToInlineSvg() {
        listOf("data:image/svg+xml;utf8,", "data:image/svg+xml,", "DATA:image/SVG+xml;charset=utf-8,").forEach { prefix ->
            val source = raftImageSourceOf(prefix + EncodedSvg)
            assertIs<RaftImageSource.InlineSvg>(source, "prefix=$prefix")
            assertEquals(DecodedSvg, source.text, "prefix=$prefix")
        }
    }

    @Test
    fun base64AndNonSvgDataUrisStayWithCoil() {
        assertNull(raftImageSourceOf("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="))
        assertNull(raftImageSourceOf("data:image/png;utf8,$EncodedSvg"))
    }

    @Test
    fun malformedSvgDataUriIsNotClaimed() {
        assertNull(raftImageSourceOf("data:image/svg+xml;utf8"), "no payload separator")
        assertNull(raftImageSourceOf("data:image/svg+xml;utf8,%3Csvg%2"), "truncated escape")
        assertNull(raftImageSourceOf("data:image/svg+xml;utf8,"), "empty payload")
    }

    private companion object {
        const val DecodedSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4"><rect width="4" height="4" fill="#FDE047"/></svg>"""
        const val EncodedSvg =
            "%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%204%204%22%3E" +
                "%3Crect%20width%3D%224%22%20height%3D%224%22%20fill%3D%22%23FDE047%22%2F%3E%3C%2Fsvg%3E"
    }
}
