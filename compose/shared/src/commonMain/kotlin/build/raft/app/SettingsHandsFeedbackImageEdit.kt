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
    val groupId: String,
    val index: Int,
    val total: Int,
    val file: PlatformFile
)

internal sealed interface SettingsHandsFeedbackImageEditFoldResult {
    data class Pending(val session: SettingsHandsFeedbackImageEditSession) :
        SettingsHandsFeedbackImageEditFoldResult

    data class Complete(val files: List<PlatformFile>) :
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
        val pageGeneration: String
    )

    private var activeClaim: Claim? = null

    fun tryClaim(session: SettingsHandsFeedbackImageEditSession): Boolean {
        if (activeClaim != null) return false
        activeClaim = session.toClaim()
        return true
    }

    fun isCurrent(
        session: SettingsHandsFeedbackImageEditSession,
        currentAccountId: String,
        currentPageGeneration: String
    ): Boolean =
        activeClaim == session.toClaim() &&
            currentAccountId == session.accountId &&
            currentPageGeneration == session.pageGeneration

    fun release(session: SettingsHandsFeedbackImageEditSession) {
        if (activeClaim == session.toClaim()) {
            activeClaim = null
        }
    }

    private fun SettingsHandsFeedbackImageEditSession.toClaim(): Claim =
        Claim(
            groupId = groupId,
            accountId = accountId,
            pageGeneration = pageGeneration
        )
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
    if (data.optString("action").orEmpty() != "complete") return null
    val threadKey = data.optString("threadKey").takeIf { it.isNotBlank() } ?: return null
    val actionId = data.optString("actionId").takeIf { it.isNotBlank() } ?: return null
    val groupId = data.optString("groupId").takeIf { it.isNotBlank() } ?: return null
    val fileRef = data.optString("fileRef").takeIf { it.isNotBlank() } ?: return null
    val index = data.optInt("groupIndex", -1)
    val total = data.optInt("groupTotal", -1)
    val fileName = data.optString("fileName").takeIf { it.isNotBlank() } ?: "image.png"
    val mimeType = data.optString("mimeType").takeIf { it.isNotBlank() } ?: "image/png"
    val previewUri = data.optString("previewUri").takeIf { it.isNotBlank() } ?: fileRef
    return SettingsHandsFeedbackImageEditEvent(
        threadKey = threadKey,
        actionId = actionId,
        groupId = groupId,
        index = index,
        total = total,
        file =
            PlatformFile(
                id = "feedback-edited-${groupId.take(24)}-$index",
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = data.optLong("sizeBytes", 0L).takeIf { it > 0L },
                previewUri = previewUri,
                fileRef = fileRef,
                source = "image_annotation"
            )
    )
}

internal fun settingsHandsFeedbackImageEditItemKey(
    event: SettingsHandsFeedbackImageEditEvent
): String =
    listOf(
        event.actionId,
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
        SettingsHandsFeedbackImageEditFoldResult.Complete(orderedFiles)
    } else {
        SettingsHandsFeedbackImageEditFoldResult.Failed("missing_group_item")
    }
}

internal fun promoteSettingsHandsFeedbackEditedAttachments(
    existing: List<PlatformFile>,
    edited: List<PlatformFile>,
    attachmentMaxBytes: Long,
    attachmentLimit: Int
): SettingsHandsFeedbackAttachmentPromotion {
    val durable =
        edited.filter { file ->
            !file.fileRef.isNullOrBlank() &&
                file.source == "settings_feedback_draft" &&
                file.sizeBytes?.let { it > 0L } == true
        }
    val accepted = durable.filter { file -> file.sizeBytes?.let { it <= attachmentMaxBytes } == true }
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
    val remainingSlots = (attachmentLimit - currentExisting().size).coerceAtLeast(0)
    val selected = edited.take(remainingSlots)
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
                    attachmentLimit = attachmentLimit
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
