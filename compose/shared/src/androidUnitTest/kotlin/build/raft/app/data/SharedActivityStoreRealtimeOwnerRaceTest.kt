package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Activity realtime-owner mutual exclusion against a second real thread.
 *
 * The owner is a monitor (`realtimeOwnerLock`) and the property is that a second thread cannot
 * enter it while the first holds it. Virtual time cannot express that: on one test thread the
 * held section never yields, and the monitor is reentrant. So the store runs on `runTest`'s
 * scheduler (every load and await is deterministic, no wall clock), and only the contender runs
 * on a real thread. The holder's injection hook waits until the JVM reports that contender
 * BLOCKED on a monitor owned by the holder's thread: a deterministic proof that the contender
 * reached the owner and was excluded, replacing the old 200 ms negative window and the
 * Dispatchers.Default timeouts (flaky under CI thread starvation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreRealtimeOwnerRaceTest {
    @Test
    fun processReadPredictionSerializesWithRealtimePatchWithoutLosingEitherTransition() = runTest {
        val inbox =
            FakeInboxRepository(
                initialSnapshots =
                    listOf(
                        snapshot(
                            listOf(threadItem("thread-channel-1", unreadCount = 2)),
                            totalUnreadCount = 2
                        )
                    )
            )
        val owner = ReadReceiptOwner("production", "user-1", "server-1")
        val prediction =
            SharedActivityIntent.ApplyReadReceiptPrediction(
                ReadReceiptPrediction(
                    owner = owner,
                    channelId = ChannelId("thread-channel-1"),
                    revision = 1L,
                    target = ReadReceiptTarget.All
                )
            )
        val predictionEntries = AtomicInteger(0)
        var predictionThread: Thread? = null
        var whileRealtimeHeld: OwnerContention? = null
        lateinit var store: SharedActivityStore
        store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                readReceiptOwnerFactory = { serverId, accountId ->
                    ReadReceiptOwner("production", accountId, serverId)
                },
                onRealtimePatchOwnerEntered = {
                    // Holding the owner on the test thread: race the prediction in from a real thread.
                    val contender = thread(name = "activity-prediction-contender") { store.dispatch(prediction) }
                    predictionThread = contender
                    whileRealtimeHeld = awaitBlockedOnOwnerHeldByCurrentThread(contender) { predictionEntries.get() }
                },
                onReadReceiptPredictionOwnerEntered = { predictionEntries.incrementAndGet() }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        // A realtime event that arrives before the row exists has nothing to patch.
        assertEquals(1, store.state.value.items.size, "initial load settled, state=${store.state.value}")

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-concurrent",
                        channelId = "thread-channel-1",
                        body = "Concurrent realtime preview"
                    )
                )
            )
        )
        val contender = joinContender(predictionThread, "prediction")
        runCurrent()

        val held = whileRealtimeHeld ?: error("realtime patch never entered the owner")
        assertTrue(held.blocked, "prediction never contended for the owner held by the realtime patch: $held")
        assertEquals(0, held.entriesWhileHeld, "prediction entered the owner while the realtime patch held it")
        assertFalse(contender.isAlive)
        assertEquals(1, predictionEntries.get(), "prediction entered the owner after the realtime patch released it")
        val item = store.state.value.items.single()
        assertEquals(MessageId("message-concurrent"), item.latestActivityMessageId)
        assertEquals("Concurrent realtime preview", item.latestActivityPreview)
        assertEquals(0, item.unreadCount)
        assertEquals(0, store.state.value.totalUnreadCount)
    }

    @Test
    fun realtimePatchCannotEnterOwnerDuringStaleLoadFoldAndKeepsHighWater() = runTest {
        val stale = threadItem("thread-channel-1", unreadCount = 1).copy(replyCount = 2)
        // Every load (initial, both refreshes) answers the same stale canonical row.
        val inbox = FakeInboxRepository(initialSnapshots = listOf(snapshot(listOf(stale), totalUnreadCount = 1)))
        val secondPatch =
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-new",
                        channelId = "thread-channel-1",
                        body = "Realtime preview"
                    )
                )
            )
        val patchEntries = AtomicInteger(0)
        var foldCount = 0
        var patchThread: Thread? = null
        var whileFoldHeld: OwnerContention? = null
        lateinit var store: SharedActivityStore
        store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                beforeRealtimeHighWaterFold = {
                    foldCount += 1
                    if (foldCount == 2) {
                        // The stale refresh holds the owner mid-fold: race the next patch in from a real thread.
                        val contender = thread(name = "activity-patch-contender") { store.dispatch(secondPatch) }
                        patchThread = contender
                        whileFoldHeld = awaitBlockedOnOwnerHeldByCurrentThread(contender) { patchEntries.get() }
                    }
                },
                onRealtimePatchOwnerEntered = { patchEntries.incrementAndGet() }
            )

        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        assertTrue(store.state.value.loaded && !store.state.value.refreshing, "initial load settled, state=${store.state.value}")

        store.dispatch(
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "message-1",
                        channelId = "thread-channel-1",
                        body = "First realtime preview"
                    )
                )
            )
        )
        assertEquals(MessageId("message-1"), store.state.value.items.single().latestActivityMessageId)
        assertEquals(1, patchEntries.get())

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        val contender = joinContender(patchThread, "realtime patch")
        runCurrent()

        val held = whileFoldHeld ?: error("stale refresh never reached the high-water fold, folds=$foldCount")
        assertTrue(held.blocked, "realtime patch never contended for the owner held by the fold: $held")
        assertEquals(1, held.entriesWhileHeld, "realtime patch entered the owner during the stale load fold")
        assertFalse(contender.isAlive)
        assertEquals(2, patchEntries.get(), "realtime patch entered the owner after the fold released it")
        assertFalse(store.state.value.refreshing)
        assertEquals(MessageId("message-new"), store.state.value.items.single().latestActivityMessageId)

        store.dispatch(SharedActivityIntent.Refresh)
        runCurrent()
        assertEquals(3, foldCount)
        assertFalse(store.state.value.refreshing)

        val reconciled = store.state.value.items.single()
        assertEquals(MessageId("message-new"), reconciled.latestActivityMessageId)
        assertEquals("Realtime preview", reconciled.latestActivityPreview)
        assertEquals(4, reconciled.replyCount)
        assertEquals(1, reconciled.unreadCount, "unread stays the server's")
    }

    private data class OwnerContention(
        val blocked: Boolean,
        val entriesWhileHeld: Int,
        val detail: String
    )

    /**
     * Called by the owner holder (inside the owner): returns once [contender] is BLOCKED on a
     * monitor owned by this thread, or has finished / exceeded the bound (reported, not thrown,
     * so the store's load is not failed from inside the hook). The bound only caps a broken run.
     */
    private fun awaitBlockedOnOwnerHeldByCurrentThread(
        contender: Thread,
        entries: () -> Int
    ): OwnerContention {
        val holderId = Thread.currentThread().id
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + ContentionBoundNanos
        while (true) {
            val info = threads.getThreadInfo(contender.id)
            val detail =
                "state=${info?.threadState} lock=${info?.lockName} lockOwner=${info?.lockOwnerId} holder=$holderId"
            if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == holderId) {
                return OwnerContention(blocked = true, entriesWhileHeld = entries(), detail = detail)
            }
            if (!contender.isAlive || System.nanoTime() > deadline) {
                return OwnerContention(blocked = false, entriesWhileHeld = entries(), detail = "alive=${contender.isAlive} $detail")
            }
            Thread.yield()
        }
    }

    private fun joinContender(contender: Thread?, what: String): Thread {
        val started = contender ?: error("$what contender was never started")
        started.join(ContentionBoundNanos / 1_000_000L)
        assertFalse(started.isAlive, "$what contender still stuck after the owner was released")
        return started
    }

    private companion object {
        const val ContentionBoundNanos = 10_000_000_000L
    }
}
