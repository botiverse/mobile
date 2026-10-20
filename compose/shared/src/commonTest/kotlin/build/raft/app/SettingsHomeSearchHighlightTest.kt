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
    fun highlightRangesIndexTheOriginalTextWhenLowercaseChangesLength() {
        // "İ".lowercase() is two chars ("i" + combining dot), so ranges found in the lowercased
        // copy would drift past the original text (and overrun it at the end).
        val text = "İstanbul İzmir"
        val ranges = homeSearchHighlightRanges(text = text, query = "izmir")

        assertEquals(listOf(9 until 14), ranges)
        assertEquals("İzmir", text.substring(ranges.single().first, ranges.single().last + 1))
        assertEquals(listOf(0 until 1), homeSearchHighlightRanges(text = "İ", query = "i"))
    }

    @Test
    fun highlightRangesMergeTouchingHitsIntoOneSticker() {
        data class Case(val text: String, val query: String, val expected: List<IntRange>)
        listOf(
            // Two terms back to back inside one word: one sticker, not two overlapping ones.
            Case("foobar baz", "foo bar", listOf(0 until 6)),
            // Repeats of one term abut each other.
            Case("abab", "ab", listOf(0 until 4)),
            // A space between hits keeps them apart.
            Case("foo bar", "foo bar", listOf(0 until 3, 4 until 7)),
        ).forEach { case ->
            assertEquals(case.expected, homeSearchHighlightRanges(case.text, case.query), "${case.text} / ${case.query}")
        }
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
