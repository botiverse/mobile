package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RaftAvatarStepTest {

    @Test
    fun nearestPicksTheClosestWebStepAndTiesGoToTheLargerStep() {
        // avatar dp -> step. Midpoints between edges (14/19/27/35/51/99): 16.5, 23, 31, 43, 75.
        val cases =
            listOf(
                0f to RaftAvatarStep.Xxxs,
                12f to RaftAvatarStep.Xxxs,
                14f to RaftAvatarStep.Xxxs,
                16f to RaftAvatarStep.Xxxs,
                16.5f to RaftAvatarStep.Xs,
                20f to RaftAvatarStep.Xs,
                22f to RaftAvatarStep.Xs,
                23f to RaftAvatarStep.Sm,
                28f to RaftAvatarStep.Sm,
                30f to RaftAvatarStep.Sm,
                31f to RaftAvatarStep.Md,
                36f to RaftAvatarStep.Md,
                42f to RaftAvatarStep.Md,
                43f to RaftAvatarStep.Lg,
                44f to RaftAvatarStep.Lg,
                56f to RaftAvatarStep.Lg,
                74f to RaftAvatarStep.Lg,
                75f to RaftAvatarStep.Xl,
                99f to RaftAvatarStep.Xl,
                200f to RaftAvatarStep.Xl,
            )
        cases.forEach { (size, expected) ->
            assertEquals(expected, RaftAvatarStep.nearest(size.dp), "nearest(${size}dp)")
        }
    }

    @Test
    fun initialsSkipWhatCannotStandAsAnInitial() {
        val grinning = "😀"
        // label, step, expected initials ("" = the placeholder icon).
        val cases =
            listOf(
                Triple("Ada Lovelace", RaftAvatarStep.Md, "AL"),
                Triple("Ada Lovelace", RaftAvatarStep.Sm, "A"),
                Triple("ada  lovelace  byron", RaftAvatarStep.Lg, "AL"),
                // One word gives one initial, also when it is short or already upper case.
                Triple("Ada", RaftAvatarStep.Md, "A"),
                Triple("AD", RaftAvatarStep.Md, "A"),
                Triple("  ", RaftAvatarStep.Md, ""),
                Triple("", RaftAvatarStep.Md, ""),
                Triple("@x", RaftAvatarStep.Md, "X"),
                Triple("?", RaftAvatarStep.Md, ""),
                Triple("#general", RaftAvatarStep.Sm, "G"),
                // Emoji: never half a surrogate pair; an emoji-only name shows the icon.
                Triple(grinning, RaftAvatarStep.Md, ""),
                Triple("$grinning Ada", RaftAvatarStep.Md, "A"),
                Triple("${grinning}Ada Lovelace", RaftAvatarStep.Md, "AL"),
                // Lone surrogates are dropped, not drawn as a broken glyph.
                Triple("\uD83D", RaftAvatarStep.Md, ""),
                Triple("\uDE00bob", RaftAvatarStep.Sm, "B"),
                // CJK: the first character is the initial.
                Triple("张三", RaftAvatarStep.Md, "张"),
                Triple("山田 太郎", RaftAvatarStep.Md, "山太"),
                // A combining accent stays on its letter.
                Triple("émile", RaftAvatarStep.Sm, "É"),
                Triple("007 agent", RaftAvatarStep.Md, "0A"),
            )
        cases.forEach { (label, step, expected) ->
            assertEquals(expected, raftAppAvatarInitials(label, step), "initials(\"$label\", $step)")
        }
    }

    @Test
    fun storedAvatarLabelIsOneWholeInitial() {
        val cases =
            listOf(
                "Ada Lovelace" to "A",
                "🚀 Rocket" to "R",
                "🚀" to "",
                "@bob" to "B",
                "\uD83D" to "",
                "张三" to "张",
            )
        cases.forEach { (name, expected) ->
            assertEquals(expected, name.toRaftAvatarLabel(), "toRaftAvatarLabel(\"$name\")")
        }
    }

    @Test
    fun pixelGridEdgesAreWholePixelsWithNoEmptyCellAtSmallSizes() {
        (10..16).forEach { extentPx ->
            val extent = extentPx.toFloat()
            val edges = (0..8).map { pixelAvatarGridEdge(it, extent) }
            assertEquals(0f, edges.first(), "first edge at ${extentPx}px")
            assertEquals(extent, edges.last(), "last edge at ${extentPx}px")
            edges.zipWithNext().forEachIndexed { cell, (start, end) ->
                assertEquals(start.toInt().toFloat(), start, "edge $cell at ${extentPx}px is a whole pixel")
                val width = end - start
                assertTrue(width >= 1f, "cell $cell at ${extentPx}px has width $width (edges $edges)")
                assertTrue(width <= 2f, "cell $cell at ${extentPx}px has width $width (edges $edges)")
            }
        }
    }
}
