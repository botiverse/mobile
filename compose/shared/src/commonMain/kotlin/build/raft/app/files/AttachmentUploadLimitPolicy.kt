package build.raft.app

import build.raft.app.data.PlatformFile

internal data class AttachmentUploadLimitDecision(
    val accepted: List<PlatformFile>,
    val rejected: List<PlatformFile>
) {
    val largestRejectedBytes: Long? = rejected.mapNotNull(PlatformFile::sizeBytes).maxOrNull()
}

internal fun applyServerAttachmentUploadLimit(
    files: List<PlatformFile>,
    maxBytes: Long
): AttachmentUploadLimitDecision {
    require(maxBytes > 0L)
    val rejected = files.filter { file -> file.sizeBytes?.let { it > maxBytes } == true }
    return AttachmentUploadLimitDecision(
        accepted = files.filterNot(rejected.toSet()::contains),
        rejected = rejected
    )
}
