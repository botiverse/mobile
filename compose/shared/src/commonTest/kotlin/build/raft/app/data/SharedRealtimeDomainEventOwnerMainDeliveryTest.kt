package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ThreadId
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Realtime ingress runs on a background dispatcher. A guarded sink registered with `onMain = true`
 * (one that writes Compose state, such as the Computer sign-in dialog) is called on
 * `Dispatchers.Main`, in event order; every other sink stays on the ingress thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRealtimeDomainEventOwnerMainDeliveryTest {
    @Test
    fun aMainSinkGetsEveryEventOnMainInOrderAndAStoreSinkStaysOnTheIngressThread() =
        withTestMain {
            val ingress = UnconfinedTestDispatcher(testScheduler, name = "ingress")
            val owner = owner()
            val onMain = mutableListOf<Pair<String, CoroutineDispatcher?>>()
            val offMain = mutableListOf<Pair<String, CoroutineDispatcher?>>()
            owner.registerGuardedActiveThreadSink(
                RealtimeGuardedActiveThreadSink { _, event -> onMain += event.messageId() to currentDispatcher() },
                onMain = true,
            )
            owner.registerGuardedActiveThreadSink(
                RealtimeGuardedActiveThreadSink { _, event -> offMain += event.messageId() to currentDispatcher() },
            )

            val ingressJob = launch(ingress) { (1L..3L).forEach { seq -> owner.accept(Grant, messageNew(seq), source = "test") } }

            // Ingress waits for main, so it cannot run ahead of the dialog with the next event.
            assertEquals(emptyList(), onMain, "main has not run yet")
            assertTrue(ingressJob.isActive, "ingress waits for the main-thread sink")
            testScheduler.runCurrent()

            assertTrue(ingressJob.isCompleted)
            assertEquals(listOf("m1", "m2", "m3"), onMain.map { it.first })
            assertTrue(onMain.all { it.second === Dispatchers.Main.immediate }, "main sink ran on ${onMain.map { it.second }}")
            assertEquals(listOf("m1", "m2", "m3"), offMain.map { it.first })
            assertTrue(offMain.all { it.second === ingress }, "store sink ran on ${offMain.map { it.second }}")
        }

    @Test
    fun aMainSinkUnregisteredWhileTheEventWaitsForMainIsNotCalled() =
        withTestMain {
            val ingress = UnconfinedTestDispatcher(testScheduler, name = "ingress")
            val owner = owner()
            val delivered = mutableListOf<RealtimeEvent>()
            val sink = RealtimeGuardedActiveThreadSink { _, event -> delivered += event }
            owner.registerGuardedActiveThreadSink(sink, onMain = true)

            val ingressJob = launch(ingress) { owner.accept(Grant, messageNew(1L), source = "test") }
            // The dialog closes (onDispose, on main) before main picks the event up.
            owner.unregisterGuardedActiveThreadSink(sink)
            testScheduler.runCurrent()

            assertTrue(ingressJob.isCompleted)
            assertEquals(emptyList(), delivered, "a disposed UI sink must not get the event")
        }

    private fun withTestMain(body: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler, name = "main"))
            try {
                body()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun owner() = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })

    private suspend fun currentDispatcher(): CoroutineDispatcher? =
        currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher

    private fun RealtimeEvent.messageId(): String = (this as MessageNew).message.id.value

    private fun messageNew(seq: Long) =
        MessageNew(
            Message(
                id = MessageId("m$seq"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                seq = seq,
                author = MemberProfile(MemberId("u1"), "Artin", "artin", MemberKind.Human),
                timestampLabel = "now",
                body = "hello",
            )
        )

    private companion object {
        val Grant =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey("shell-1", "server-1"),
                principalId = "user-1",
                environmentId = "env-test",
                authGeneration = 1L,
                connectionFamilyId = "family-1",
                connectionEpochId = "epoch-1",
            )
    }
}
