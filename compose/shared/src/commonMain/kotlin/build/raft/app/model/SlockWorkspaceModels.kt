package build.raft.app.model

enum class SlockWorkspaceFileKind {
    Folder,
    Markdown,
    Text,
    Image,
    Unsupported
}

data class SlockWorkspaceNode(
    val id: String,
    val name: String,
    val path: String,
    val kind: SlockWorkspaceFileKind,
    val meta: String? = null,
    val preview: SlockWorkspaceFilePreview? = null,
    val children: List<SlockWorkspaceNode> = emptyList()
)

sealed interface SlockWorkspaceFilePreview {
    data class Text(
        val path: String,
        val content: String,
        val markdown: Boolean,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : SlockWorkspaceFilePreview

    data class Image(
        val path: String,
        val imageUrl: String? = null,
        val sampleAssetKey: String? = null,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null,
        val description: String? = null
    ) : SlockWorkspaceFilePreview

    data class Loading(
        val detail: String = "Fetching preview content."
    ) : SlockWorkspaceFilePreview

    data class Error(
        val reason: String
    ) : SlockWorkspaceFilePreview

    data class Unsupported(
        val path: String,
        val reason: String,
        val sizeBytes: Long? = null,
        val modifiedAt: String? = null,
        val mimeType: String? = null
    ) : SlockWorkspaceFilePreview
}
