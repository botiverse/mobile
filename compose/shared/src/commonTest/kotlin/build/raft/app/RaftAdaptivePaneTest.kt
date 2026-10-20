package build.raft.app

import build.raft.app.compat.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftAdaptivePaneTest {
    @Test
    fun wideFlatWindowReducesToDualPane() {
        val state =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 1680,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat
                )
            )

        assertEquals(RaftAdaptivePaneLayout.DualPane, state.layout)
        assertEquals(RaftAdaptivePanePosture.Flat, state.posture)
    }

    @Test
    fun halfFoldedWindowTakesDualPaneWhenWideEnough() {
        // task #180 (artin 2026-10-17): a half-folded posture is no longer excluded from the second
        // pane. 2200px / 2f density = 1100dp, above the 840dp rule, so it is DualPane. This reverses
        // the previous "half-fold never opens a second pane" contract on purpose.
        val state =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.HalfFolded
                )
            )

        assertEquals(RaftAdaptivePaneLayout.DualPane, state.layout)
    }

    @Test
    fun halfFoldedWindowStaysCompactBelowTheWidthRule() {
        // Half-fold is now judged by width like every other posture -- it must not claim a second
        // pane just because it is folded.
        val state =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 1600,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.HalfFolded
                )
            )

        assertEquals(RaftAdaptivePaneLayout.Compact, state.layout)
    }

    @Test
    fun foldedAndFlatAgreeWithHalfFoldedAtTheSameWidth() {
        // Posture must no longer change the layout decision; only width (and Unknown) does.
        val width = 2200
        val density = 2f
        listOf(
            RaftAdaptivePanePosture.Flat,
            RaftAdaptivePanePosture.Folded,
            RaftAdaptivePanePosture.HalfFolded,
        ).forEach { posture ->
            val state =
                reduceRaftAdaptivePane(
                    RaftAdaptivePaneState(),
                    RaftAdaptivePaneEvent(width, 1200, density, posture)
                )
            assertEquals(
                RaftAdaptivePaneLayout.DualPane,
                state.layout,
                "posture $posture should follow the width rule"
            )
        }
    }

    @Test
    fun unknownPostureStaysCompactUntilHostFactsSettle() {
        val unknown =
            reduceRaftAdaptivePane(
                RaftAdaptivePaneState(),
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Unknown
                )
            )
        val settled =
            reduceRaftAdaptivePane(
                unknown,
                RaftAdaptivePaneEvent(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat
                )
            )

        assertEquals(RaftAdaptivePaneLayout.Compact, unknown.layout)
        assertEquals(RaftAdaptivePaneLayout.DualPane, settled.layout)
    }

    @Test
    fun invalidHostEventDoesNotEraseLastKnownWindowFact() {
        val previous =
            RaftAdaptivePaneState(
                widthPx = 1200,
                heightPx = 800,
                density = 2f,
                posture = RaftAdaptivePanePosture.Unknown,
                layout = RaftAdaptivePaneLayout.Compact
            )

        assertEquals(
            previous,
            reduceRaftAdaptivePane(
                previous,
                RaftAdaptivePaneEvent(0, 800, 2f, RaftAdaptivePanePosture.Flat)
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
                .toRaftAdaptivePaneEventOrNull()

        assertEquals(RaftAdaptivePanePosture.HalfFolded, event?.posture)
        assertNull(JSONObject().put("widthPx", 1800).toRaftAdaptivePaneEventOrNull())
    }
}
