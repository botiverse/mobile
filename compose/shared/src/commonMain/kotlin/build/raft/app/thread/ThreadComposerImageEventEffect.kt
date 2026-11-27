package build.raft.app.thread

import build.raft.app.base.BridgeModule
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftEventBus
import build.raft.app.settings.observe
import build.raft.app.optString
import build.raft.app.parseRaftFilePreviewComposerImageEvent
import build.raft.app.raftFilePreviewAbortComposerImageEventSession
import build.raft.app.raftFilePreviewClaimComposerImageEvent
import build.raft.app.raftFilePreviewFinishComposerImageEventSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import build.raft.app.RAFT_COMPOSER_IMAGE_EDIT_EVENT
import build.raft.app.RaftFilePreviewComposerImageEventAdmission
import build.raft.app.ThreadComposerImageEventSessionTracker
import build.raft.app.abortThreadComposerImageEventSessions
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The composer image editor's event-bus consumer. The mutex, accumulators and session tracker are
 * remembered by the page; this effect only uses them.
 */
@Composable
@NonRestartableComposable
internal fun ThreadComposerImageEventEffect(
    bridge: BridgeModule,
    eventBus: RaftEventBus,
    threadInstanceKey: String,
    coroutineScope: CoroutineScope,
    threadSendScope: CoroutineScope,
    composerImageEventBatchMutex: Mutex,
    composerImageEventBatches: MutableMap<ThreadComposerImageEventBatchKey, ThreadComposerImageEventBatchAccumulator>,
    composerImageEventSessionTracker: ThreadComposerImageEventSessionTracker,
    latestUploadComposerAttachmentFilesState:
        State<suspend (List<PlatformFile>, String?, Boolean, Map<String, String>) -> Boolean>,
) {
    val latestUploadComposerAttachmentFiles by latestUploadComposerAttachmentFilesState
    DisposableEffect(eventBus, threadInstanceKey) {
        // Event-bus callbacks can be re-entrant and may be dispatched from different host turns.
        // Keep the callback side-effect-free: one sequential consumer owns parsing, accumulation,
        // registry admission, upload handoff and terminal cleanup.  In particular, a malformed
        // replay queued behind an already admitted gallery cannot abort that in-flight batch.
        val eventMailbox = Channel<String>(Channel.UNLIMITED)

        /**
         * Remove every in-flight accumulator for one preview session while the same mutex also
         * retires the registry session.  A malformed/conflicting member must poison the whole
         * N/N handoff; leaving a sibling accumulator alive would let a later callback claim an
         * otherwise orphaned temporary ref.
         */
        fun abortBatchSessionLocked(sessionId: String): Set<String> {
            val normalizedSessionId = sessionId.trim()
            // Once the complete batch has crossed into the process-owned uploader, a late
            // malformed/conflicting replay must not tear down the refs that uploader is reading.
            // Its NonCancellable handoff owns the terminal finish path instead.
            if (composerImageEventSessionTracker.isHandoffStarted(normalizedSessionId)) {
                return emptySet()
            }
            composerImageEventSessionTracker.release(normalizedSessionId)
            composerImageEventBatches.keys
                .filter { key ->
                    key.sessionId == normalizedSessionId && key.threadKey == threadInstanceKey
                }
                .forEach { key ->
                    composerImageEventBatches.remove(key)?.abort()
                }
            return raftFilePreviewAbortComposerImageEventSession(normalizedSessionId)
        }

        suspend fun abortBatchSession(sessionId: String, reason: String): Set<String> {
            val cleanupRefs =
                composerImageEventBatchMutex.withLock {
                    abortBatchSessionLocked(sessionId)
                }
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = sessionId.trim(),
                    fileRefs = cleanupRefs.toList()
                )
            }
            bridge.logWarn(
                "composer_image_event_batch_aborted sessionId=${sessionId.trim()} " +
                    "reason=$reason cleanupRefs=${cleanupRefs.size}"
            )
            return cleanupRefs
        }

        data class ComposerImageBatchHandoff(
            val items: List<ThreadComposerImageEventBatchItem> = emptyList(),
            val admissions: List<RaftFilePreviewComposerImageEventAdmission.Accepted> = emptyList(),
            val ready: Boolean = false,
            val cleanupRefs: Set<String> = emptySet()
        )

        suspend fun processComposerImageEvent(eventDataSnapshot: String) {
                    val eventData = runCatching { JSONObject(eventDataSnapshot) }.getOrNull() ?: return
                    // Parse through the shared wire-contract validator before applying this
                    // ThreadPage's session/ownership policy.  This keeps malformed callbacks
                    // from reaching the registry and ensures metadata semantics match Settings.
                    val parsed = parseRaftFilePreviewComposerImageEvent(eventData)
                    val rawThreadKey = eventData.optString("threadKey").trim()
                    if (parsed == null) {
                        // Event-bus delivery is process-wide.  Only retire an invalid event's
                        // session when its raw thread key identifies this page; an unrelated
                        // page must not abort a live session before its owner can consume it.
                        val previewSessionId = eventData.optString("previewSessionId").trim()
                        if (
                            rawThreadKey != threadInstanceKey ||
                                !composerImageEventSessionTracker.owns(previewSessionId)
                        ) {
                            return
                        }
                        abortBatchSession(previewSessionId, reason = "malformed")
                        return
                    }
                    if (
                        parsed.threadKey != threadInstanceKey ||
                            !composerImageEventSessionTracker.owns(parsed.previewSessionId)
                    ) {
                        return
                    }
                    val previewSessionId = parsed.previewSessionId.trim()
                    val groupId = parsed.groupId.orEmpty().trim()
                    val expectedCount = parsed.groupTotal ?: 1
                    val replaceAttachmentId =
                        eventData.optString("replaceAttachmentId").trim().takeIf { it.isNotBlank() }
                    val item =
                        ThreadComposerImageEventBatchItem(
                            eventData = eventData,
                            payload = parsed,
                            replaceAttachmentId = replaceAttachmentId
                        )
                    val handoff =
                        composerImageEventBatchMutex.withLock {
                            val key =
                                ThreadComposerImageEventBatchKey(
                                    sessionId = previewSessionId,
                                    threadKey = threadInstanceKey,
                                    groupId = groupId
                                )
                            // A page can be disposed after an event has entered the mailbox but
                            // before this serialized offer runs.  Do not create a fresh
                            // accumulator after the tracker has closed; otherwise a late event
                            // could still claim a registry row from a page which no longer owns
                            // it.
                            if (!composerImageEventSessionTracker.owns(previewSessionId)) {
                                // This session belongs to another mounted page (or has already
                                // been disposed).  Ignore the process-wide replay; aborting by
                                // thread key alone could retire that other page's registry/ref.
                                ComposerImageBatchHandoff()
                            } else {
                                val accumulator =
                                    composerImageEventBatches[key]
                                        ?: ThreadComposerImageEventBatchAccumulator(
                                            key = key,
                                            expectedCount = expectedCount
                                        ).also { created ->
                                            composerImageEventBatches[key] = created
                                        }
                            when (val offer = accumulator.offer(item)) {
                                ThreadComposerImageEventBatchOffer.Added,
                                ThreadComposerImageEventBatchOffer.Duplicate ->
                                    ComposerImageBatchHandoff()

                                ThreadComposerImageEventBatchOffer.Rejected ->
                                    ComposerImageBatchHandoff(
                                        ready = false,
                                        cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                    )

                                is ThreadComposerImageEventBatchOffer.Complete -> {
                                    val items = offer.events
                                    val files =
                                        items.map { batchItem ->
                                            PlatformFile(
                                                id = "edited-${batchItem.actionId}",
                                                fileName = batchItem.payload.fileName,
                                                mimeType = batchItem.payload.mimeType,
                                                sizeBytes = batchItem.payload.sizeBytes,
                                                previewUri = batchItem.payload.previewUri,
                                                fileRef = batchItem.payload.fileRef,
                                                source = batchItem.payload.source
                                            )
                                        }
                                    val fileIds = files.map(PlatformFile::id)
                                    val fileRefs = files.mapNotNull(PlatformFile::fileRef)
                                    val filesValid =
                                        files.size == expectedCount &&
                                            files.all { file ->
                                                file.id.isNotBlank() &&
                                                    !file.fileRef.isNullOrBlank() &&
                                                    file.isComposerImageOwnedTemporary()
                                            } &&
                                            fileIds.toSet().size == fileIds.size &&
                                            fileRefs.toSet().size == fileRefs.size
                                    if (!filesValid) {
                                        ComposerImageBatchHandoff(
                                            cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                        )
                                    } else {
                                        val claimedAdmissions =
                                            items.map { batchItem ->
                                                raftFilePreviewClaimComposerImageEvent(
                                                    eventData = batchItem.eventData,
                                                    expectedThreadKey = threadInstanceKey
                                                )
                                            }
                                        val acceptedAdmissions =
                                            claimedAdmissions.mapNotNull { admission ->
                                                admission as? RaftFilePreviewComposerImageEventAdmission.Accepted
                                            }
                                        val claimComplete =
                                            acceptedAdmissions.size == items.size &&
                                                acceptedAdmissions.lastOrNull()?.sessionComplete == true &&
                                                acceptedAdmissions.dropLast(1).none { admission ->
                                                    admission.sessionComplete
                                                }
                                        if (!claimComplete) {
                                            ComposerImageBatchHandoff(
                                                cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                            )
                                        } else if (!composerImageEventSessionTracker.markHandoffStarted(previewSessionId)) {
                                            ComposerImageBatchHandoff(
                                                cleanupRefs = abortBatchSessionLocked(previewSessionId)
                                            )
                                        } else {
                                            // Keep the started session in the tracker until the
                                            // upload handoff's finally block.  Disposal needs to
                                            // distinguish this in-flight owner from a partial
                                            // accumulator and perform a post-join sweep.
                                            ComposerImageBatchHandoff(
                                                items = items,
                                                admissions = acceptedAdmissions,
                                                ready = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (handoff.cleanupRefs.isNotEmpty()) {
                        bridge.retireFilePreviewTemporaryFiles(
                            sessionId = previewSessionId,
                            fileRefs = handoff.cleanupRefs.toList()
                        )
                    }
                    if (!handoff.ready) return

                    val files =
                        handoff.items.map { batchItem ->
                            PlatformFile(
                                id = "edited-${batchItem.actionId}",
                                fileName = batchItem.payload.fileName,
                                mimeType = batchItem.payload.mimeType,
                                sizeBytes = batchItem.payload.sizeBytes,
                                previewUri = batchItem.payload.previewUri,
                                fileRef = batchItem.payload.fileRef,
                                source = batchItem.payload.source
                            )
                        }
                    val replaceAttachmentIdsByLocalId =
                        handoff.items.mapNotNull { batchItem ->
                            batchItem.replaceAttachmentId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { replacement ->
                                    "edited-${batchItem.actionId}" to replacement
                                }
                        }.toMap()
                    var transferred = false
                    try {
                        transferred =
                            // Attempts run on the process send scope, so page teardown must not
                            // cancel this waiter before those attempts publish their outcomes.
                            // Otherwise the `finally` below would retire ownership while a real
                            // network transfer was still in flight.
                            withContext(NonCancellable) {
                                latestUploadComposerAttachmentFiles(
                                    files,
                                    null,
                                    // Per-file admission: one oversize or failed image must not
                                    // reject the rest. Direct vs multipart is chosen per file.
                                    false,
                                    replaceAttachmentIdsByLocalId
                                )
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "composer_image_event_batch_upload_failed " +
                                "sessionId=$previewSessionId error=${error::class.simpleName.orEmpty()}"
                        )
                    } finally {
                        // Disposal cancels this consumer while the upload handoff is unwinding.
                        // Mutex.withLock is cancellable, so the terminal registry transition must
                        // be protected just like the upload itself; otherwise a cancellation can
                        // strand the pending session and its native temporary refs.
                        withContext(NonCancellable) {
                            val cleanupRefs =
                                composerImageEventBatchMutex.withLock {
                                // The accumulator remains present during the async upload so
                                // identical replays are harmless. Remove it only at terminal
                                // handoff, while finishing the registry under the same lock.
                                composerImageEventBatches.keys
                                    .filter { key ->
                                        key.sessionId == previewSessionId &&
                                            key.threadKey == threadInstanceKey
                                    }
                                    .forEach { key ->
                                        composerImageEventBatches.remove(key)?.abort()
                                    }
                                raftFilePreviewFinishComposerImageEventSession(
                                    sessionId = previewSessionId,
                                    transferred = transferred
                                )
                                }
                            composerImageEventSessionTracker.release(previewSessionId)
                            if (cleanupRefs.isNotEmpty()) {
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = previewSessionId,
                                    fileRefs = cleanupRefs.toList()
                                )
                            }
                            if (!transferred) {
                                bridge.logWarn(
                                    "composer_image_event_batch_rejected sessionId=$previewSessionId " +
                                        "reason=upload_handoff_failed cleanupRefs=${cleanupRefs.size}"
                                )
                            }
                        }
                    }
        }

        val consumerJob =
            coroutineScope.launch {
                for (eventDataSnapshot in eventMailbox) {
                    try {
                        processComposerImageEvent(eventDataSnapshot)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        bridge.logWarn(
                            "composer_image_event_consumer_failed " +
                                "error=${error::class.simpleName.orEmpty()}"
                        )
                    }
                }
            }
        val subscription =
            eventBus.observe(RAFT_COMPOSER_IMAGE_EDIT_EVENT, crossProcess = true) { data ->
                val eventDataSnapshot = data?.toString() ?: return@observe
                eventMailbox.trySend(eventDataSnapshot)
            }
        onDispose {
            subscription.close()
            eventMailbox.close()
            // Close admission before requesting consumer cancellation.  Cancellation is
            // cooperative; if the consumer is in a non-suspending reducer step, it can still
            // finish that step after `cancel()` returns.  The closed tracker makes such a late
            // step fail its ownership check instead of admitting a new batch during teardown.
            val closeResult = composerImageEventSessionTracker.close()
            consumerJob.cancel()
            threadSendScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    // The consumer may already be inside the process-owned NonCancellable upload
                    // handoff.  Wait for that handoff's finally block before sweeping its refs;
                    // aborting it first could delete a file while the uploader still reads it.
                    try {
                        consumerJob.cancelAndJoin()
                    } catch (_: CancellationException) {
                        // The enclosing NonCancellable cleanup remains authoritative even if the
                        // child reports its own cancellation while joining.
                    } catch (_: Throwable) {
                        // A failed join must not suppress the final registry/ledger sweep below.
                    }
                    val sessionIds = closeResult.abortSessionIds + closeResult.inFlightSessionIds
                    try {
                        composerImageEventBatchMutex.withLock {
                            abortThreadComposerImageEventSessions(
                                sessionIds = sessionIds,
                                threadKey = threadInstanceKey,
                                batches = composerImageEventBatches,
                                retire = { sessionId, refs ->
                                    bridge.retireFilePreviewTemporaryFiles(
                                        sessionId = sessionId,
                                        fileRefs = refs.toList()
                                    )
                                }
                            )
                        }
                    } finally {
                        sessionIds.forEach(composerImageEventSessionTracker::release)
                    }
                }
            }
        }
    }
}
