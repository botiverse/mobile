package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class SlockFocusedInputBoundsResolverTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resolverUsesInnermostRegistryAndTracksItsReplacement() = runTest {
        val outerBounds = Rect(1f, 2f, 31f, 42f)
        val firstInnerBounds = Rect(101f, 102f, 131f, 142f)
        val replacementInnerBounds = Rect(201f, 202f, 231f, 242f)
        val outerRegistry = focusedRegistry(outerBounds)
        val innerRegistry = mutableStateOf(focusedRegistry(firstInnerBounds))
        var composedResolver: (() -> Rect?)? = null

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(FocusedBoundsUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalSlockInputFocusOwnerRegistry provides outerRegistry) {
                    CompositionLocalProvider(
                        LocalSlockInputFocusOwnerRegistry provides innerRegistry.value
                    ) {
                        val resolver = rememberSlockFocusedInputBoundsResolver()
                        SideEffect { composedResolver = resolver }
                    }
                }
            }
            advanceUntilIdle()

            val initialResolver = requireNotNull(composedResolver)
            assertEquals(outerBounds, outerRegistry.focusedBounds())
            assertEquals(firstInnerBounds, initialResolver())

            innerRegistry.value = focusedRegistry(replacementInnerBounds)
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(System.nanoTime())
            advanceUntilIdle()

            assertSame(initialResolver, composedResolver)
            assertEquals(replacementInnerBounds, initialResolver())
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }

    private fun focusedRegistry(bounds: Rect): SlockInputFocusOwnerRegistry =
        SlockInputFocusOwnerRegistry().also { registry ->
            registry.setFocused(owner = Any(), focused = true, bounds = { bounds })
        }
}

private class FocusedBoundsUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
