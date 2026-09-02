package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadComposerImageEventSessionTrackerTest {
    @Test
    fun closeReturnsOnlyPartialSessionsAndRejectsLateTracking() {
        val tracker = ThreadComposerImageEventSessionTracker()
        assertTrue(tracker.track("partial"))
        assertTrue(tracker.track("handoff"))
        assertTrue(tracker.markHandoffStarted("handoff"))
        val closed = tracker.close()
        assertEquals(setOf("partial"), closed.abortSessionIds)
        assertEquals(setOf("handoff"), closed.inFlightSessionIds)
        assertTrue(tracker.isHandoffStarted("handoff"))
        assertFalse(tracker.track("late"))
        assertFalse(tracker.owns("partial"))
        tracker.release("handoff")
        assertFalse(tracker.isHandoffStarted("handoff"))
    }

    @Test
    fun partialThreadSessionSweepRemovesAccumulatorRegistryLedgerAndRefs() {
        val tracker = ThreadComposerImageEventSessionTracker()
        val registry = SlockFilePreviewEphemeralActionRegistry()
        val ledger = SlockFilePreviewEphemeralCleanupLedger(InMemorySlockKeyValueStore())
        val sessionId = "thread-preview-partial"
        val threadKey = "thread-owner-a"
        val refs =
            mapOf(
                "action-0" to "file:///cache/thread-zero.jpg",
                "action-1" to "file:///cache/thread-one.jpg"
            )
        registry.register(
            sessionId = sessionId,
            threadKey = threadKey,
            actionIds = refs.keys,
            ownedTemporaryFileRefs = refs.values.toSet()
        )
        val prepared =
            requireNotNull(
                registry.prepareDeliveryBatch(
                    sessionId = sessionId,
                    threadKey = threadKey,
                    fileRefByActionId = refs
                )
            )
        ledger.replace(sessionId, prepared.pendingFileRefs)

        val key = ThreadComposerImageEventBatchKey(sessionId, threadKey, "group-a")
        val batches = mutableMapOf<ThreadComposerImageEventBatchKey, ThreadComposerImageEventBatchAccumulator>()
        val accumulator = ThreadComposerImageEventBatchAccumulator(key, expectedCount = 2)
        val payload =
            SlockFilePreviewComposerImageEventPayload(
                previewSessionId = sessionId,
                threadKey = threadKey,
                actionId = "action-0",
                action = "complete",
                fileRef = refs.getValue("action-0"),
                previewUri = refs.getValue("action-0"),
                fileName = "thread-zero.jpg",
                mimeType = "image/jpeg",
                source = SLOCK_FILE_PREVIEW_IMAGE_EDITOR_SOURCE,
                compressed = false,
                sizeBytes = 10L,
                groupId = "group-a",
                groupIndex = 0,
                groupTotal = 2
            )
        assertIs<ThreadComposerImageEventBatchOffer.Added>(
            accumulator.offer(ThreadComposerImageEventBatchItem(JSONObject(), payload))
        )
        batches[key] = accumulator
        assertTrue(tracker.track(sessionId))
        val close = tracker.close()
        val retired = mutableMapOf<String, Set<String>>()

        abortThreadComposerImageEventSessions(
            sessionIds = close.abortSessionIds,
            threadKey = threadKey,
            batches = batches,
            registry = registry,
            cleanupLedger = ledger,
            retire = { id, fileRefs -> retired[id] = fileRefs }
        )

        assertTrue(batches.isEmpty())
        assertEquals(refs.values.toSet(), retired.getValue(sessionId))
        assertTrue(registry.activeSessionIds().isEmpty())
        assertTrue(ledger.take(sessionId).isEmpty())
    }

    @Test
    fun duplicateTrackIsIdempotentAndReleaseRemovesOwnership() {
        val tracker = ThreadComposerImageEventSessionTracker()
        assertTrue(tracker.track("session"))
        assertTrue(tracker.track(" session "))
        tracker.release(" session ")
        assertFalse(tracker.owns("session"))
        assertEquals(emptySet(), tracker.close().abortSessionIds)
    }

    @Test
    fun rejectedPreviewRegistrationAbortsTheExactSession() {
        val tracker = ThreadComposerImageEventSessionTracker()
        tracker.close()
        val aborted = mutableListOf<String>()

        assertFalse(
            registerThreadComposerImagePreviewSession(
                sessionId = "late-session",
                tracker = tracker,
                abort = aborted::add
            )
        )
        assertEquals(listOf("late-session"), aborted)
    }
}
