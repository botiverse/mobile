package build.raft.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
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
class ThreadFocusedOriginCorrectionReadinessTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadedOriginWaitsForLaterFocusedAnchorReadiness() = runTest {
        val targetMessageId = "focused-message"
        val targetIdentity = focusedMessageAnchorIdentity(targetMessageId, requestNonce = 7)
        val anchoredIdentity = mutableStateOf<String?>(null)
        val startUserScrollGeneration = mutableStateOf<Int?>(null)
        val correctedIdentity = mutableStateOf<String?>(null)
        val lazyItemCount = mutableStateOf(19)
        val triggers = mutableListOf<ThreadFocusedOriginCorrectionTrigger>()

        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(ThreadFocusedOriginUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                ThreadFocusedOriginCorrectionEffect(
                    threadInstanceKey = "thread-instance",
                    targetMessageId = targetMessageId,
                    requestNonce = 7,
                    originStatus = "loaded",
                    originMessageId = "origin-message",
                    lazyItemCount = lazyItemCount.value,
                    anchoredIdentity = anchoredIdentity.value,
                    correctedIdentity = correctedIdentity.value,
                    scrollInterruptedIdentity = null,
                    startUserScrollGeneration = startUserScrollGeneration.value,
                    currentUserScrollGeneration = 3
                ) { trigger ->
                    triggers += trigger
                    correctedIdentity.value = trigger.targetIdentity
                }
            }
            advanceUntilIdle()
            assertEquals(emptyList(), triggers)

            anchoredIdentity.value = targetIdentity
            startUserScrollGeneration.value = 3
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(System.nanoTime())
            advanceUntilIdle()

            assertEquals(
                listOf(
                    ThreadFocusedOriginCorrectionTrigger(
                        targetMessageId = targetMessageId,
                        targetIdentity = targetIdentity,
                        startUserScrollGeneration = 3
                    )
                ),
                triggers
            )

            lazyItemCount.value = 20
            settleComposition(frameClock)
            assertEquals(1, triggers.size, "a completed identity must remain one-shot")
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun originTransitionStillTriggersWhenAnchorIsAlreadyReady() = runTest {
        val targetMessageId = "focused-message"
        val targetIdentity = focusedMessageAnchorIdentity(targetMessageId, requestNonce = 8)
        val originStatus = mutableStateOf("loading")
        val originMessageId = mutableStateOf<String?>(null)
        val correctedIdentity = mutableStateOf<String?>(null)
        val triggers = mutableListOf<ThreadFocusedOriginCorrectionTrigger>()

        runCorrectionComposition(
            targetMessageId = targetMessageId,
            requestNonce = 8,
            originStatus = originStatus,
            originMessageId = originMessageId,
            anchoredIdentity = mutableStateOf(targetIdentity),
            startUserScrollGeneration = mutableStateOf(3),
            correctedIdentity = correctedIdentity,
            currentUserScrollGeneration = 3,
            triggers = triggers
        ) { frameClock ->
            assertEquals(emptyList(), triggers)

            originStatus.value = "loaded"
            originMessageId.value = "origin-message"
            settleComposition(frameClock)

            assertEquals(1, triggers.size)
            assertEquals(targetIdentity, triggers.single().targetIdentity)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun laterReadinessNeverOverridesAUserScroll() = runTest {
        val targetMessageId = "focused-message"
        val targetIdentity = focusedMessageAnchorIdentity(targetMessageId, requestNonce = 9)
        val anchoredIdentity = mutableStateOf<String?>(null)
        val startUserScrollGeneration = mutableStateOf<Int?>(null)
        val triggers = mutableListOf<ThreadFocusedOriginCorrectionTrigger>()

        runCorrectionComposition(
            targetMessageId = targetMessageId,
            requestNonce = 9,
            originStatus = mutableStateOf("loaded"),
            originMessageId = mutableStateOf<String?>("origin-message"),
            anchoredIdentity = anchoredIdentity,
            startUserScrollGeneration = startUserScrollGeneration,
            correctedIdentity = mutableStateOf<String?>(null),
            currentUserScrollGeneration = 4,
            triggers = triggers
        ) { frameClock ->
            anchoredIdentity.value = targetIdentity
            startUserScrollGeneration.value = 3
            settleComposition(frameClock)

            assertEquals(emptyList(), triggers)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.runCorrectionComposition(
        targetMessageId: String,
        requestNonce: Int,
        originStatus: androidx.compose.runtime.MutableState<String>,
        originMessageId: androidx.compose.runtime.MutableState<String?>,
        anchoredIdentity: androidx.compose.runtime.MutableState<String?>,
        startUserScrollGeneration: androidx.compose.runtime.MutableState<Int?>,
        correctedIdentity: androidx.compose.runtime.MutableState<String?>,
        currentUserScrollGeneration: Int,
        triggers: MutableList<ThreadFocusedOriginCorrectionTrigger>,
        assertions: suspend (BroadcastFrameClock) -> Unit
    ) {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val composition = Composition(ThreadFocusedOriginUnitApplier(), recomposer)
        val recomposerJob = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent {
                ThreadFocusedOriginCorrectionEffect(
                    threadInstanceKey = "thread-instance",
                    targetMessageId = targetMessageId,
                    requestNonce = requestNonce,
                    originStatus = originStatus.value,
                    originMessageId = originMessageId.value,
                    lazyItemCount = 19,
                    anchoredIdentity = anchoredIdentity.value,
                    correctedIdentity = correctedIdentity.value,
                    scrollInterruptedIdentity = null,
                    startUserScrollGeneration = startUserScrollGeneration.value,
                    currentUserScrollGeneration = currentUserScrollGeneration
                ) { trigger ->
                    triggers += trigger
                    correctedIdentity.value = trigger.targetIdentity
                }
            }
            advanceUntilIdle()
            assertions(frameClock)
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.settleComposition(
        frameClock: BroadcastFrameClock
    ) {
        Snapshot.sendApplyNotifications()
        frameClock.sendFrame(System.nanoTime())
        advanceUntilIdle()
    }
}

private class ThreadFocusedOriginUnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit

    override fun remove(index: Int, count: Int) = Unit
}
