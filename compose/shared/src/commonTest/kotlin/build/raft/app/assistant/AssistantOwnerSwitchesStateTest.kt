package build.raft.app.assistant

import build.raft.app.data.AssistantOwnerSetting
import build.raft.app.data.MyAssistantSettingSaveOutcome
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The owner's switch rows (web AssistantOwnerSection `OwnerSwitchRow`): saving, then a failure until the next flip. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantOwnerSwitchesStateTest {
    private val failed = ScreenError("http_500", traceId = "4bf92f3577b34da6a3ce929d0e0e4736")

    @Test
    fun aSwitchIsDisabledWhileItSavesAndASecondFlipWaitsForIt() =
        runTest {
            val state = AssistantOwnerSwitchesState()
            val answer = CompletableDeferred<MyAssistantSettingSaveOutcome>()
            val calls = mutableListOf<Pair<AssistantOwnerSetting, Boolean>>()
            val save: suspend (AssistantOwnerSetting, Boolean) -> MyAssistantSettingSaveOutcome = { setting, value ->
                calls += setting to value
                answer.await()
            }
            launch { state.flip(AssistantOwnerSetting.ReadsDms, false, save) }
            runCurrent()
            assertTrue(state.isSaving(AssistantOwnerSetting.ReadsDms))
            assertEquals(setOf(AssistantOwnerSetting.ReadsDms), state.saving, "the other switch stays enabled")
            // A flip while it saves does nothing (web `disabled={saving}`).
            state.flip(AssistantOwnerSetting.ReadsDms, true, save)
            assertEquals(listOf(AssistantOwnerSetting.ReadsDms to false), calls)
            answer.complete(MyAssistantSettingSaveOutcome.Saved)
            runCurrent()
            assertEquals(emptySet<AssistantOwnerSetting>(), state.saving)
            assertEquals(emptyMap<AssistantOwnerSetting, ScreenError>(), state.failures)
        }

    @Test
    fun aFailureStaysUntilTheNextFlip() =
        runTest {
            val state = AssistantOwnerSwitchesState()
            state.flip(AssistantOwnerSetting.SendAsOwner, true) { _, _ -> MyAssistantSettingSaveOutcome.Failed(failed) }
            assertEquals(mapOf(AssistantOwnerSetting.SendAsOwner to failed), state.failures)
            assertEquals(emptySet<AssistantOwnerSetting>(), state.saving)
            // Another switch's save leaves it.
            state.flip(AssistantOwnerSetting.ReadsDms, false) { _, _ -> MyAssistantSettingSaveOutcome.Saved }
            assertEquals(mapOf(AssistantOwnerSetting.SendAsOwner to failed), state.failures)
            // Its own next flip clears it.
            state.flip(AssistantOwnerSetting.SendAsOwner, true) { _, _ -> MyAssistantSettingSaveOutcome.Saved }
            assertEquals(emptyMap<AssistantOwnerSetting, ScreenError>(), state.failures)
        }

    @Test
    fun aSaveThatThrowsFailsWithoutATraceId() =
        runTest {
            val state = AssistantOwnerSwitchesState()
            state.flip(AssistantOwnerSetting.ReadsDms, false) { _, _ -> error("boom") }
            assertEquals(null, state.failures.getValue(AssistantOwnerSetting.ReadsDms).traceId)
            assertEquals(emptySet<AssistantOwnerSetting>(), state.saving)
        }
}
