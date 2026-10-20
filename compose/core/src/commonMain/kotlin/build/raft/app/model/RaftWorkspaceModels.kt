package build.raft.app.model

enum class RaftWorkspaceFileKind {
    Folder,
    Markdown,
    Text,
    Image,
    Unsupported
}

data class RaftWorkspaceNode(
    val id: String,
    val name: String,
    val path: String,
    val kind: RaftWorkspaceFileKind,
    val meta: String? = null,
    val preview: RaftWorkspaceFilePreview? = null,
    val children: List<RaftWorkspaceNode> = emptyList()
)

sealed interface RaftWorkspaceFilePreview {
    data class Text(
        val path: String,
        val content: String,
        val markdown: Boolean,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : RaftWorkspaceFilePreview

    data class Image(
        val path: String,
        val imageUrl: String? = null,
        val sampleAssetKey: String? = null,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null,
        val description: String? = null
    ) : RaftWorkspaceFilePreview

    data class Loading(
        val detail: String = "Fetching preview content."
    ) : RaftWorkspaceFilePreview

    data class Error(
        val reason: String
    ) : RaftWorkspaceFilePreview

    data class Unsupported(
        val path: String,
        val reason: String,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : RaftWorkspaceFilePreview
}
