package build.raft.app

import androidx.compose.ui.graphics.Color
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
    fun highlightRangesTakeThePrefixOfAHandleOrChannelHit() {
        data class Case(val text: String, val query: String, val expected: List<IntRange>)
        listOf(
            // The handle is one unit: "@Remy", not "@" + "Remy".
            Case("Agent · @Remy", "remy", listOf(8 until 13)),
            Case("Agent · @Remy", "REMY", listOf(8 until 13)),
            // A query that carries the "@" matches the same range.
            Case("Agent · @Remy", "@remy", listOf(8 until 13)),
            // The title has no "@": unchanged.
            Case("Remy", "remy", listOf(0 until 4)),
            // An "@" glued to a word (an email) is not a handle.
            Case("mail a@remy.dev", "remy", listOf(7 until 11)),
            // Several terms: each handle hit keeps its "@".
            Case("@Remy and @Lagoon", "remy lagoon", listOf(0 until 5, 10 until 17)),
            // A "#" channel takes its "#" too.
            Case("Channel · #remy-chat", "remy", listOf(10 until 15)),
            Case("see #remy-chat and #lagoon", "remy lagoon", listOf(4 until 9, 19 until 26)),
            Case("issue#remy", "remy", listOf(6 until 10)),
            // A query that starts with "@" / "#" matches the bare name too, and the prefixed one.
            Case("Remy", "@remy", listOf(0 until 4)),
            Case("Agent · @Remy", "#remy", listOf(8 until 13)),
            // Not a handle hit at all.
            Case("@Lagoon", "remy", emptyList()),
        ).forEach { case ->
            assertEquals(case.expected, homeSearchHighlightRanges(case.text, case.query), "${case.text} / ${case.query}")
        }
    }

    @Test
    fun highlightRangeLeavesTheFixedLabelUnmarked() {
        // "Agent · @Remy · Members": only the handle (8 until 13) is data.
        val text = "Agent · @Remy · Members"
        assertEquals(listOf(8 until 13), homeSearchHighlightRanges(text, "remy", within = 8 until 13))
        assertEquals(emptyList(), homeSearchHighlightRanges(text, "agent members", within = 8 until 13))
        assertEquals(listOf(0 until 5, 16 until 23), homeSearchHighlightRanges(text, "agent members"))
        // A fully fixed subtitle ("Channel") marks nothing.
        assertEquals(emptyList(), homeSearchHighlightRanges("Channel", "chan", within = IntRange.EMPTY))
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
        // raft-ui search hit (S1): sticker-ink type in the surrounding weight (not bold); the fill is drawn behind it.
        val hit = annotated.spanStyles.single().item
        assertEquals(RaftColor.StickerInk, hit.color)
        assertEquals(null, hit.fontWeight)
        assertEquals(null, hit.fontFamily)
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
