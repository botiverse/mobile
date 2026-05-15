package ai.slock.android.ui.components

import ai.slock.android.ui.icons.LucideIcons

import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

sealed interface SlockWorkspaceTreeState {
    data object Loading : SlockWorkspaceTreeState
    data object Empty : SlockWorkspaceTreeState
    data object NoPermission : SlockWorkspaceTreeState
    data class Error(val code: String) : SlockWorkspaceTreeState
    data class Content(
        val rootPath: String,
        val root: SlockWorkspaceNode,
        val expandedNodeIds: Set<String>,
        val selectedFileId: String? = null,
        val showPreview: Boolean = false
    ) : SlockWorkspaceTreeState
}

@Composable
fun SlockWorkspaceFileTree(
    state: SlockWorkspaceTreeState,
    modifier: Modifier = Modifier,
    onToggleFolder: (SlockWorkspaceNode) -> Unit = {},
    onSelectFile: (SlockWorkspaceNode) -> Unit = {},
    onBackToTree: () -> Unit = {},
    onCopyPath: () -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (state) {
            SlockWorkspaceTreeState.Loading ->
                SlockWorkspaceStateBlock("Loading workspace", "Reading live workspace files.")
            SlockWorkspaceTreeState.Empty ->
                SlockWorkspaceStateBlock("No workspace files", "This workspace has no visible files.")
            SlockWorkspaceTreeState.NoPermission ->
                SlockWorkspaceStateBlock("No permission", "You do not have access to this workspace.")
            is SlockWorkspaceTreeState.Error ->
                SlockWorkspaceStateBlock("Workspace failed to load", "Error code: ${state.code}")
            is SlockWorkspaceTreeState.Content -> {
                SlockWorkspacePathBar(
                    path = state.rootPath,
                    onCopyPath = onCopyPath,
                    onRefresh = onRefresh
                )
                if (state.showPreview) {
                    SlockWorkspacePreview(
                        file = state.selectedFileId?.let(state.root::findNode),
                        onBack = onBackToTree
                    )
                } else {
                    SlockWorkspaceTreeList(
                        root = state.root,
                        expandedNodeIds = state.expandedNodeIds,
                        selectedFileId = state.selectedFileId,
                        onToggleFolder = onToggleFolder,
                        onSelectFile = onSelectFile
                    )
                }
            }
        }
    }
}

@Composable
private fun SlockWorkspacePathBar(
    path: String,
    onCopyPath: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = path,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SlockWorkspaceMiniAction(LucideIcons.Copy, "Copy path", onCopyPath)
        SlockWorkspaceMiniAction(LucideIcons.RefreshCw, "Refresh", onRefresh)
    }
}

@Composable
private fun SlockWorkspaceMiniAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val shadow = SlockShadow.Small
    Box(
        modifier = Modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .slockHardShadow(shadow)
            .size(30.dp)
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = SlockColor.TextBlack, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun SlockWorkspaceTreeList(
    root: SlockWorkspaceNode,
    expandedNodeIds: Set<String>,
    selectedFileId: String?,
    onToggleFolder: (SlockWorkspaceNode) -> Unit,
    onSelectFile: (SlockWorkspaceNode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "WORKSPACE",
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${root.countFiles()} files",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextMutedGray
            )
        }
        root.flattenVisible(expandedNodeIds).forEach { item ->
            SlockWorkspaceNodeRow(
                item = item,
                expanded = expandedNodeIds.contains(item.node.id),
                selected = selectedFileId == item.node.id,
                onClick = {
                    if (item.node.kind == SlockWorkspaceFileKind.Folder) onToggleFolder(item.node)
                    else onSelectFile(item.node)
                }
            )
        }
    }
}

@Composable
private fun SlockWorkspaceNodeRow(
    item: VisibleWorkspaceNode,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val node = item.node
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(if (selected) SlockColor.BrandYellow else Color.Transparent)
            .then(if (selected) Modifier.border(SlockStroke.Default, SlockColor.BorderBlack) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                start = 8.dp + (item.depth * 16).dp,
                end = SlockSpacing.Sm,
                top = SlockSpacing.Xs,
                bottom = SlockSpacing.Xs
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        if (node.kind == SlockWorkspaceFileKind.Folder) {
            Icon(
                imageVector = if (expanded) LucideIcons.ChevronDown else LucideIcons.ChevronRight,
                contentDescription = null,
                tint = SlockColor.TextBlack,
                modifier = Modifier.size(14.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(14.dp))
        }
        Icon(
            imageVector = node.kind.icon,
            contentDescription = null,
            tint = if (node.kind == SlockWorkspaceFileKind.Folder) SlockColor.AccentOrange else SlockColor.TextMutedGray,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = node.name,
            style = SlockType.Caption.copy(
                fontWeight = if (selected || node.kind == SlockWorkspaceFileKind.Folder) FontWeight.Bold else FontWeight.Medium
            ),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        node.meta?.let { meta ->
            Text(
                text = meta,
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SlockWorkspacePreview(
    file: SlockWorkspaceNode?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockWorkspacePreviewHeader(file = file, onBack = onBack)
        when (val preview = file?.preview) {
            is SlockWorkspaceFilePreview.Text -> SlockWorkspaceTextPreview(preview)
            is SlockWorkspaceFilePreview.Image -> SlockWorkspaceImagePreview(file = file, preview = preview)
            is SlockWorkspaceFilePreview.Loading ->
                SlockWorkspacePreviewStatus(LucideIcons.RefreshCw, "Loading preview", preview.detail)
            is SlockWorkspaceFilePreview.Error ->
                SlockWorkspacePreviewStatus(LucideIcons.X, "Preview failed", preview.reason)
            is SlockWorkspaceFilePreview.Unsupported ->
                SlockWorkspaceUnsupportedPreview(file = file, preview = preview)
            null ->
                SlockWorkspaceStateBlock("Select a file", "Text previews appear here after choosing a file.")
        }
    }
}

@Composable
private fun SlockWorkspacePreviewHeader(
    file: SlockWorkspaceNode?,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockWorkspaceMiniAction(LucideIcons.ArrowLeft, "Back to file tree", onBack)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = file?.name ?: "No file selected",
                style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = file?.path ?: "Select a file to view",
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (file != null) {
                Text(
                    text = listOf(file.kind.typeLabel, file.meta ?: file.preview.previewMeta).filterNotNull().joinToString(" · "),
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SlockWorkspaceTextPreview(preview: SlockWorkspaceFilePreview.Text) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Md)
    ) {
        if (preview.markdown) {
            Markdown(content = preview.content)
        } else {
            Text(
                text = preview.content,
                style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
                color = SlockColor.TextBlack
            )
        }
    }
}

@Composable
private fun SlockWorkspaceImagePreview(
    file: SlockWorkspaceNode,
    preview: SlockWorkspaceFilePreview.Image
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockWorkspaceFileMeta(file.name, preview.mimeType ?: "image")
        Text(
            text = preview.imageUrl ?: "Image preview is available, but no direct image URL was returned.",
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun SlockWorkspaceUnsupportedPreview(
    file: SlockWorkspaceNode,
    preview: SlockWorkspaceFilePreview.Unsupported
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockWorkspaceFileMeta(file.name, file.meta ?: "Unknown type")
        Text(
            text = "Preview unavailable",
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = preview.reason,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun SlockWorkspacePreviewStatus(
    icon: ImageVector? = null,
    title: String,
    detail: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = SlockColor.TextBlack, modifier = Modifier.size(28.dp))
        }
        Text(text = title, style = SlockType.Body.copy(fontWeight = FontWeight.Bold), color = SlockColor.TextBlack)
        Text(text = detail, style = SlockType.Caption, color = SlockColor.TextMutedGray)
    }
}

@Composable
private fun SlockWorkspaceFileMeta(
    name: String,
    detail: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = name,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SlockWorkspaceStateBlock(
    title: String,
    detail: String
) {
    SlockEmptyState(title = title, detail = detail)
}

private data class VisibleWorkspaceNode(
    val node: SlockWorkspaceNode,
    val depth: Int
)

private fun SlockWorkspaceNode.flattenVisible(
    expandedNodeIds: Set<String>,
    depth: Int = 0
): List<VisibleWorkspaceNode> {
    val self = VisibleWorkspaceNode(this, depth)
    if (kind != SlockWorkspaceFileKind.Folder || !expandedNodeIds.contains(id)) return listOf(self)
    return listOf(self) + children.flatMap { it.flattenVisible(expandedNodeIds, depth + 1) }
}

private fun SlockWorkspaceNode.findNode(id: String): SlockWorkspaceNode? {
    if (this.id == id) return this
    return children.firstNotNullOfOrNull { it.findNode(id) }
}

private fun SlockWorkspaceNode.countFiles(): Int =
    if (kind != SlockWorkspaceFileKind.Folder) 1 else children.sumOf { it.countFiles() }

private val SlockWorkspaceFileKind.icon: ImageVector
    get() = when (this) {
        SlockWorkspaceFileKind.Folder -> LucideIcons.FolderOpen
        SlockWorkspaceFileKind.Text, SlockWorkspaceFileKind.Markdown -> LucideIcons.FileText
        SlockWorkspaceFileKind.Image -> LucideIcons.Image
        SlockWorkspaceFileKind.Unsupported -> LucideIcons.Square
    }

private val SlockWorkspaceFileKind.typeLabel: String
    get() = when (this) {
        SlockWorkspaceFileKind.Folder -> "Folder"
        SlockWorkspaceFileKind.Text -> "Text"
        SlockWorkspaceFileKind.Markdown -> "Markdown"
        SlockWorkspaceFileKind.Image -> "Image"
        SlockWorkspaceFileKind.Unsupported -> "Unsupported"
    }

private val SlockWorkspaceFilePreview?.previewMeta: String?
    get() = when (this) {
        is SlockWorkspaceFilePreview.Text -> mimeType ?: if (markdown) "text/markdown" else "text/plain"
        is SlockWorkspaceFilePreview.Image -> mimeType ?: "image"
        is SlockWorkspaceFilePreview.Loading -> "Loading"
        is SlockWorkspaceFilePreview.Error -> "Failed"
        is SlockWorkspaceFilePreview.Unsupported -> "Unsupported"
        null -> null
    }

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspaceFileTreePreview() {
    val root = SlockWorkspaceNode(
        id = "/",
        name = "workspace",
        path = "/",
        kind = SlockWorkspaceFileKind.Folder,
        children = listOf(
            SlockWorkspaceNode(
                id = "/README.md",
                name = "README.md",
                path = "/README.md",
                kind = SlockWorkspaceFileKind.Markdown,
                meta = "1.2 KB",
                preview = SlockWorkspaceFilePreview.Text(
                    path = "/README.md",
                    content = "# Workspace\n\nShared workspace tree preview.",
                    markdown = true,
                    mimeType = "text/markdown"
                )
            )
        )
    )
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceTreeState.Content(
                rootPath = "/workspace",
                root = root,
                expandedNodeIds = setOf("/"),
                selectedFileId = "/README.md",
                showPreview = true
            ),
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}
