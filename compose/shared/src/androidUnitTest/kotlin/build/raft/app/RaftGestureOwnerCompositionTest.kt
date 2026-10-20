package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class RaftGestureOwnerCompositionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recompositionUpdatesSlotWhileSameSemanticRemountDisposesOldOwner() = runTest {
        val callback = mutableStateOf("old")
        var renderedOwner: RaftGestureOwner<GestureOwnerTestCallbacks>? = null
        lateinit var originalOwner: RaftGestureOwner<GestureOwnerTestCallbacks>
        lateinit var originalToken: Any
        lateinit var replacementBoundaryGesture: RaftGestureSession<GestureOwnerTestCallbacks>

        runGestureOwnerComposition(
            content = {
                renderedOwner = rememberOriginalTestGestureOwner(GestureOwnerTestCallbacks(callback.value))
            },
            mutate = {
                originalOwner = checkNotNull(renderedOwner)
                originalToken = originalOwner.pointerInputOwnerToken
                val originalGesture = originalOwner.beginGesture()

                callback.value = "updated"
                applyAndDrain()

                assertSame(originalOwner, renderedOwner)
                assertSame(originalToken, renderedOwner?.pointerInputOwnerToken)
                assertEquals("old", originalGesture.currentCallbacksOrNull()?.callback)
                assertEquals("updated", originalOwner.beginGesture().currentCallbacksOrNull()?.callback)

                replacementBoundaryGesture = originalOwner.beginGesture()
            }
        )

        assertEquals(null, replacementBoundaryGesture.currentCallbacksOrNull())
        assertFalse(originalOwner.beginGesture().isCurrent())

        var replacementOwner: RaftGestureOwner<GestureOwnerTestCallbacks>? = null
        runGestureOwnerComposition(
            content = {
                replacementOwner =
                    rememberReplacementTestGestureOwner(GestureOwnerTestCallbacks(callback.value))
            },
            mutate = {}
        )

        val mountedReplacement = checkNotNull(replacementOwner)
        assertNotSame(originalOwner, mountedReplacement)
        assertEquals(originalOwner.semanticIdentity, mountedReplacement.semanticIdentity)
        assertNotSame(originalToken, mountedReplacement.pointerInputOwnerToken)
        assertFalse(
            originalOwner.pointerInputKeys("tap").contentEquals(mountedReplacement.pointerInputKeys("tap")),
            "same-semantic remounts must not produce equal Kuikly pointer-input elements"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.runGestureOwnerComposition(
        content: @androidx.compose.runtime.Composable () -> Unit,
        mutate: suspend GestureOwnerCompositionScope.() -> Unit
    ) {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(GestureOwnerUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent(content)
            advanceUntilIdle()
            GestureOwnerCompositionScope(this, frameClock).mutate()
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberOriginalTestGestureOwner(
    callbacks: GestureOwnerTestCallbacks
): RaftGestureOwner<GestureOwnerTestCallbacks> =
    rememberRaftGestureOwner(
        surface = "composition-test",
        semanticIdentity = "same-id",
        callbacks = callbacks
    )

@androidx.compose.runtime.Composable
private fun rememberReplacementTestGestureOwner(
    callbacks: GestureOwnerTestCallbacks
): RaftGestureOwner<GestureOwnerTestCallbacks> =
    rememberRaftGestureOwner(
        surface = "composition-test",
        semanticIdentity = "same-id",
        callbacks = callbacks
    )

private data class GestureOwnerTestCallbacks(val callback: String)

private class GestureOwnerCompositionScope(
    private val testScope: TestScope,
    private val frameClock: BroadcastFrameClock
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun applyAndDrain() {
        Snapshot.sendApplyNotifications()
        frameClock.sendFrame(System.nanoTime())
        testScope.advanceUntilIdle()
    }
}

private class GestureOwnerUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
