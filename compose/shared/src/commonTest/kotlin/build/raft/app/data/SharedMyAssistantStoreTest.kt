package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** "My assistant" reads (web assistantClient.ts `useMyAssistant` / `refetchMyAssistant`), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedMyAssistantStoreTest {
    private val key = MyAssistantKey(serverId = "s1", userId = "u1")
    private val ready = MyAssistantAnswer(true, MyAssistantRecord("a1", "Ada", "a1", "dm1"))
    private val settingUp = MyAssistantAnswer(true, MyAssistantRecord("a1", "Ada", "a1", null))

    /** Answers in order; the last one repeats. */
    private class ScriptedApi(vararg results: MyAssistantFetchResult) : RaftMyAssistantApi {
        private val script = results.toList()
        val asked = mutableListOf<String>()

        override suspend fun fetch(serverId: ServerId): MyAssistantFetchResult {
            asked += serverId.value
            return script[minOf(asked.size - 1, script.lastIndex)]
        }
    }

    @Test
    fun readsOncePerPersonAndServer() =
        runTest {
            val api = ScriptedApi(MyAssistantFetchResult.Answer(ready))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            store.ensureLoaded(key)
            advanceUntilIdle()
            store.ensureLoaded(key)
            advanceUntilIdle()
            assertEquals(listOf("s1"), api.asked)
            assertEquals(ready, store.answerFor(key))
        }

    @Test
    fun notAvailableAndAFailedFirstReadFailClosed() =
        runTest {
            val notAvailable = SharedMyAssistantStore(ScriptedApi(MyAssistantFetchResult.NotAvailable), scope = backgroundScope)
            notAvailable.ensureLoaded(key)
            val failed = SharedMyAssistantStore(ScriptedApi(MyAssistantFetchResult.Failed(503)), scope = backgroundScope)
            failed.ensureLoaded(key)
            advanceUntilIdle()
            assertEquals(MyAssistantAnswer.NotEnabled, notAvailable.answerFor(key))
            assertEquals(MyAssistantAnswer.NotEnabled, failed.answerFor(key))
        }

    @Test
    fun asksAgainWithBackoffWhileTheDmIsSetUp() =
        runTest {
            val api =
                ScriptedApi(
                    MyAssistantFetchResult.Answer(settingUp),
                    // A transient failure on a refetch keeps the cached answer.
                    MyAssistantFetchResult.Failed(502),
                    MyAssistantFetchResult.Answer(ready),
                )
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            runCurrent()
            assertEquals(settingUp, store.answerFor(key))
            advanceTimeBy(2_001)
            assertEquals(2, api.asked.size, "first refetch after 2s")
            assertEquals(settingUp, store.answerFor(key), "a failed refetch keeps the answer")
            advanceTimeBy(5_001)
            assertEquals(3, api.asked.size, "second refetch 5s later")
            assertEquals(ready, store.answerFor(key))
            advanceUntilIdle()
            assertEquals(3, api.asked.size, "no more once the DM exists")
        }

    @Test
    fun stopsAfterTheLastDelay() =
        runTest {
            val api = ScriptedApi(MyAssistantFetchResult.Answer(settingUp))
            val store = SharedMyAssistantStore(api, scope = backgroundScope)
            store.ensureLoaded(key)
            advanceUntilIdle()
            assertEquals(1 + MyAssistantDmSetupRetryDelaysMillis.size, api.asked.size)
            assertEquals(settingUp, store.answerFor(key))
        }

    @Test
    fun stateFollowsTheWebGate() {
        // Guests never, whatever the flag and the answer say.
        assertEquals(MyAssistantState.Off, myAssistantState(viewerIsGuest = true, flagDecisionPending = false, flagEnabled = true, answer = ready))
        // The flag is a pre-check: off is off, pending is loading.
        assertEquals(MyAssistantState.Off, myAssistantState(false, flagDecisionPending = false, flagEnabled = false, answer = ready))
        assertEquals(MyAssistantState.Loading, myAssistantState(false, flagDecisionPending = true, flagEnabled = false, answer = null))
        // Then the API's `enabled` decides.
        assertEquals(MyAssistantState.Loading, myAssistantState(false, false, flagEnabled = true, answer = null))
        assertEquals(MyAssistantState.Off, myAssistantState(false, false, flagEnabled = true, answer = MyAssistantAnswer.NotEnabled))
        assertEquals(MyAssistantState.Ready(ready.assistant), myAssistantState(false, false, flagEnabled = true, answer = ready))
        assertEquals(MyAssistantState.Ready(null), myAssistantState(false, false, true, MyAssistantAnswer(true, null)))
    }

    @Test
    fun theOtterLiesOnTheComposerUsedLast() {
        val hosts = RaftAssistantOtterHosts()
        hosts.register("channel")
        assertEquals("channel", hosts.order.value.activeAssistantOtterHost())
        // A thread opened over the channel: the newest composer takes it.
        hosts.register("thread")
        assertEquals("thread", hosts.order.value.activeAssistantOtterHost())
        // Focus in the channel's composer moves it back.
        hosts.activate("channel")
        assertEquals("channel", hosts.order.value.activeAssistantOtterHost())
        // Activating a composer that is not registered does nothing.
        hosts.activate("gone")
        assertEquals(listOf("thread", "channel"), hosts.order.value)
        // The one it lay on leaves: the one before it takes it.
        hosts.unregister("channel")
        assertEquals("thread", hosts.order.value.activeAssistantOtterHost())
        hosts.unregister("thread")
        assertEquals(null, hosts.order.value.activeAssistantOtterHost())
    }
}
