package build.raft.app.data

import build.raft.app.ChannelConversionErrorText
import build.raft.app.ChannelConversionStatusNotice
import build.raft.app.channelConversionStripModel
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversion strip's store (web `useChannelConversionController`, botiverse/slock 922c8de664
 * and its tests `channelConversionPolling.behavior.test.tsx`): the `channel:updated` rows move the
 * strip and a done job ends it; the job is read every [CHANNEL_CONVERSION_POLL_MILLIS] only while
 * it runs, a page shows it and the app is in front; Retry and Cancel answer into the slice.
 */
class SharedChannelConversionStoreTest {
    private val key = ChannelConversionKey("srv-1", "ch-ops")

    /** Each request waits for the answer the test gives it. */
    private class FakeApi : RaftChannelConversionApi {
        val calls = mutableListOf<String>()
        private val answers = ArrayDeque<CompletableDeferred<HomeNetworkResponse>>()

        override suspend fun readChannel(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority) = wait("channel:${channelId.value}:$priority")

        override suspend fun readJob(serverId: ServerId, jobId: String, priority: DataRequestPriority) = wait("job:$jobId:$priority")

        override suspend fun command(serverId: ServerId, jobId: String, action: ChannelConversionAction) = wait("${action.name.lowercase()}:$jobId")

        private suspend fun wait(call: String): HomeNetworkResponse {
            calls += call
            return CompletableDeferred<HomeNetworkResponse>().also { answers.addLast(it) }.await()
        }

        fun answer(response: HomeNetworkResponse) {
            answers.removeFirst().complete(response)
        }

        fun fail() {
            answers.removeFirst().completeExceptionally(IllegalStateException("offline"))
        }

        val waiting: Int get() = answers.size
    }

    private fun jobJson(status: String, phase: String, canCancel: Boolean = false, error: String? = null) =
        """{"id":"job-1","status":"$status","phase":"$phase","canCancel":$canCancel,"blocksSending":true,"progress":{},"error":${error?.let { "\"$it\"" } ?: "null"}}"""

    /** A channel row as `attachLatestChannelConversionJobs` writes it. */
    private fun row(status: String, phase: String, canCancel: Boolean = false, error: String? = null): String {
        val job = jobJson(status, phase, canCancel, error)
        val stateStatus = if (status == "pending") "running" else status
        return """{"id":"ch-ops","name":"ops","type":"channel","conversionJob":${if (status in setOf("done", "canceled")) "null" else job},"conversionCommand":null,"conversionState":{"status":"$stateStatus","command":null,"job":$job}}"""
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    private fun jobAnswer(status: String, phase: String, canCancel: Boolean = false, error: String? = null) =
        ok("""{"channel":${row(status, phase, canCancel, error)},"conversionJob":${jobJson(status, phase, canCancel, error)}}""")

    /** The `channel:updated` frame the worker sends after a phase, through the app's realtime mapper. */
    private fun frame(status: String, phase: String, channelId: String = "ch-ops"): RealtimeEvent =
        RealtimeEventMapper().map(RealtimeSocketEvents.ChannelUpdated, """{"channel":${row(status, phase).replace("\"ch-ops\"", "\"$channelId\"")}}""")!!

    private fun TestScope.store(api: FakeApi, foreground: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        SharedChannelConversionStore(ChannelConversionRepository(api), backgroundScope, foreground = foreground, nowMillis = { testScheduler.currentTime })

    private fun TestScope.opened(api: FakeApi, store: SharedChannelConversionStore, answer: HomeNetworkResponse) {
        store.dispatch(ChannelConversionIntent.Load(key, "open", DataRequestPriority.Visible))
        runCurrent()
        api.answer(answer)
        runCurrent()
    }

    private fun SharedChannelConversionStore.model() = channelConversionStripModel(current(key))

    @Test
    fun theOpenReadShowsTheRunningJobTheWorkersFramesMoveItAndDoneEndsIt() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            assertNull(store.model(), "nothing before the first answer")
            opened(api, store, ok(row("running", "move_parent_messages")))
            assertEquals(listOf("channel:ch-ops:Visible"), api.calls)
            assertEquals(45, store.model()?.percent)

            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", frame("running", "verify")))
            assertEquals(82, store.model()?.percent)
            assertEquals(2, store.model()?.stepIndex)

            // Another channel, another server, or a row without the conversion: nothing moves.
            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", frame("done", "done", channelId = "ch-other")))
            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-2", frame("done", "done")))
            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", RealtimeRefreshRequired(RealtimeRefreshScope.Home, RealtimeSocketEvents.ChannelUpdated, channelId = ChannelId("ch-ops"), channelArchived = true)))
            assertEquals(82, store.model()?.percent)

            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", frame("done", "done")))
            assertNull(store.model(), "done: it goes away by itself")
            assertEquals(1, api.calls.size, "the frames needed no read")
        }

    @Test
    fun aReadThatStartedBeforeAFrameDoesNotTakeItBack() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            store.dispatch(ChannelConversionIntent.Load(key, "open", DataRequestPriority.Visible))
            runCurrent()
            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", frame("running", "verify")))
            api.answer(ok(row("running", "move_parent_messages")))
            runCurrent()
            assertEquals("verify", store.current(key).conversion.job?.phase)
        }

    @Test
    fun aRunningJobIsReadEveryTenSecondsWhileShownAndNotWhileTheAppIsHidden() =
        runTest {
            val api = FakeApi()
            val foreground = MutableStateFlow(true)
            val store = store(api, foreground)
            opened(api, store, ok(row("running", "move_parent_messages")))
            store.dispatch(ChannelConversionIntent.Watch(key))
            advanceTimeBy(CHANNEL_CONVERSION_POLL_MILLIS - 100)
            assertEquals(1, api.calls.size, "no read before the fallback interval")
            advanceTimeBy(200)
            assertEquals("job:job-1:Background", api.calls.last())
            api.answer(jobAnswer("running", "prepare_threads"))
            runCurrent()
            assertEquals(60, store.model()?.percent)

            foreground.value = false
            advanceTimeBy(CHANNEL_CONVERSION_POLL_MILLIS * 6)
            assertEquals(2, api.calls.size, "paused while the app is in the background")
            foreground.value = true
            runCurrent()
            assertEquals(3, api.calls.size, "read at once when it is back in front")
            api.answer(jobAnswer("running", "prepare_threads"))
            runCurrent()

            store.dispatch(ChannelConversionIntent.Unwatch(key))
            advanceTimeBy(CHANNEL_CONVERSION_POLL_MILLIS * 3)
            assertEquals(3, api.calls.size, "no page shows it")
        }

    @Test
    fun retryFollowsTheRetriedJobAndARefusalStaysWithTheServersWords() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            opened(api, store, ok(row("failed", "verify", error = "Verification failed")))
            assertEquals(ChannelConversionErrorText.Server("Verification failed"), store.model()?.error)
            store.dispatch(ChannelConversionIntent.Retry(key))
            store.dispatch(ChannelConversionIntent.Retry(key))
            runCurrent()
            assertEquals(listOf("retry:job-1"), api.calls.drop(1), "one Retry at a time")
            api.answer(
                HomeNetworkResponse(
                    code = 409,
                    bodyText = null,
                    errorBodyText = """{"error":"Only a failed conversion can be retried","code":"channel_conversion_retry_not_allowed"}""",
                    responseHeaders = mapOf("x-trace-id" to listOf("0123456789abcdef0123456789abcdef")),
                ),
            )
            runCurrent()
            assertEquals(ChannelConversionErrorText.Server("Only a failed conversion can be retried"), store.model()?.error)
            assertEquals("0123456789abcdef0123456789abcdef", store.model()?.errorTraceId)
            assertEquals(true, store.model()?.offersRetry)

            store.dispatch(ChannelConversionIntent.Retry(key))
            runCurrent()
            api.answer(HomeNetworkResponse(code = 202, bodyText = """{"channel":${row("pending", "verify")},"conversionJob":${jobJson("pending", "verify")}}"""))
            runCurrent()
            val model = store.model()
            assertEquals(true, model?.showsStrip)
            assertNull(model?.error, "the retried job goes on: the old failure is gone")
            assertNull(store.current(key).busy)
        }

    @Test
    fun aRetryWithoutAnAnswerSaysTheStatusIsUnknownAndLooksAgain() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            opened(api, store, ok(row("failed", "verify", error = "Verification failed")))
            store.dispatch(ChannelConversionIntent.Retry(key))
            runCurrent()
            api.answer(HomeNetworkResponse(code = 503, bodyText = null))
            runCurrent()
            assertEquals(ChannelConversionStatusNotice.Unavailable, store.model()?.statusNotice)
            assertEquals("channel:ch-ops:Background", api.calls.last(), "the retry may have gone through: find the job again")
            api.answer(ok(row("running", "verify")))
            runCurrent()
            assertNull(store.model()?.statusNotice)
            assertEquals(true, store.model()?.showsStrip)
        }

    @Test
    fun aConfirmedCancelHidesTheStripAndSaysSoOnce() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            val effects = mutableListOf<ChannelConversionEffect>()
            backgroundScope.launch { store.effects.toList(effects) }
            opened(api, store, ok(row("running", "prepare", canCancel = true)))
            assertEquals(true, store.model()?.offersCancel)
            store.dispatch(ChannelConversionIntent.Cancel(key))
            runCurrent()
            assertEquals(ChannelConversionAction.Cancel, store.model()?.busy)
            api.answer(jobAnswer("canceled", "prepare"))
            runCurrent()
            assertNull(store.model())
            assertEquals(listOf<ChannelConversionEffect>(ChannelConversionEffect.Canceled(key)), effects)
            // An older frame of the same job does not bring it back.
            store.dispatch(ChannelConversionIntent.Realtime(key, "srv-1", frame("running", "prepare")))
            assertNull(store.model())
        }

    @Test
    fun aRefusedCancelReadsTheJobAgainAndSaysWhy() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            opened(api, store, ok(row("running", "prepare", canCancel = true)))
            store.dispatch(ChannelConversionIntent.Cancel(key))
            runCurrent()
            api.answer(HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"This conversion can't be canceled now","code":"channel_conversion_cancel_not_allowed"}"""))
            runCurrent()
            assertEquals("job:job-1:Visible", api.calls.last())
            api.answer(jobAnswer("running", "audience_cutover", canCancel = false))
            runCurrent()
            val model = store.model()
            assertEquals(ChannelConversionErrorText.Server("This conversion can't be canceled now"), model?.error)
            assertEquals(false, model?.failed)
            assertEquals(true, model?.showsStrip)
            assertEquals(false, model?.offersCancel)
        }

    @Test
    fun aCancelWithoutAnAnswerThatTheJobConfirmsIsDone() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            opened(api, store, ok(row("running", "prepare", canCancel = true)))
            store.dispatch(ChannelConversionIntent.Cancel(key))
            runCurrent()
            api.fail()
            runCurrent()
            api.answer(jobAnswer("canceled", "prepare"))
            runCurrent()
            assertNull(store.model())
            assertEquals(0, api.waiting)
        }

    @Test
    fun anUnreadableStatusSaysSoThenThatTheOutcomeIsUnknownUntilARead() =
        runTest {
            val api = FakeApi()
            val store = store(api)
            opened(api, store, ok(row("running", "move_parent_messages")))
            store.dispatch(ChannelConversionIntent.CheckStatus(key))
            runCurrent()
            assertEquals("job:job-1:Visible", api.calls.last(), "a job under way is read on its own route")
            api.answer(HomeNetworkResponse(code = 500, bodyText = null, responseHeaders = mapOf("x-trace-id" to listOf("fedcba9876543210fedcba9876543210"))))
            runCurrent()
            val unavailable = store.model()
            assertEquals(ChannelConversionStatusNotice.Unavailable, unavailable?.statusNotice)
            assertEquals("fedcba9876543210fedcba9876543210", unavailable?.statusTraceId, "the failed read's trace id")
            assertTrue(unavailable?.paused == true, "no step reads as under way")
            assertEquals(45, unavailable?.percent, "the last progress stays")
            advanceTimeBy(CHANNEL_CONVERSION_CONFIRM_MILLIS + 1)
            assertEquals(ChannelConversionStatusNotice.OutcomeUnknown, store.model()?.statusNotice)
            store.dispatch(ChannelConversionIntent.CheckStatus(key))
            runCurrent()
            api.answer(jobAnswer("running", "verify"))
            runCurrent()
            assertNull(store.model()?.statusNotice)
            assertNull(store.model()?.statusTraceId)
            assertEquals(82, store.model()?.percent)
        }
}
