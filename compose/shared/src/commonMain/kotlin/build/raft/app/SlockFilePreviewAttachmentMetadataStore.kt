package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.normalizedOrNull

/**
 * Process-local display metadata captured from the current canonical Thread attachment model.
 *
 * Remote File Preview routes remain identity-only. This candidate may only fill fields that the
 * destination's attachment-id route cannot currently hydrate from the server; it never carries a
 * signed URL, content body, auth token, or route authority.
 */
internal data class SlockFilePreviewAttachmentMetadata(
    val attachmentId: String,
    val name: String = "",
    val mimeType: String = "",
    val sizeLabel: String = "",
)

private data class SlockFilePreviewAttachmentMetadataKey(
    val owner: AuthenticatedRouteOwner,
    val attachmentId: String,
)

internal class SlockFilePreviewAttachmentMetadataStore(
    private val maxEntries: Int = 256,
) {
    private val entries = LinkedHashMap<SlockFilePreviewAttachmentMetadataKey, SlockFilePreviewAttachmentMetadata>()

    init {
        require(maxEntries > 0)
    }

    fun capture(
        owner: AuthenticatedRouteOwner,
        payload: SlockFilePreviewPayload,
    ) {
        val normalizedOwner = owner.normalizedOrNull() ?: return
        payload.threadAttachmentMetadataCandidates().forEach { candidate ->
            val key = SlockFilePreviewAttachmentMetadataKey(normalizedOwner, candidate.attachmentId)
            synchronized(entries) {
                val merged = entries.remove(key)?.merge(candidate) ?: candidate
                entries[key] = merged
                while (entries.size > maxEntries) {
                    val oldest = entries.keys.firstOrNull() ?: break
                    entries.remove(oldest)
                }
            }
        }
    }

    fun hydrate(
        owner: AuthenticatedRouteOwner,
        payload: SlockFilePreviewPayload,
    ): SlockFilePreviewPayload {
        if (payload.source != SlockFilePreviewSourceThreadAttachment) return payload
        val normalizedOwner = owner.normalizedOrNull() ?: return payload
        val attachmentId = payload.attachmentId.trim().takeIf(String::isNotBlank) ?: return payload
        val metadataByAttachmentId = mutableMapOf<String, SlockFilePreviewAttachmentMetadata>()
        synchronized(entries) {
            entries[SlockFilePreviewAttachmentMetadataKey(normalizedOwner, attachmentId)]?.let {
                metadataByAttachmentId[attachmentId] = it
            }
            payload.imageGallery.forEach { image ->
                val imageId = image.attachmentId.trim()
                if (imageId.isNotBlank()) {
                    entries[SlockFilePreviewAttachmentMetadataKey(normalizedOwner, imageId)]?.let {
                        metadataByAttachmentId[imageId] = it
                    }
                }
            }
        }
        val metadata = metadataByAttachmentId[attachmentId] ?: return payload
        return payload.copy(
            subtitle = payload.subtitle.ifBlank { metadata.name },
            name = payload.name.ifBlank { metadata.name },
            path = payload.path.ifBlank { metadata.name },
            meta = payload.meta.ifBlank { metadata.sizeLabel },
            externalFileName = payload.externalFileName.ifBlank { metadata.name },
            externalMimeType = payload.externalMimeType.ifBlank { metadata.mimeType },
            imageGallery =
                payload.imageGallery.map { image ->
                    val candidate = metadataByAttachmentId[image.attachmentId.trim()]
                    if (candidate == null) {
                        image
                    } else {
                        image.copy(
                            name = image.name.ifBlank { candidate.name },
                            mimeType = image.mimeType.ifBlank { candidate.mimeType },
                            sizeLabel = image.sizeLabel.ifBlank { candidate.sizeLabel },
                        )
                    }
                },
        )
    }
}

private fun SlockFilePreviewPayload.threadAttachmentMetadataCandidates(): List<SlockFilePreviewAttachmentMetadata> {
    if (source != SlockFilePreviewSourceThreadAttachment || composerImageActionId.isNotBlank()) return emptyList()
    return buildList {
        attachmentId.trim().takeIf(String::isNotBlank)?.let { id ->
            add(
                SlockFilePreviewAttachmentMetadata(
                    attachmentId = id,
                    name = name.trim(),
                    mimeType = externalMimeType.trim(),
                    sizeLabel = meta.trim(),
                ),
            )
        }
        imageGallery.forEach { image ->
            image.attachmentId.trim().takeIf(String::isNotBlank)?.let { id ->
                add(
                    SlockFilePreviewAttachmentMetadata(
                        attachmentId = id,
                        name = image.name.trim(),
                        mimeType = image.mimeType.trim(),
                        sizeLabel = image.sizeLabel.trim(),
                    ),
                )
            }
        }
    }
}

private fun SlockFilePreviewAttachmentMetadata.merge(
    newer: SlockFilePreviewAttachmentMetadata,
): SlockFilePreviewAttachmentMetadata =
    copy(
        name = newer.name.ifBlank { name },
        mimeType = newer.mimeType.ifBlank { mimeType },
        sizeLabel = newer.sizeLabel.ifBlank { sizeLabel },
    )

private val ProcessSlockFilePreviewAttachmentMetadataStore = SlockFilePreviewAttachmentMetadataStore()

internal fun slockFilePreviewAttachmentMetadataStore(): SlockFilePreviewAttachmentMetadataStore =
    ProcessSlockFilePreviewAttachmentMetadataStore
