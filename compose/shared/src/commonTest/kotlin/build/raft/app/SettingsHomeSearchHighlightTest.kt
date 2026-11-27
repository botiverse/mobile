package build.raft.app

import build.raft.app.settings.homeSearchHighlightRanges
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsHomeSearchHighlightTest {
    @Test
    fun highlightRangesMatchQueryTermsCaseInsensitively() {
        val ranges = homeSearchHighlightRanges(
            text = "Release notes mention raft release status",
            query = "release RAFT"
        )

        assertEquals(listOf(0 until 7, 22 until 26, 27 until 34), ranges)
    }

    @Test
    fun highlightRangesIndexTheOriginalTextWhenLowercaseChangesLength() {
        // "İ".lowercase() is two chars ("i" + combining dot), so ranges found in the lowercased
        // copy would drift past the original text (and overrun it at the end).
        val text = "İstanbul İzmir"
        val ranges = homeSearchHighlightRanges(text = text, query = "izmir")

        assertEquals(listOf(9 until 14), ranges)
        assertEquals("İzmir", text.substring(ranges.single().first, ranges.single().last + 1))
        assertEquals(listOf(0 until 1), homeSearchHighlightRanges(text = "İ", query = "i"))
    }
}
