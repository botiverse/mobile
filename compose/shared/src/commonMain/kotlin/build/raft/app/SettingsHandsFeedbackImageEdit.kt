package build.raft.app

import build.raft.app.data.PlatformFile
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.resources.MR
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlin.random.Random

internal data class SettingsHandsFeedbackImageEditRequest(
    val session: SettingsHandsFeedbackImageEditSession,
    val previewPayload: SlockFilePreviewPayload
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
    val trimmedByLimit: Boolean
)

internal fun newSettingsHandsFeedbackImageEditId(
    randomLong: () -> Long = { Random.nextLong() }
): String =
    "feedback-image-${randomLong().toULong().toString(36)}-${randomLong().toULong().toString(36)}"

internal fun settingsHandsFeedbackImageEditRequest(
    accountId: String,
    pageGeneration: String,
    files: List<PlatformFile>,
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
                composerImageCanComplete = true,
                composerImageCanEdit = true
            )
    )
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
    val accepted =
        edited.filter { file ->
            !file.fileRef.isNullOrBlank() && (file.sizeBytes ?: 0L) <= attachmentMaxBytes
        }
    val merged = (existing + accepted).distinctBy { file -> file.fileRef ?: file.id }
    return SettingsHandsFeedbackAttachmentPromotion(
        attachments = merged.take(attachmentLimit),
        rejectedTooLargeCount = edited.size - accepted.size,
        trimmedByLimit = merged.size > attachmentLimit
    )
}
