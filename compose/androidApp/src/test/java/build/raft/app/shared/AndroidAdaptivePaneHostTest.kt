package build.raft.app.shared

import androidx.window.layout.FoldingFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdaptivePaneHostTest {
    @Test
    fun publisherEmitsTheSharedPagerContractAndSuppressesAnExactDuplicate() {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        val publisher = AndroidAdaptivePanePublisher { event, data -> events += event to data }
        val snapshot =
            AndroidAdaptivePaneSnapshot(
                widthPx = 1800,
                heightPx = 1200,
                density = 2f,
                posture = AndroidAdaptivePanePosture.Flat,
            )

        assertTrue(publisher.publish(snapshot))
        assertFalse(publisher.publish(snapshot))

        assertEquals(1, events.size)
        assertEquals(AndroidAdaptivePanePublisher.EventName, events.single().first)
        assertEquals(1800, events.single().second["widthPx"])
        assertEquals(1200, events.single().second["heightPx"])
        assertEquals(2f, events.single().second["density"])
        assertEquals("flat", events.single().second["posture"])
    }

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
