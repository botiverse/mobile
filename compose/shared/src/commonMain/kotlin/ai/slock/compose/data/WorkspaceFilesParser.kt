package ai.slock.compose.data

import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Strict canonical parsers for the agent-workspace file routes.
 *
 *  - GET `/api/agents/:id/workspace/files[?path=...]`
 *      → `{files: WireWorkspaceNode[]}`
 *
 *  - GET `/api/agents/:id/workspace/file?path=...`
 *      → `{path, content?, mimeType?, sizeBytes?, modifiedAt?, markdown?,
 *         imageUrl?, sampleAssetKey?, description?, reason?}`
 *
 *  WireWorkspaceNode ::= `{id?, name, path, kind?, mimeType?, sizeBytes?,
 *                          children?: WireWorkspaceNode[], preview?: WireWorkspacePreview}`
 *
 *  All field names are camelCase; no alias scanning.
 */

private val workspaceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireWorkspaceNode(
    val id: String? = null,
    val name: String = "",
    val path: String = "",
    val kind: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val children: List<WireWorkspaceNode> = emptyList(),
    val preview: WireWorkspacePreview? = null,
)

@Serializable
private data class WireWorkspacePreview(
    val path: String? = null,
    val content: String? = null,
    val markdown: Boolean? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAt: String? = null,
    val imageUrl: String? = null,
    val sampleAssetKey: String? = null,
    val description: String? = null,
    val reason: String? = null,
)

/**
 * Parses a `{files: [...]}` response into a flat list of [SlockWorkspaceNode]s.
 */
fun parseSharedWorkspaceFileNodes(bodyText: String): List<SlockWorkspaceNode> {
    if (bodyText.isBlank()) return emptyList()
    val root = runCatching {
        workspaceJson.parseToJsonElement(bodyText) as? JsonObject
    }.getOrNull() ?: return emptyList()
    val filesElement = root["files"] ?: return emptyList()
    return runCatching {
        workspaceJson
            .decodeFromJsonElement(
                ListSerializer(WireWorkspaceNode.serializer()),
                filesElement,
            )
            .map { it.toDomain() }
    }.getOrElse { emptyList() }
}

/**
 * Parses a single-file preview response, falling back to [fallbackPath] when the
 * server omits `path`.
 */
fun parseSharedWorkspaceFilePreview(
    bodyText: String,
    fallbackPath: String,
): SlockWorkspaceFilePreview {
    if (bodyText.isBlank()) {
        return SlockWorkspaceFilePreview.Unsupported(
            path = fallbackPath,
            reason = "Preview unavailable",
        )
    }
    val preview = runCatching {
        workspaceJson.decodeFromString(WireWorkspacePreview.serializer(), bodyText)
    }.getOrElse {
        return SlockWorkspaceFilePreview.Unsupported(
            path = fallbackPath,
            reason = "Preview unavailable",
        )
    }
    return preview.toDomain(fallbackPath)
}

internal fun parseSharedWorkspaceFilePathFromBody(bodyText: String, fallback: String): String {
    if (bodyText.isBlank()) return fallback
    return runCatching {
        (workspaceJson.parseToJsonElement(bodyText) as? JsonObject)
            ?.get("path")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
}

private fun WireWorkspaceNode.toDomain(): SlockWorkspaceNode {
    val resolvedPath = path.ifBlank { id.orEmpty() }
    val resolvedName = name.ifBlank {
        resolvedPath.substringAfterLast('/').ifBlank { resolvedPath }
    }
    return SlockWorkspaceNode(
        id = id ?: resolvedPath,
        name = resolvedName,
        path = resolvedPath,
        kind = resolveWorkspaceKind(kind, resolvedPath),
        meta = formatNodeMeta(sizeBytes, mimeType),
        preview = preview?.toDomain(resolvedPath),
        children = children.map { it.toDomain() },
    )
}

private fun WireWorkspacePreview.toDomain(fallbackPath: String): SlockWorkspaceFilePreview {
    val resolvedPath = path?.ifBlank { null } ?: fallbackPath
    if (content != null) {
        val isMarkdown = markdown
            ?: mimeType?.contains("markdown", ignoreCase = true)
            ?: resolvedPath.endsWith(".md", ignoreCase = true)
        return SlockWorkspaceFilePreview.Text(
            path = resolvedPath,
            content = content,
            markdown = isMarkdown,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
        )
    }
    if (imageUrl != null) {
        return SlockWorkspaceFilePreview.Image(
            path = resolvedPath,
            imageUrl = imageUrl,
            sampleAssetKey = sampleAssetKey,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
            description = description,
        )
    }
    if (resolvedPath.isImagePath()) {
        return SlockWorkspaceFilePreview.Unsupported(
            path = resolvedPath,
            reason = "Binary image preview is not available in the current mobile contract.",
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            mimeType = mimeType,
        )
    }
    return SlockWorkspaceFilePreview.Unsupported(
        path = resolvedPath,
        reason = reason ?: "Preview unavailable",
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        mimeType = mimeType,
    )
}

private fun resolveWorkspaceKind(rawKind: String?, path: String): SlockWorkspaceFileKind {
    val normalized = rawKind?.lowercase()
    return when {
        normalized in folderKinds -> SlockWorkspaceFileKind.Folder
        normalized in markdownKinds || path.endsWith(".md", ignoreCase = true) ->
            SlockWorkspaceFileKind.Markdown
        normalized in imageKinds || path.isImagePath() -> SlockWorkspaceFileKind.Image
        normalized in textKinds -> SlockWorkspaceFileKind.Text
        else -> SlockWorkspaceFileKind.Unsupported
    }
}

private val folderKinds = setOf("dir", "directory", "folder")
private val markdownKinds = setOf("markdown", "md")
private val imageKinds = setOf("image", "photo")
private val textKinds = setOf("file", "text")

private fun String.isImagePath(): Boolean =
    endsWith(".png", ignoreCase = true) ||
        endsWith(".jpg", ignoreCase = true) ||
        endsWith(".jpeg", ignoreCase = true) ||
        endsWith(".gif", ignoreCase = true) ||
        endsWith(".webp", ignoreCase = true) ||
        endsWith(".heic", ignoreCase = true)

private fun formatNodeMeta(sizeBytes: Long?, mimeType: String?): String? {
    if (sizeBytes != null && sizeBytes > 0) {
        return formatAttachmentSizeLabel(sizeBytes)
    }
    return mimeType
}
