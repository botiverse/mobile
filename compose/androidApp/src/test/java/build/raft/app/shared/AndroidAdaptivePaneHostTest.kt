package build.raft.app.shared

import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdaptivePaneHostTest {
    @Test
    fun resizeAndFoldChangesEachRepublishTheCompleteSnapshot() {
        val payloads = mutableListOf<Map<String, Any>>()
        val publisher = AndroidAdaptivePanePublisher { _, data -> payloads += data }

        publisher.publish(snapshot(widthPx = 1800, posture = AndroidAdaptivePanePosture.Flat))
        publisher.publish(snapshot(widthPx = 1200, posture = AndroidAdaptivePanePosture.Flat))
        publisher.publish(snapshot(widthPx = 1200, posture = AndroidAdaptivePanePosture.HalfFolded))

        assertEquals(listOf(1800, 1200, 1200), payloads.map { it["widthPx"] })
        assertEquals(listOf("flat", "flat", "half_folded"), payloads.map { it["posture"] })
        assertTrue(payloads.all { it.keys == setOf("widthPx", "heightPx", "density", "posture") })
    }

    @Test
    fun invalidFactsAreIgnoredWithoutReplacingTheLastValidSnapshot() {
        val payloads = mutableListOf<Map<String, Any>>()
        val publisher = AndroidAdaptivePanePublisher { _, data -> payloads += data }
        val valid = snapshot(widthPx = 1800, posture = AndroidAdaptivePanePosture.Unknown)

        assertTrue(publisher.publish(valid))
        assertFalse(publisher.publish(valid.copy(widthPx = 0)))
        assertFalse(publisher.publish(valid.copy(heightPx = 0)))
        assertFalse(publisher.publish(valid.copy(density = 0f)))
        assertFalse(publisher.publish(valid))

        assertEquals(1, payloads.size)
    }

    @Test
    fun halfOpenedWinsAcrossMultipleFoldingFeatures() {
        assertEquals(
            AndroidAdaptivePanePosture.Flat,
            androidAdaptivePanePosture(emptyList()),
        )
        assertEquals(
            AndroidAdaptivePanePosture.Flat,
            androidAdaptivePanePosture(listOf(FoldingFeature.State.FLAT)),
        )
        assertEquals(
            AndroidAdaptivePanePosture.HalfFolded,
            androidAdaptivePanePosture(
                listOf(FoldingFeature.State.FLAT, FoldingFeature.State.HALF_OPENED)
            ),
        )
    }

    private fun snapshot(
        widthPx: Int,
        posture: AndroidAdaptivePanePosture,
    ): AndroidAdaptivePaneSnapshot =
        AndroidAdaptivePaneSnapshot(
            widthPx = widthPx,
            heightPx = 1200,
            density = 2f,
            posture = posture,
        )
}
