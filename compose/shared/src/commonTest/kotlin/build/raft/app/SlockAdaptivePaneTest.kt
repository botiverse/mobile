package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
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
    fun halfFoldedWindowStaysCompactEvenWhenWide() {
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

        assertEquals(SlockAdaptivePaneLayout.Compact, state.layout)
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
