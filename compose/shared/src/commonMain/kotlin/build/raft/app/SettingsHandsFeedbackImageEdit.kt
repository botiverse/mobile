package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.resources.MR
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class SettingsHandsFeedbackImageEditRequest(
    val session: SettingsHandsFeedbackImageEditSession,
    val previewPayload: SlockFilePreviewPayload,
    val sourceStoreScope: SlockShellStoreScope
)

internal data class SettingsHandsFeedbackImageEditSession(
    val groupId: String,
    val threadKey: String,
    val accountId: String,
    val pageGeneration: String,
    val expectedCount: Int,
    val previewSessionId: String = "",
    val groupState: SlockImageEditGroupState =
        SlockImageEditGroupState(
            expectedGroupId = groupId,
            expectedCount = expectedCount
        ),
    val filesByIndex: Map<Int, PlatformFile> = emptyMap()
)

internal data class SettingsHandsFeedbackImageEditEvent(
    val threadKey: String,
    val actionId: String,
    /** "complete" appends an edited batch; "replace" substitutes one existing attachment. */
    val action: String = "complete",
    /** Attachment identity the replace action targets (feedbackComposerAttachmentId). */
    val replaceAttachmentId: String = "",
    val groupId: String,
    val index: Int,
    val total: Int,
    /** The closed source marker carried by the native editor finalization. */
    val source: String,
    /** Explicit JSON Boolean proving whether the final result is a JPEG compression output. */
    val compressed: Boolean,
    val file: PlatformFile
)

internal sealed interface SettingsHandsFeedbackImageEditFoldResult {
    data class Pending(val session: SettingsHandsFeedbackImageEditSession) :
        SettingsHandsFeedbackImageEditFoldResult

    data class Complete(val files: List<PlatformFile>, val replaceAttachmentId: String = "") :
        SettingsHandsFeedbackImageEditFoldResult

    data class Ignored(val session: SettingsHandsFeedbackImageEditSession) :
        SettingsHandsFeedbackImageEditFoldResult

    data class Failed(val reason: String) : SettingsHandsFeedbackImageEditFoldResult
}

internal data class SettingsHandsFeedbackAttachmentPromotion(
    val attachments: List<PlatformFile>,
    val rejectedTooLargeCount: Int,
    val trimmedByLimit: Boolean,
    val invalidDurableCount: Int = 0
)

internal val SettingsHandsFeedbackAttachmentPromotion.isCommittable: Boolean
    get() = rejectedTooLargeCount == 0 && invalidDurableCount == 0

internal sealed interface SettingsHandsFeedbackEditedGroupCommitResult {
    data class Committed(val promotion: SettingsHandsFeedbackAttachmentPromotion) :
        SettingsHandsFeedbackEditedGroupCommitResult

    data class Failed(val code: String) : SettingsHandsFeedbackEditedGroupCommitResult
}

internal class SettingsHandsFeedbackImageEditCommitGate {
    private data class Claim(
        val groupId: String,
        val accountId: String,
        val pageGeneration: String,
        val previewSessionId: String
    )

    private val lock = SynchronizedObject()
    private var activeClaim: Claim? = null

    fun tryClaim(session: SettingsHandsFeedbackImageEditSession): Boolean = synchronized(lock) {
        if (activeClaim != null) return@synchronized false
        activeClaim = session.toClaim()
        true
    }

    fun isCurrent(
        session: SettingsHandsFeedbackImageEditSession,
        currentAccountId: String,
        currentPageGeneration: String
    ): Boolean = synchronized(lock) {
        activeClaim == session.toClaim() &&
            currentAccountId == session.accountId &&
            currentPageGeneration == session.pageGeneration
    }

    fun release(session: SettingsHandsFeedbackImageEditSession) {
        synchronized(lock) {
            if (activeClaim == session.toClaim()) {
                activeClaim = null
            }
        }
    }

    /** Clear a page-owned claim during disposal, including after its pending UI state was nulled. */
    fun clear() {
        synchronized(lock) { activeClaim = null }
    }

    private fun SettingsHandsFeedbackImageEditSession.toClaim(): Claim =
        Claim(
            groupId = groupId,
            accountId = accountId,
            pageGeneration = pageGeneration,
            previewSessionId = previewSessionId.trim()
        )
}

/** Page-owned fence for sessions that may outlive the pending UI state during async commit. */
internal class SettingsHandsFeedbackImageEditSessionTracker {
    private val lock = SynchronizedObject()
    /** False means a partial group is still collecting; true means promotion is in flight. */
    private val owned = linkedMapOf<String, Boolean>()
    /** Keep an in-flight handoff visible to a racing opener after page disposal. */
    private val closedInFlightSessionIds = linkedSetOf<String>()

    /** Once disposed, a late event cannot re-admit a session into this page. */
    private var disposed = false

    fun track(sessionId: String): Boolean {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return false
        return synchronized(lock) {
            if (disposed) {
                false
            } else {
                if (normalized !in owned) {
                    owned[normalized] = false
                }
                true
            }
        }
    }

    fun owns(sessionId: String): Boolean = synchronized(lock) {
        !disposed && sessionId.trim() in owned
    }

    /** Run a final durable write only while this page still owns the session. */
    fun runIfOwned(sessionId: String, block: () -> Unit): Boolean = synchronized(lock) {
        val normalized = sessionId.trim()
        if (disposed || normalized !in owned) {
            false
        } else {
            block()
            true
        }
    }

    fun release(sessionId: String) {
        synchronized(lock) {
            val normalized = sessionId.trim()
            owned.remove(normalized)
            closedInFlightSessionIds.remove(normalized)
        }
    }

    /**
     * Atomically revoke every partial session before its registry rows are retired.
     *
     * Event handling may already have read [owns] when a picker replacement or submit begins.
     * Removing the partial witness here makes its later [markHandoffStarted] fail, so the event
     * cannot cross into the uploader while the caller is deleting its temporary refs.  Sessions
     * whose handoff has started remain owned until their terminal `finally` path releases them.
     */
    fun takePartialSessionIds(): Set<String> = synchronized(lock) {
        val partial = owned.filterValues { started -> !started }.keys.toSet()
        partial.forEach(owned::remove)
        partial
    }

    fun isHandoffStarted(sessionId: String): Boolean = synchronized(lock) {
        val normalized = sessionId.trim()
        owned[normalized] == true || normalized in closedInFlightSessionIds
    }

    /** Atomically transfer a complete group into the page-independent promotion handoff. */
    fun markHandoffStarted(sessionId: String): Boolean {
        val normalized = sessionId.trim()
        if (normalized.isBlank()) return false
        return synchronized(lock) {
            if (disposed || !owned.containsKey(normalized) || owned[normalized] == true) {
                false
            } else {
                owned[normalized] = true
                true
            }
        }
    }

    /** Snapshot current ownership without closing the page; used when a new picker replaces it. */
    fun snapshot(): SettingsHandsFeedbackImageEditSessionCloseResult = synchronized(lock) {
        SettingsHandsFeedbackImageEditSessionCloseResult(
            abortSessionIds = owned.filterValues { started -> !started }.keys.toSet(),
            inFlightSessionIds = owned.filterValues { started -> started }.keys.toSet()
        )
    }

    /** Atomically closes this page's admission and separates partial/in-flight sessions. */
    fun closeAndSnapshot(): SettingsHandsFeedbackImageEditSessionCloseResult = synchronized(lock) {
        disposed = true
        val partial = owned.filterValues { started -> !started }.keys.toSet()
        val inFlight = owned.filterValues { started -> started }.keys.toSet()
        closedInFlightSessionIds += inFlight
        owned.clear()
        SettingsHandsFeedbackImageEditSessionCloseResult(partial, inFlight)
    }

    fun isDisposed(): Boolean = synchronized(lock) { disposed }
}

internal data class SettingsHandsFeedbackImageEditSessionCloseResult(
    val abortSessionIds: Set<String>,
    val inFlightSessionIds: Set<String>
) {
    val allSessionIds: Set<String>
        get() = abortSessionIds + inFlightSessionIds
}

/**
 * Tracks event jobs on a detached page scope so disposal can cancel and join them before retiring
 * their preview sessions. The tracker itself is closed before the join is scheduled, which rejects
 * any callback racing with composition teardown.
 */
internal class SettingsHandsFeedbackImageEditEventJobTracker {
    private val lock = SynchronizedObject()
    private val jobs = linkedSetOf<Job>()
    private var closed = false

    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job? {
        lateinit var job: Job
        synchronized(lock) {
            if (closed) return null
            job = scope.launch(start = CoroutineStart.LAZY) { block() }
            jobs += job
        }
        job.invokeOnCompletion { synchronized(lock) { jobs.remove(job) } }
        job.start()
        return job
    }

    fun closeAndSnapshot(): Set<Job> = synchronized(lock) {
        closed = true
        jobs.toSet().also { jobs.clear() }
    }
}

/**
 * A Settings event is consumable only by the page instance which opened its preview session.
 * Keeping this predicate outside the composable makes the two-instance isolation contract
 * executable without requiring a Compose host in common tests.
 */
internal fun settingsHandsFeedbackImageEditEventMatchesSession(
    session: SettingsHandsFeedbackImageEditSession?,
    tracker: SettingsHandsFeedbackImageEditSessionTracker,
    eventSessionId: String,
    eventThreadKey: String
): Boolean {
    val current = session ?: return false
    val normalizedSessionId = eventSessionId.trim()
    return normalizedSessionId.isNotBlank() &&
        normalizedSessionId == current.previewSessionId &&
        eventThreadKey.trim() == current.threadKey &&
        tracker.owns(normalizedSessionId)
}

/**
 * A rejected action is terminal only after the process registry no longer owns its session.
 * Duplicate delivery of one member in a partial N/N group is a non-terminal rejection: releasing
 * the page tracker there would strand the still-valid sibling actions.
 */
internal fun settingsHandsFeedbackRejectedAdmissionIsTerminal(
    admission: SlockFilePreviewComposerImageEventAdmission.Rejected,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry()
): Boolean =
    admission.orphanedTemporaryFileRefs.isNotEmpty() ||
        admission.sessionId.isBlank() ||
        !registry.hasSession(admission.sessionId)

/**
 * Synchronously abort all sessions still owned by one Settings page.
 *
 * Registry/ledger transitions are deliberately synchronous: native retirement must quarantine
 * every admitted ref before the page can disappear.  The callback is injected for tests and is
 * the same bridge operation used by production.
 */
internal fun abortSettingsHandsFeedbackImageEditSessions(
    sessionIds: Collection<String>,
    registry: SlockFilePreviewEphemeralActionRegistry = slockFilePreviewEphemeralActionRegistry(),
    cleanupLedger: SlockFilePreviewEphemeralCleanupLedger = slockFilePreviewEphemeralCleanupLedger(),
    retire: (sessionId: String, fileRefs: Set<String>) -> Unit = { id, refs ->
        ThreadComposerTemporaryFileRetirement.retire(id, refs)
    }
): Set<String> {
    val normalizedIds = sessionIds.map(String::trim).filter(String::isNotBlank).toSet()
    val cleanupRefs = linkedSetOf<String>()
    normalizedIds.forEach { sessionId ->
        val refs = slockFilePreviewAbortComposerImageEventSession(
            sessionId = sessionId,
            registry = registry,
            cleanupLedger = cleanupLedger
        )
        if (refs.isNotEmpty()) {
            cleanupRefs += refs
            retire(sessionId, refs)
        }
    }
    return cleanupRefs
}


internal fun newSettingsHandsFeedbackImageEditId(
    randomLong: () -> Long = { Random.nextLong() }
): String =
    "feedback-image-${randomLong().toULong().toString(36)}-${randomLong().toULong().toString(36)}"

internal fun settingsHandsFeedbackImageEditRequest(
    accountId: String,
    pageGeneration: String,
    files: List<PlatformFile>,
    sourceStoreScope: SlockShellStoreScope,
    groupId: String = newSettingsHandsFeedbackImageEditId()
): SettingsHandsFeedbackImageEditRequest? {
    if (groupId.isBlank() || pageGeneration.isBlank() || files.isEmpty()) return null
    if (files.any { it.fileRef.isNullOrBlank() }) return null
    val firstFile = files.first()
    val previewUri = firstFile.composerLocalPreviewUri().orEmpty()
    val threadKey = "feedback-$groupId"
    val session =
        SettingsHandsFeedbackImageEditSession(
            groupId = groupId,
            threadKey = threadKey,
            accountId = accountId,
            pageGeneration = pageGeneration,
            expectedCount = files.size
        )
    return SettingsHandsFeedbackImageEditRequest(
        session = session,
        sourceStoreScope = sourceStoreScope,
        previewPayload =
            SlockFilePreviewPayload(
                kind = "image",
                titleDesc = MR.strings.file_preview_image.desc(),
                subtitle = firstFile.fileName,
                name = firstFile.fileName,
                path = firstFile.fileName,
                meta =
                    listOf(
                        firstFile.sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(),
                        firstFile.mimeType.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                imageUrl = previewUri.takeIf { it.isNotBlank() },
                messageDesc =
                    if (previewUri.isBlank()) {
                        MR.strings.file_preview_error_image_unavailable.desc()
                    } else {
                        null
                    },
                externalFileName = firstFile.fileName,
                externalMimeType = firstFile.mimeType.orEmpty(),
                imageGallery = files.map { file -> file.toComposerImageGalleryImage() },
                composerImageThreadKey = threadKey,
                composerImageActionId = "feedback-image-$groupId",
                composerImageGroupId = groupId,
                composerImageFileRef = firstFile.fileRef.orEmpty(),
                composerImageFileName = firstFile.fileName,
                composerImageMimeType = firstFile.mimeType.orEmpty(),
                composerImageSizeBytes = firstFile.sizeBytes,
                composerImagePreviewUri = previewUri,
                composerImageOwnedTemporaryFileRefs =
                    files.filter(PlatformFile::isComposerImageOwnedTemporary)
                        .mapNotNull(PlatformFile::fileRef),
                // Feedback has no "original image" control. Freeze the product default here
                // instead of inheriting SlockFilePreviewPayload's non-composer-safe false
                // default: editor PNG output must receive the same JPEG pass as composer images.
                composerImageCompressAfterEdit = true,
                composerImageCanComplete = true,
                composerImageCanEdit = true
            )
    )
}

/**
 * task #286: build the editable preview for one already-mounted feedback attachment.
 *
 * The session is a single-member group (N=1) whose completion carries action="replace" with the
 * target attachment identity. The page opens the shared file-preview page with editing enabled;
 * the resulting event folds back through the same ownership/commit machinery as a picked batch.
 */
internal fun settingsHandsFeedbackImageEditPreviewRequest(
    accountId: String,
    pageGeneration: String,
    file: PlatformFile,
    sourceStoreScope: SlockShellStoreScope,
    groupId: String = newSettingsHandsFeedbackImageEditId()
): SettingsHandsFeedbackImageEditRequest? {
    if (groupId.isBlank() || pageGeneration.isBlank() || file.fileRef.isNullOrBlank()) return null
    val previewUri = file.composerLocalPreviewUri().orEmpty()
    val threadKey = "feedback-$groupId"
    val session =
        SettingsHandsFeedbackImageEditSession(
            groupId = groupId,
            threadKey = threadKey,
            accountId = accountId,
            pageGeneration = pageGeneration,
            expectedCount = 1
        )
    return SettingsHandsFeedbackImageEditRequest(
        session = session,
        sourceStoreScope = sourceStoreScope,
        previewPayload =
            SlockFilePreviewPayload(
                kind = "image",
                requestedKind = "image",
                titleDesc = MR.strings.file_preview_image.desc(),
                subtitle = file.fileName,
                name = file.fileName,
                path = file.fileName,
                meta =
                    listOf(
                        file.sizeBytes?.let(::formatAttachmentSizeLabel).orEmpty(),
                        file.mimeType.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
                imageUrl = previewUri.takeIf { it.isNotBlank() },
                externalUrl = previewUri.takeIf { it.isNotBlank() },
                externalFileName = file.fileName,
                externalMimeType = file.mimeType.orEmpty(),
                source = file.source,
                attachmentId = file.feedbackComposerAttachmentId(),
                imageGallery =
                    listOf(
                        file.toComposerImageGalleryImage().copy(
                            composerImageReplaceAttachmentId = file.feedbackComposerAttachmentId()
                        )
                    ),
                composerImageThreadKey = threadKey,
                composerImageActionId = "feedback-image-edit-$groupId",
                composerImageGroupId = groupId,
                composerImageFileRef = file.fileRef.orEmpty(),
                composerImageFileName = file.fileName,
                composerImageMimeType = file.mimeType.orEmpty(),
                composerImageSizeBytes = file.sizeBytes,
                composerImagePreviewUri = previewUri,
                composerImageOwnedTemporaryFileRefs =
                    listOf(file).filter(PlatformFile::isComposerImageOwnedTemporary)
                        .mapNotNull(PlatformFile::fileRef),
                composerImageCompressAfterEdit = true,
                composerImageCanComplete = true,
                composerImageCanEdit = true
            )
    )
}

internal fun normalizeSettingsHandsFeedbackPickerFiles(
    files: List<PlatformFile>,
    imageOnly: Boolean
): List<PlatformFile> {
    if (!imageOnly) return files
    return files.map { file ->
        val normalizedMimeType = file.mimeType.orEmpty().substringBefore(';').trim().lowercase()
        val extension =
            when (normalizedMimeType) {
                "image/avif" -> ".avif"
                "image/bmp" -> ".bmp"
                "image/gif" -> ".gif"
                "image/heic" -> ".heic"
                "image/heif" -> ".heif"
                "image/jpeg", "image/jpg" -> ".jpg"
                "image/png" -> ".png"
                "image/svg+xml" -> ".svg"
                "image/webp" -> ".webp"
                else -> null
            }
        if (extension == null || slockComposerImagePickerIsStrictImage(file.fileName, normalizedMimeType)) {
            file
        } else {
            file.copy(fileName = "${file.fileName.ifBlank { "image" }}$extension")
        }
    }
}

internal fun parseSettingsHandsFeedbackImageEditEvent(
    data: JSONObject
): SettingsHandsFeedbackImageEditEvent? {
    // Keep Settings and Thread on one strict parser.  In particular, group metadata and the
    // explicit source/compressed finalization proof must not drift between consumers.
    val payload =
        parseSlockFilePreviewComposerImageEvent(
            data = data,
            requireGroup = true
        ) ?: return null
    if (payload.action != "complete" && payload.action != "replace") return null
    val groupId = payload.groupId ?: return null
    val index = payload.groupIndex ?: return null
    val total = payload.groupTotal ?: return null
    return SettingsHandsFeedbackImageEditEvent(
        threadKey = payload.threadKey,
        actionId = payload.actionId,
        action = payload.action,
        replaceAttachmentId = payload.replaceAttachmentId,
        groupId = groupId,
        index = index,
        total = total,
        source = payload.source,
        compressed = payload.compressed,
        file =
            PlatformFile(
                id = "feedback-edited-${groupId.take(24)}-$index",
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                sizeBytes = payload.sizeBytes,
                previewUri = payload.previewUri,
                fileRef = payload.fileRef,
                // The wire source describes finalization; Settings promotion owns the durable
                // draft source independently so it cannot accidentally persist a transient
                // editor/compressor marker.
                source = "settings_feedback_draft"
            )
    )
}

internal fun settingsHandsFeedbackImageEditItemKey(
    event: SettingsHandsFeedbackImageEditEvent
): String =
    listOf(
        event.actionId,
        event.source,
        event.compressed.toString(),
        event.file.fileRef,
        event.file.fileName,
        event.file.mimeType,
        event.file.sizeBytes?.toString(),
        event.file.previewUri
    ).joinToString(separator = "") { value ->
        value?.let { "${it.length}:$it" } ?: "-1:"
    }

internal fun foldSettingsHandsFeedbackImageEditEvent(
    session: SettingsHandsFeedbackImageEditSession,
    event: SettingsHandsFeedbackImageEditEvent,
    currentAccountId: String,
    currentPageGeneration: String
): SettingsHandsFeedbackImageEditFoldResult {
    if (
        session.accountId != currentAccountId ||
        session.pageGeneration != currentPageGeneration ||
        event.threadKey != session.threadKey
    ) {
        return SettingsHandsFeedbackImageEditFoldResult.Ignored(session)
    }
    val previousItems = session.groupState.itemsByIndex
    val nextGroupState =
        SlockImageEditGroupReconcile.fold(
            state = session.groupState,
            actualGroupId = event.groupId,
            index = event.index,
            total = event.total,
            itemKey = settingsHandsFeedbackImageEditItemKey(event)
        )
    val newReject = nextGroupState.rejects.firstOrNull { it !in session.groupState.rejects }
    if (newReject != null) {
        return SettingsHandsFeedbackImageEditFoldResult.Failed(newReject)
    }
    val accepted = nextGroupState.itemsByIndex.size == previousItems.size + 1
    if (!accepted) {
        return SettingsHandsFeedbackImageEditFoldResult.Pending(session.copy(groupState = nextGroupState))
    }
    val nextFilesByIndex = session.filesByIndex + (event.index to event.file)
    val nextSession =
        session.copy(
            groupState = nextGroupState,
            filesByIndex = nextFilesByIndex
        )
    if (!nextGroupState.isCommittable) {
        return SettingsHandsFeedbackImageEditFoldResult.Pending(nextSession)
    }
    val orderedFiles = (0 until session.expectedCount).mapNotNull(nextFilesByIndex::get)
    return if (orderedFiles.size == session.expectedCount) {
        SettingsHandsFeedbackImageEditFoldResult.Complete(
            files = orderedFiles,
            replaceAttachmentId = event.replaceAttachmentId
        )
    } else {
        SettingsHandsFeedbackImageEditFoldResult.Failed("missing_group_item")
    }
}

internal fun promoteSettingsHandsFeedbackEditedAttachments(
    existing: List<PlatformFile>,
    edited: List<PlatformFile>,
    attachmentMaxBytes: Long,
    attachmentLimit: Int,
    replaceAttachmentId: String = ""
): SettingsHandsFeedbackAttachmentPromotion {
    val durable =
        edited.filter { file ->
            !file.fileRef.isNullOrBlank() &&
                file.source == "settings_feedback_draft" &&
                file.sizeBytes?.let { it > 0L } == true
        }
    val accepted = durable.filter { file -> file.sizeBytes?.let { it <= attachmentMaxBytes } == true }
    // task #286: a replace completion swaps its target in place instead of appending; the target
    // already occupies a slot so the limit only applies to genuinely new members.
    if (replaceAttachmentId.isNotBlank()) {
        val targetIndex =
            existing.indexOfFirst { file -> (file.fileRef ?: file.id) == replaceAttachmentId }
        val replaced =
            if (targetIndex < 0) {
                // Target removed while the editor was open: mount the edit as a new entry so the
                // user's work is not silently dropped (limit still applies below).
                (existing + accepted).distinctBy { file -> file.fileRef ?: file.id }
            } else {
                existing.toMutableList().apply {
                    accepted.forEachIndexed { index, file ->
                        val slot = targetIndex + index
                        if (slot < size) set(slot, file) else add(file)
                    }
                }
            }
        return SettingsHandsFeedbackAttachmentPromotion(
            attachments = replaced.take(attachmentLimit),
            rejectedTooLargeCount = durable.size - accepted.size,
            trimmedByLimit = replaced.size > attachmentLimit,
            invalidDurableCount = edited.size - durable.size
        )
    }
    val merged = (existing + accepted).distinctBy { file -> file.fileRef ?: file.id }
    return SettingsHandsFeedbackAttachmentPromotion(
        attachments = merged.take(attachmentLimit),
        rejectedTooLargeCount = durable.size - accepted.size,
        trimmedByLimit = merged.size > attachmentLimit,
        invalidDurableCount = edited.size - durable.size
    )
}

internal suspend fun commitSettingsHandsFeedbackEditedGroup(
    existing: List<PlatformFile>,
    edited: List<PlatformFile>,
    attachmentMaxBytes: Long,
    attachmentLimit: Int,
    replaceAttachmentId: String = "",
    isCurrent: () -> Boolean,
    promote: suspend (List<PlatformFile>) -> DataResult<List<PlatformFile>>,
    persist: (List<PlatformFile>) -> Unit,
    currentExisting: () -> List<PlatformFile> = { existing },
    initialDraftResetGeneration: Long = 0L,
    currentDraftResetGeneration: () -> Long = { initialDraftResetGeneration },
    discard: suspend (List<PlatformFile>) -> Unit = {}
): SettingsHandsFeedbackEditedGroupCommitResult {
    if (!isCurrent() || currentDraftResetGeneration() != initialDraftResetGeneration) {
        return SettingsHandsFeedbackEditedGroupCommitResult.Failed(
            "settings_feedback_attachment_session_stale"
        )
    }
    val isReplace = replaceAttachmentId.isNotBlank()
    val remainingSlots = (attachmentLimit - currentExisting().size).coerceAtLeast(0)
    val selected = if (isReplace) edited.take(1) else edited.take(remainingSlots)
    if (selected.isEmpty()) {
        return SettingsHandsFeedbackEditedGroupCommitResult.Failed(
            "settings_feedback_attachment_limit_reached"
        )
    }
    val promoted =
        try {
            promote(selected)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return SettingsHandsFeedbackEditedGroupCommitResult.Failed(
                "settings_feedback_attachment_promotion_failed"
            )
        }
    return when (promoted) {
        is DataResult.Content -> {
            if (!isCurrent() || currentDraftResetGeneration() != initialDraftResetGeneration) {
                discardSettingsHandsFeedbackPromotedAttachments(promoted.data, discard)
                return SettingsHandsFeedbackEditedGroupCommitResult.Failed(
                    "settings_feedback_attachment_session_stale"
                )
            }
            val promotionBeforeSelectionTrim =
                promoteSettingsHandsFeedbackEditedAttachments(
                    existing = currentExisting(),
                    edited = promoted.data,
                    attachmentMaxBytes = attachmentMaxBytes,
                    attachmentLimit = attachmentLimit,
                    replaceAttachmentId = replaceAttachmentId
                )
            val promotion =
                promotionBeforeSelectionTrim.copy(
                    trimmedByLimit =
                        promotionBeforeSelectionTrim.trimmedByLimit || edited.size > selected.size
                )
            if (
                !promotion.isCommittable ||
                promoted.data.size != selected.size ||
                promotionBeforeSelectionTrim.trimmedByLimit
            ) {
                discardSettingsHandsFeedbackPromotedAttachments(promoted.data, discard)
                val code =
                    when {
                        promotion.rejectedTooLargeCount > 0 ->
                            "settings_feedback_attachment_too_large"
                        promotionBeforeSelectionTrim.trimmedByLimit ->
                            "settings_feedback_attachment_limit_reached"
                        else -> "settings_feedback_attachment_promotion_incomplete"
                    }
                SettingsHandsFeedbackEditedGroupCommitResult.Failed(code)
            } else {
                try {
                    persist(promotion.attachments)
                    SettingsHandsFeedbackEditedGroupCommitResult.Committed(promotion)
                } catch (failure: Throwable) {
                    discardSettingsHandsFeedbackPromotedAttachments(promoted.data, discard)
                    if (failure is CancellationException) throw failure
                    SettingsHandsFeedbackEditedGroupCommitResult.Failed(
                        "settings_feedback_attachment_persistence_failed"
                    )
                }
            }
        }
        is DataResult.Error -> SettingsHandsFeedbackEditedGroupCommitResult.Failed(promoted.error.code)
        is DataResult.Empty ->
            SettingsHandsFeedbackEditedGroupCommitResult.Failed(
                "settings_feedback_attachment_promotion_empty"
            )
        is DataResult.NoPermission ->
            SettingsHandsFeedbackEditedGroupCommitResult.Failed(promoted.blocker.code)
    }
}

private suspend fun discardSettingsHandsFeedbackPromotedAttachments(
    attachments: List<PlatformFile>,
    discard: suspend (List<PlatformFile>) -> Unit
) {
    try {
        discard(attachments)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
    }
}
