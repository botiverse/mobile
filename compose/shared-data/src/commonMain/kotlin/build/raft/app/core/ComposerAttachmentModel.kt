package build.raft.app

import build.raft.app.transfer.RaftTransferProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SharedThreadComposerAttachment(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val previewUri: String? = null,
    val status: String = "ready",
    val errorCode: String? = null,
    val fileRef: String? = null,
    val source: String? = null,
    val externalShareToken: String? = null,
    val externalShareAccountId: String? = null,
    val externalShareServerId: String? = null,
    /**
     * The upload meter's state (owned by the upload attempt in the Thread page): null until the
     * upload starts sending, Done once the server confirmed it. Not persisted: a restored draft
     * re-uploads.
     */
    @Transient val uploadTransfer: RaftTransferProgress? = null
)
