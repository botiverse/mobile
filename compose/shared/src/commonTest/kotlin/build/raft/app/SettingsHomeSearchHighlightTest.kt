package build.raft.app

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
    fun highlightRangesPreferLongerOverlappingTerms() {
        val ranges = homeSearchHighlightRanges(
            text = "server serverless service",
            query = "server serverless"
        )

        assertEquals(listOf(0 until 6, 7 until 17), ranges)
    }

    @Test
    fun highlightSegmentsKeepMatchedTextSeparateForVisibleBackground() {
        val segments = homeSearchHighlightSegments(
            text = "Find keyword here",
            query = "keyword"
        )

        assertEquals(
            listOf(
                HomeSearchHighlightSegment("Find ", highlighted = false),
                HomeSearchHighlightSegment("keyword", highlighted = true),
                HomeSearchHighlightSegment(" here", highlighted = false)
            ),
            segments
        )
    }
}
