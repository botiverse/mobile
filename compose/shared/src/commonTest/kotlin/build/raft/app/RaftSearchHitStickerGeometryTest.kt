package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftSearchHitStickerGeometryTest {
    @Test
    fun lineSegmentKeepsOnlyTheVisiblePartOfAHit() {
        data class Case(val name: String, val range: Pair<Int, Int>, val line: Pair<Int, Int>, val expected: IntRange?)
        listOf(
            Case("hit inside the line", 4 to 9, 0 to 20, 4 until 9),
            Case("hit wrapping onto the next line", 4 to 30, 20 to 40, 20 until 30),
            // An ellipsized line's visible end is where the ellipsis starts.
            Case("hit cut by the ellipsis", 10 to 30, 0 to 18, 10 until 18),
            Case("hit wholly behind the ellipsis", 18 to 25, 0 to 18, null),
            Case("hit on an earlier line", 0 to 5, 20 to 40, null),
        ).forEach { case ->
            assertEquals(
                case.expected,
                raftSearchHitLineSegment(case.range.first, case.range.second, case.line.first, case.line.second),
                case.name
            )
        }
    }

    @Test
    fun stickerExtentPadsOnlyIntoFreeRoomAndStaysInsideTheText() {
        data class Case(
            val name: String,
            val glyphs: Pair<Float, Float>,
            val room: Pair<Float, Float>,
            val expected: ClosedFloatingPointRange<Float>?,
        )
        val pad = 3f
        val width = 100f
        listOf(
            Case("spaces either side wide enough", 20f to 40f, 5f to 5f, 17f..43f),
            Case("half-space room caps the pad", 20f to 40f, 2f to 1.5f, 18f..41.5f),
            Case("glyph neighbours get no pad", 20f to 40f, 0f to 0f, 20f..40f),
            Case("line start clamps to 0", 1f to 10f, pad to pad, 0f..13f),
            Case("line end clamps to the width", 90f to 99f, pad to pad, 87f..100f),
            Case("glyphs past the visible edge draw nothing", 104f to 120f, pad to pad, null),
            Case("negative room is no room", 20f to 40f, -4f to -4f, 20f..40f),
        ).forEach { case ->
            val extent =
                raftSearchHitStickerExtent(
                    glyphLeft = case.glyphs.first,
                    glyphRight = case.glyphs.second,
                    roomLeft = case.room.first,
                    roomRight = case.room.second,
                    padX = pad,
                    layoutWidth = width,
                )
            if (case.expected == null) {
                assertNull(extent, case.name)
            } else {
                assertEquals(case.expected, extent, case.name)
            }
        }
    }
}
