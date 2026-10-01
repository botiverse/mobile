package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SlockImagePreviewRenderSlotTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coldDecodeCompletionRemountsCurrentNativeImageExactlyOnce() = runTest {
        var identity by
            mutableStateOf(
                slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 0, 0)
            )
        val mounted = mutableListOf<SlockImagePreviewRenderIdentity>()

        runRenderSlotComposition(
            content = {
                val currentIdentity = identity
                SlockImagePreviewRenderSlot(currentIdentity) {
                    DisposableEffect(Unit) {
                        mounted += currentIdentity
                        onDispose { }
                    }
                }
            },
            mutate = {
                identity = slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 1280, 720)
                Snapshot.sendApplyNotifications()
                sendFrame()
                advanceUntilIdle()
                identity = slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 1280, 720)
                Snapshot.sendApplyNotifications()
                sendFrame()
                advanceUntilIdle()
            }
        )

        assertEquals(
            listOf(
                slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 0, 0),
                slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 1280, 720)
            ),
            mounted
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lateOldSourceDecodeCannotRemountCurrentSource() = runTest {
        var activeSource by mutableStateOf("https://cdn.example/a.jpg")
        var decodedSizes by mutableStateOf(mapOf<String, Pair<Int, Int>>())
        val mounted = mutableListOf<SlockImagePreviewRenderIdentity>()

        runRenderSlotComposition(
            content = {
                val currentSource = activeSource
                val currentSize = decodedSizes[currentSource] ?: (0 to 0)
                val currentIdentity =
                    slockImagePreviewRenderIdentity(
                        currentSource,
                        currentSize.first,
                        currentSize.second
                    )
                SlockImagePreviewRenderSlot(currentIdentity) {
                    DisposableEffect(Unit) {
                        mounted += currentIdentity
                        onDispose { }
                    }
                }
            },
            mutate = {
                activeSource = "https://cdn.example/b.jpg"
                Snapshot.sendApplyNotifications()
                sendFrame()
                advanceUntilIdle()
                decodedSizes = decodedSizes + ("https://cdn.example/a.jpg" to (1280 to 720))
                Snapshot.sendApplyNotifications()
                sendFrame()
                advanceUntilIdle()
            }
        )

        assertEquals(
            listOf(
                slockImagePreviewRenderIdentity("https://cdn.example/a.jpg", 0, 0),
                slockImagePreviewRenderIdentity("https://cdn.example/b.jpg", 0, 0)
            ),
            mounted
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun resolvedIdentityPreservationPreventsRemountOnSubsequentDecode() = runTest {
        var identity by
            mutableStateOf(
                slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 0, 0)
            )
        val mounted = mutableListOf<SlockImagePreviewRenderIdentity>()

        runRenderSlotComposition(
            content = {
                val currentIdentity = identity
                SlockImagePreviewRenderSlot(currentIdentity) {
                    DisposableEffect(Unit) {
                        mounted += currentIdentity
                        onDispose { }
                    }
                }
            },
            mutate = {
                val preservedIdentity =
                    resolveSlockImagePreviewRenderIdentity(
                        currentIdentity = identity,
                        source = "https://cdn.example/image.jpg",
                        decodedWidth = 606,
                        decodedHeight = 508,
                        activeSucceeded = true
                    )
                identity = preservedIdentity
                Snapshot.sendApplyNotifications()
                sendFrame()
                advanceUntilIdle()
            }
        )

        assertEquals(
            listOf(
                slockImagePreviewRenderIdentity("https://cdn.example/image.jpg", 0, 0)
            ),
            mounted
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.runRenderSlotComposition(
        content: @androidx.compose.runtime.Composable () -> Unit,
        mutate: suspend RenderSlotTestScope.() -> Unit
    ) {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(UnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent(content)
            advanceUntilIdle()

            RenderSlotTestScope(this, frameClock).mutate()
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }
}

private class RenderSlotTestScope(
    private val testScope: kotlinx.coroutines.test.TestScope,
    private val frameClock: BroadcastFrameClock
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun advanceUntilIdle() = testScope.advanceUntilIdle()

    fun sendFrame() {
        frameClock.sendFrame(System.nanoTime())
    }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
