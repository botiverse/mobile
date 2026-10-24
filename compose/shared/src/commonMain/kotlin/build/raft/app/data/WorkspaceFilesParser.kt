package build.raft.app.data

import build.raft.app.format.RaftFormat

import build.raft.app.model.MemberId
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceFilePreview
import build.raft.app.model.RaftWorkspaceNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Strict canonical parsers for the agent-workspace file routes.
 *
 * - GET `/api/agents/:id/workspace-files[?dirPath=...]`
 *   -> `{files: FileNode[]}`
 * - GET `/api/agents/:id/workspace-files/read?path=...`
 *   -> `{path, content, binary, size, mimeType?, encoding?, modifiedAt}`
 *
 * The wire DTOs mirror the upstream TypeScript contracts directly. No alias
 * scanning, envelope sniffing, or JSON-tree probing belongs here.
 */
private val workspaceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireWorkspaceFilesResponse(
    val files: List<WireWorkspaceNode> = emptyList(),
)

@Serializable
private data class WireWorkspaceNode(
    val name: String = "",
    val path: String = "",
    val isDirectory: Boolean = false,
    val size: Long? = null,
    val modifiedAt: String? = null,
    val children: List<WireWorkspaceNode> = emptyList(),
)

@Serializable
private data class WireWorkspacePreview(
    val path: String,
    val content: String? = null,
    val binary: Boolean = false,
    val size: Long? = null,
    val mimeType: String? = null,
    val encoding: String? = null,
    val modifiedAt: String? = null,
)

fun parseSharedWorkspaceFileNodes(bodyText: String): List<RaftWorkspaceNode> =
    parseSharedWorkspaceFileNodesOrNull(bodyText).orEmpty()

fun parseSharedWorkspaceFileNodesOrNull(bodyText: String): List<RaftWorkspaceNode>? {
    if (bodyText.isBlank()) return emptyList()
    return runCatching {
        workspaceJson.decodeFromString(WireWorkspaceFilesResponse.serializer(), bodyText)
            .files
            .map { it.toDomain() }
    }.getOrNull()
}

fun parseSharedWorkspaceFilePreview(
    bodyText: String,
    fallbackPath: String,
): RaftWorkspaceFilePreview {
    if (bodyText.isBlank()) {
        return RaftWorkspaceFilePreview.Unsupported(
            path = fallbackPath,
            reason = "Preview unavailable",
        )
    }
    val preview = runCatching {
        workspaceJson.decodeFromString(WireWorkspacePreview.serializer(), bodyText)
    }.getOrElse {
        return RaftWorkspaceFilePreview.Unsupported(
            path = fallbackPath,
            reason = "Preview unavailable",
        )
    }
    return preview.toDomain(fallbackPath)
}

fun parseSharedAgentWorkspaceFilePreviewSnapshot(
    bodyText: String,
    agentId: MemberId,
    fallbackPath: String,
): AgentWorkspaceFilePreviewSnapshot =
    AgentWorkspaceFilePreviewSnapshot(
        agentId = agentId,
        path = parseSharedWorkspaceFilePathFromBody(bodyText, fallbackPath),
        preview = parseSharedWorkspaceFilePreview(bodyText, fallbackPath),
    )

fun parseSharedWorkspaceFilePathFromBody(bodyText: String, fallback: String): String {
    if (bodyText.isBlank()) return fallback
    return runCatching {
        workspaceJson.decodeFromString(WireWorkspacePreview.serializer(), bodyText).path
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

private fun WireWorkspaceNode.toDomain(): RaftWorkspaceNode {
    val resolvedPath = path
    val resolvedName = name.ifBlank {
        resolvedPath.substringAfterLast('/').ifBlank { resolvedPath }
    }
    return RaftWorkspaceNode(
        id = resolvedPath,
        name = resolvedName,
        path = resolvedPath,
        kind = resolveWorkspaceKind(isDirectory, resolvedPath),
        meta = formatNodeMeta(size, modifiedAt),
        children = children.map { it.toDomain() },
    )
}

private fun WireWorkspacePreview.toDomain(fallbackPath: String): RaftWorkspaceFilePreview {
    val resolvedPath = path.ifBlank { fallbackPath }
    if (!binary && content != null) {
        return RaftWorkspaceFilePreview.Text(
            path = resolvedPath,
            content = content,
            markdown = mimeType?.contains("markdown", ignoreCase = true)
                ?: resolvedPath.endsWith(".md", ignoreCase = true),
            sizeBytes = size,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
        )
    }
    if (binary && content != null && encoding == "base64" && mimeType?.startsWith("image/") == true) {
        return RaftWorkspaceFilePreview.Image(
            path = resolvedPath,
            imageUrl = "data:$mimeType;base64,$content",
            sizeBytes = size,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
        )
    }
    if (resolvedPath.isImagePath()) {
        return RaftWorkspaceFilePreview.Unsupported(
            path = resolvedPath,
            reason = "Binary image preview is not available in the current mobile contract.",
            sizeBytes = size,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
        )
    }
    return RaftWorkspaceFilePreview.Unsupported(
        path = resolvedPath,
        reason = "Preview unavailable",
        sizeBytes = size,
        modifiedAt = modifiedAt,
        mimeType = mimeType,
    )
}

private fun resolveWorkspaceKind(isDirectory: Boolean, path: String): RaftWorkspaceFileKind =
    when {
        isDirectory -> RaftWorkspaceFileKind.Folder
        path.endsWith(".md", ignoreCase = true) -> RaftWorkspaceFileKind.Markdown
        path.isImagePath() -> RaftWorkspaceFileKind.Image
        path.hasTextExtension() -> RaftWorkspaceFileKind.Text
        else -> RaftWorkspaceFileKind.Unsupported
    }

private fun String.isImagePath(): Boolean =
    endsWith(".png", ignoreCase = true) ||
        endsWith(".jpg", ignoreCase = true) ||
        endsWith(".jpeg", ignoreCase = true) ||
        endsWith(".gif", ignoreCase = true) ||
        endsWith(".webp", ignoreCase = true) ||
        endsWith(".heic", ignoreCase = true)

private fun String.hasTextExtension(): Boolean =
    endsWith(".txt", ignoreCase = true) ||
        endsWith(".text", ignoreCase = true) ||
        endsWith(".log", ignoreCase = true) ||
        endsWith(".html", ignoreCase = true) ||
        endsWith(".htm", ignoreCase = true) ||
        endsWith(".json", ignoreCase = true) ||
        endsWith(".kt", ignoreCase = true) ||
        endsWith(".kts", ignoreCase = true) ||
        endsWith(".ts", ignoreCase = true) ||
        endsWith(".tsx", ignoreCase = true) ||
        endsWith(".js", ignoreCase = true) ||
        endsWith(".jsx", ignoreCase = true) ||
        endsWith(".py", ignoreCase = true) ||
        endsWith(".sh", ignoreCase = true) ||
        endsWith(".bash", ignoreCase = true) ||
        endsWith(".zsh", ignoreCase = true) ||
        endsWith(".css", ignoreCase = true) ||
        endsWith(".xml", ignoreCase = true) ||
        endsWith(".sql", ignoreCase = true) ||
        endsWith(".toml", ignoreCase = true) ||
        endsWith(".ini", ignoreCase = true) ||
        endsWith(".conf", ignoreCase = true) ||
        endsWith(".properties", ignoreCase = true) ||
        endsWith(".yml", ignoreCase = true) ||
        endsWith(".yaml", ignoreCase = true)

private fun formatNodeMeta(sizeBytes: Long?, modifiedAt: String?): String? {
    if (sizeBytes != null && sizeBytes > 0) {
        return RaftFormat.bytes(sizeBytes)
    }
    return modifiedAt
}
