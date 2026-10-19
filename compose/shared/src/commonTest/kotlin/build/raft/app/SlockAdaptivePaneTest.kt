package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockAdaptivePaneTest {
    @Test
    fun wideFlatWindowReducesToDualPane() {
        val state =
            reduceSlockAdaptivePane(
                SlockAdaptivePaneState(),
                SlockAdaptivePaneEvent(
                    widthPx = 1680,
                    heightPx = 1200,
                    density = 2f,
                    posture = SlockAdaptivePanePosture.Flat
                )
            )

        assertEquals(SlockAdaptivePaneLayout.DualPane, state.layout)
        assertEquals(SlockAdaptivePanePosture.Flat, state.posture)
    }

    @Test
    fun halfFoldedWindowTakesDualPaneWhenWideEnough() {
        // task #180 (artin 2026-10-17): a half-folded posture is no longer excluded from the second
        // pane. 2200px / 2f density = 1100dp, above the 840dp rule, so it is DualPane. This reverses
        // the previous "half-fold never opens a second pane" contract on purpose.
        val state =
            reduceSlockAdaptivePane(
                SlockAdaptivePaneState(),
                SlockAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = SlockAdaptivePanePosture.HalfFolded
                )
            )

        assertEquals(SlockAdaptivePaneLayout.DualPane, state.layout)
    }

    @Test
    fun halfFoldedWindowStaysCompactBelowTheWidthRule() {
        // Half-fold is now judged by width like every other posture -- it must not claim a second
        // pane just because it is folded.
        val state =
            reduceSlockAdaptivePane(
                SlockAdaptivePaneState(),
                SlockAdaptivePaneEvent(
                    widthPx = 1600,
                    heightPx = 1200,
                    density = 2f,
                    posture = SlockAdaptivePanePosture.HalfFolded
                )
            )

        assertEquals(SlockAdaptivePaneLayout.Compact, state.layout)
    }

    @Test
    fun foldedAndFlatAgreeWithHalfFoldedAtTheSameWidth() {
        // Posture must no longer change the layout decision; only width (and Unknown) does.
        val width = 2200
        val density = 2f
        listOf(
            SlockAdaptivePanePosture.Flat,
            SlockAdaptivePanePosture.Folded,
            SlockAdaptivePanePosture.HalfFolded,
        ).forEach { posture ->
            val state =
                reduceSlockAdaptivePane(
                    SlockAdaptivePaneState(),
                    SlockAdaptivePaneEvent(width, 1200, density, posture)
                )
            assertEquals(
                SlockAdaptivePaneLayout.DualPane,
                state.layout,
                "posture $posture should follow the width rule"
            )
        }
    }

    @Test
    fun unknownPostureStaysCompactUntilHostFactsSettle() {
        val unknown =
            reduceSlockAdaptivePane(
                SlockAdaptivePaneState(),
                SlockAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = SlockAdaptivePanePosture.Unknown
                )
            )
        val settled =
            reduceSlockAdaptivePane(
                unknown,
                SlockAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = SlockAdaptivePanePosture.Flat
                )
            )

        assertEquals(SlockAdaptivePaneLayout.Compact, unknown.layout)
        assertEquals(SlockAdaptivePaneLayout.DualPane, settled.layout)
    }

    @Test
    fun invalidHostEventDoesNotEraseLastKnownWindowFact() {
        val previous =
            SlockAdaptivePaneState(
                widthPx = 1200,
                heightPx = 800,
                density = 2f,
                posture = SlockAdaptivePanePosture.Unknown,
                layout = SlockAdaptivePaneLayout.Compact
            )

        assertEquals(
            previous,
            reduceSlockAdaptivePane(
                previous,
                SlockAdaptivePaneEvent(0, 800, 2f, SlockAdaptivePanePosture.Flat)
            )
        )
    }

    @Test
    fun nativeEnvelopeParsesOnlyCompleteWindowFacts() {
        val event =
            JSONObject()
                .put("widthPx", 1800)
                .put("heightPx", 1200)
                .put("density", 2)
                .put("posture", "half_folded")
                .toSlockAdaptivePaneEventOrNull()

        assertEquals(SlockAdaptivePanePosture.HalfFolded, event?.posture)
        assertNull(JSONObject().put("widthPx", 1800).toSlockAdaptivePaneEventOrNull())
    }
}
