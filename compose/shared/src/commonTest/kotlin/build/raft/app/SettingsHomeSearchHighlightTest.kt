package build.raft.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    fun highlightedAnnotatedStringKeepsOneTextNodeWithSearchHitRanges() {
        val annotated = homeSearchHighlightedAnnotatedString(
            text = "Find keyword here",
            query = "keyword"
        )

        assertEquals("Find keyword here", annotated.text)
        assertEquals(
            listOf(
                5 until 12
            ),
            annotated.spanStyles.map { it.start until it.end }
        )
        // raft-ui search hit: bold sticker-ink type; the brand sticker is drawn behind it.
        val hit = annotated.spanStyles.single().item
        assertEquals(RaftColor.StickerInk, hit.color)
        assertEquals(raftFontFamilyForWeight(FontWeight.Bold), hit.fontFamily)
        assertEquals(Color.Unspecified, hit.background)
    }

    @Test
    fun searchResultMetaUsesRaftMonoMetaFont() {
        val meta = sharedHomeSearchTimestampTextStyle()

        assertEquals(RaftMonoFamily, meta.fontFamily)
        assertEquals(12.sp, meta.fontSize)
        assertEquals(16.sp, meta.lineHeight)
    }
}
