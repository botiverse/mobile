package build.raft.app

import build.raft.app.data.PlatformFile

/**
 * Reopening an uploaded composer tile still edits a process-local file. The remote row ids are
 * replacement witnesses, not remote-preview authority. Start with the picker editor contract
 * rather than a lazy remote attachment payload, which would require an authenticated owner
 * that the opaque ephemeral route intentionally does not serialize.
 */
internal fun threadComposerUploadedImagePreviewPayload(
    threadInstanceKey: String,
    actionId: String,
    files: List<PlatformFile>,
    replacementAttachmentIds: List<String>,
    selectedIndex: Int,
    originalImage: Boolean,
): RaftFilePreviewPayload? {
    if (files.isEmpty() || files.any { it.fileRef.isNullOrBlank() } ||
        replacementAttachmentIds.size != files.size || replacementAttachmentIds.any(String::isBlank)
    ) return null
    val local = threadComposerImageAnnotationPayload(threadInstanceKey, actionId, files, originalImage)
        ?: return null
    // Keep the existing single-event contract; multi-image editors emit an atomic N/N group.
    if (files.size == 1) {
        return local.copy(
            imageGallery = emptyList(),
            // The draft already owns this input; cancelling a second preview must not delete it.
            composerImageOwnedTemporaryFileRefs = emptyList(),
            composerImageReplaceAttachmentId = replacementAttachmentIds.single(),
            messageDesc = null
        )
    }
    return local.copy(
        composerImageOwnedTemporaryFileRefs = emptyList(),
        imageGallery = files.mapIndexed { index, file ->
            file.toComposerImageGalleryImage().copy(
                composerImageReplaceAttachmentId = replacementAttachmentIds[index],
                composerImageOwnedTemporary = false
            )
        }
    ).withImageGalleryIndex(selectedIndex)
}
