package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockWorkspaceFileKind {
    Folder,
    Markdown,
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
    data class Markdown(
        val title: String,
        val paragraphs: List<String>,
        val bullets: List<String>,
        val code: String
    ) : SlockWorkspaceFilePreview

    data class Image(
        val dimensions: String,
        val size: String,
        val mimeType: String = "image/png",
        val description: String = "Sample image preview",
        val accentColor: Color = SlockColor.AccentCyan
    ) : SlockWorkspaceFilePreview

    data class Loading(
        val detail: String = "Fetching preview content."
    ) : SlockWorkspaceFilePreview

    data class Error(
        val reason: String
    ) : SlockWorkspaceFilePreview

    data class Unsupported(
        val reason: String
    ) : SlockWorkspaceFilePreview
}

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
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when (state) {
            SlockWorkspaceTreeState.Loading -> SlockWorkspaceStateBlock(
                title = "Loading workspace",
                detail = "Reading sample file tree."
            )
            SlockWorkspaceTreeState.Empty -> SlockWorkspaceStateBlock(
                title = "No workspace files",
                detail = "This workspace has no visible files."
            )
            SlockWorkspaceTreeState.NoPermission -> SlockWorkspaceStateBlock(
                title = "No permission",
                detail = "You do not have access to this workspace."
            )
            is SlockWorkspaceTreeState.Error -> SlockWorkspaceStateBlock(
                title = "Workspace failed to load",
                detail = "Error code: ${state.code}"
            )
            is SlockWorkspaceTreeState.Content -> {
                SlockWorkspacePathBar(
                    path = state.rootPath,
                    onCopyPath = onCopyPath,
                    onRefresh = onRefresh
                )
                if (state.showPreview) {
                    val selectedFile = state.selectedFileId?.let { state.root.findNode(it) }
                    SlockWorkspacePreview(
                        file = selectedFile,
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
        SlockWorkspaceMiniAction(icon = SlockIcons.Copy, label = "Copy path", onClick = onCopyPath)
        SlockWorkspaceMiniAction(icon = SlockIcons.RefreshCw, label = "Refresh", onClick = onRefresh)
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
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(14.dp)
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Xs),
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
                    if (item.node.kind == SlockWorkspaceFileKind.Folder) {
                        onToggleFolder(item.node)
                    } else {
                        onSelectFile(item.node)
                    }
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
            .then(
                if (selected) {
                    Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
                } else {
                    Modifier
                }
            )
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
                imageVector = if (expanded) SlockIcons.ChevronDown else SlockIcons.ChevronRight,
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
        if (node.meta != null) {
            Text(
                text = node.meta,
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
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        SlockWorkspacePreviewHeader(file = file, onBack = onBack)
        when (val preview = file?.preview) {
            is SlockWorkspaceFilePreview.Markdown -> SlockWorkspaceMarkdownPreview(preview)
            is SlockWorkspaceFilePreview.Image -> SlockWorkspaceImagePreview(file = file, preview = preview)
            is SlockWorkspaceFilePreview.Loading -> SlockWorkspacePreviewStatus(
                icon = SlockIcons.RefreshCw,
                title = "Loading preview",
                detail = preview.detail
            )
            is SlockWorkspaceFilePreview.Error -> SlockWorkspacePreviewStatus(
                icon = SlockIcons.X,
                title = "Preview failed",
                detail = preview.reason
            )
            is SlockWorkspaceFilePreview.Unsupported -> SlockWorkspaceUnsupportedPreview(file = file, preview = preview)
            null -> SlockWorkspaceStateBlock(
                title = "Select a file",
                detail = "Markdown, image, loading, failed, and unsupported sample previews appear here."
            )
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
        SlockWorkspaceMiniAction(icon = SlockIcons.ArrowLeft, label = "Back to file tree", onClick = onBack)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
private fun SlockWorkspaceMarkdownPreview(preview: SlockWorkspaceFilePreview.Markdown) {
    val content = preview.toMarkdownSource()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
            .padding(SlockSpacing.Md)
    ) {
        Markdown(content = content)
    }
}

@Composable
private fun SlockWorkspaceImagePreview(
    file: SlockWorkspaceNode,
    preview: SlockWorkspaceFilePreview.Image
) {
    Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(SlockColor.AppBgLightBlue)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockWorkspaceSampleImage(accentColor = preview.accentColor)
            Text(
                text = preview.description,
                style = SlockType.SectionLabel,
                color = SlockColor.TextBlack,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Thin, SlockColor.TextBlack.copy(alpha = 0.30f))
                    .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs)
            )
        }
        SlockWorkspaceFileMeta(file.name, "${preview.mimeType} · ${preview.dimensions} · ${preview.size}")
    }
}

@Composable
private fun SlockWorkspaceSampleImage(accentColor: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = accentColor.copy(alpha = 0.22f), size = size)
        drawCircle(
            color = SlockColor.BrandYellow,
            radius = size.minDimension * 0.10f,
            center = Offset(size.width * 0.78f, size.height * 0.20f)
        )
        drawRect(
            color = accentColor,
            topLeft = Offset(size.width * 0.06f, size.height * 0.12f),
            size = Size(size.width * 0.30f, size.height * 0.22f)
        )
        drawRect(
            color = SlockColor.SurfaceWhite.copy(alpha = 0.88f),
            topLeft = Offset(size.width * 0.10f, size.height * 0.18f),
            size = Size(size.width * 0.22f, size.height * 0.04f)
        )
        drawRect(
            color = SlockColor.TextBlack.copy(alpha = 0.10f),
            topLeft = Offset(size.width * 0.10f, size.height * 0.26f),
            size = Size(size.width * 0.18f, size.height * 0.03f)
        )
        drawRect(
            color = SlockColor.AccentCyan,
            topLeft = Offset(size.width * 0.0f, size.height * 0.64f),
            size = Size(size.width, size.height * 0.36f)
        )
        drawRect(
            color = SlockColor.TextBlack.copy(alpha = 0.12f),
            topLeft = Offset(size.width * 0.52f, size.height * 0.38f),
            size = Size(size.width * 0.36f, size.height * 0.18f)
        )
        drawRect(
            color = SlockColor.SurfaceWhite.copy(alpha = 0.82f),
            topLeft = Offset(size.width * 0.56f, size.height * 0.43f),
            size = Size(size.width * 0.26f, size.height * 0.035f)
        )
        drawRect(
            color = SlockColor.SurfaceWhite.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.56f, size.height * 0.50f),
            size = Size(size.width * 0.20f, size.height * 0.03f)
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
    icon: ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = detail,
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
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

private fun SlockWorkspaceNode.flattenVisible(expandedNodeIds: Set<String>, depth: Int = 0): List<VisibleWorkspaceNode> {
    val self = VisibleWorkspaceNode(this, depth)
    if (kind != SlockWorkspaceFileKind.Folder || !expandedNodeIds.contains(id)) {
        return listOf(self)
    }
    return listOf(self) + children.flatMap { it.flattenVisible(expandedNodeIds, depth + 1) }
}

private fun SlockWorkspaceNode.findNode(id: String): SlockWorkspaceNode? {
    if (this.id == id) return this
    return children.firstNotNullOfOrNull { it.findNode(id) }
}

private fun SlockWorkspaceNode.countFiles(): Int {
    if (kind != SlockWorkspaceFileKind.Folder) return 1
    return children.sumOf { it.countFiles() }
}

private val SlockWorkspaceFileKind.icon: ImageVector
    get() = when (this) {
        SlockWorkspaceFileKind.Folder -> SlockIcons.FolderOpen
        SlockWorkspaceFileKind.Markdown -> SlockIcons.FileText
        SlockWorkspaceFileKind.Image -> SlockIcons.Image
        SlockWorkspaceFileKind.Unsupported -> SlockIcons.Square
    }

private val SlockWorkspaceFileKind.typeLabel: String
    get() = when (this) {
        SlockWorkspaceFileKind.Folder -> "Folder"
        SlockWorkspaceFileKind.Markdown -> "Markdown"
        SlockWorkspaceFileKind.Image -> "Image"
        SlockWorkspaceFileKind.Unsupported -> "Unsupported"
    }

private val SlockWorkspaceFilePreview?.previewMeta: String?
    get() = when (this) {
        is SlockWorkspaceFilePreview.Image -> "$mimeType · $dimensions · $size"
        is SlockWorkspaceFilePreview.Markdown -> "text/markdown"
        is SlockWorkspaceFilePreview.Loading -> "Loading"
        is SlockWorkspaceFilePreview.Error -> "Failed"
        is SlockWorkspaceFilePreview.Unsupported -> "Unsupported"
        null -> null
    }

private fun SlockWorkspaceFilePreview.Markdown.toMarkdownSource(): String = buildString {
    append("# ").append(title).append("\n\n")
    paragraphs.forEach { paragraph ->
        append(paragraph).append("\n\n")
    }
    bullets.forEach { bullet ->
        append("- ").append(bullet).append("\n")
    }
    if (bullets.isNotEmpty()) {
        append("\n")
    }
    append("```kotlin\n").append(code).append("\n```")
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspaceFileTreePreview() {
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceSamples.treeState,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspacePreviewPanePreview() {
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceSamples.markdownPreviewState,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspaceImagePreviewPanePreview() {
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceSamples.imagePreviewState,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspaceLoadingPreviewPanePreview() {
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceSamples.loadingPreviewState,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockWorkspaceFailedPreviewPanePreview() {
    SlockTheme {
        SlockWorkspaceFileTree(
            state = SlockWorkspaceSamples.failedPreviewState,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

object SlockWorkspaceSamples {
    val root = SlockWorkspaceNode(
        id = "root",
        name = "~/.slock/agents/android-dev",
        path = "~/.slock/agents/android-dev",
        kind = SlockWorkspaceFileKind.Folder,
        children = listOf(
            SlockWorkspaceNode(
                id = "notes",
                name = "notes",
                path = "~/.slock/agents/android-dev/notes",
                kind = SlockWorkspaceFileKind.Folder,
                children = listOf(
                    SlockWorkspaceNode(
                        id = "readme",
                        name = "workspace.md",
                        path = "~/.slock/agents/android-dev/notes/workspace.md",
                        kind = SlockWorkspaceFileKind.Markdown,
                        meta = "3.4 KB",
                        preview = SlockWorkspaceFilePreview.Markdown(
                            title = "Workspace notes",
                            paragraphs = listOf(
                                "This sample Markdown file documents the current Slock Android workspace.",
                                "The preview renderer supports core text structure before real file APIs are connected."
                            ),
                            bullets = listOf("Folders can expand and collapse", "Selected files stay highlighted", "Code blocks use monospace styling"),
                            code = "fun openWorkspace(path: String) = sampleState(path)"
                        )
                    ),
                    SlockWorkspaceNode(
                        id = "channels",
                        name = "channels.md",
                        path = "~/.slock/agents/android-dev/notes/channels.md",
                        kind = SlockWorkspaceFileKind.Markdown,
                        meta = "2.1 KB",
                        preview = SlockWorkspaceFilePreview.Markdown(
                            title = "Channel notes",
                            paragraphs = listOf("Channel context and task routing decisions live here."),
                            bullets = listOf("#android-dev implementation", "#product design feedback"),
                            code = "target = \"#android-dev\""
                        )
                    )
                )
            ),
            SlockWorkspaceNode(
                id = "assets",
                name = "assets",
                path = "~/.slock/agents/android-dev/assets",
                kind = SlockWorkspaceFileKind.Folder,
                children = listOf(
                    SlockWorkspaceNode(
                        id = "image",
                        name = "members-detail.png",
                        path = "~/.slock/agents/android-dev/assets/members-detail.png",
                        kind = SlockWorkspaceFileKind.Image,
                        meta = "PNG · 1440 x 900 · 248 KB",
                        preview = SlockWorkspaceFilePreview.Image(
                            dimensions = "1440 x 900",
                            size = "248 KB",
                            description = "members detail sample",
                            accentColor = SlockColor.AccentLavender
                        )
                    ),
                    SlockWorkspaceNode(
                        id = "image-loading",
                        name = "profile-banner.png",
                        path = "~/.slock/agents/android-dev/assets/profile-banner.png",
                        kind = SlockWorkspaceFileKind.Image,
                        meta = "PNG · 2048 x 720 · 380 KB",
                        preview = SlockWorkspaceFilePreview.Loading("Loading image preview from repository sample state.")
                    ),
                    SlockWorkspaceNode(
                        id = "image-failed",
                        name = "broken-preview.jpg",
                        path = "~/.slock/agents/android-dev/assets/broken-preview.jpg",
                        kind = SlockWorkspaceFileKind.Image,
                        meta = "JPEG · 1600 x 900 · 510 KB",
                        preview = SlockWorkspaceFilePreview.Error("Preview bytes are unavailable in the current sample contract.")
                    ),
                    SlockWorkspaceNode(
                        id = "archive",
                        name = "raw-export.zip",
                        path = "~/.slock/agents/android-dev/assets/raw-export.zip",
                        kind = SlockWorkspaceFileKind.Unsupported,
                        meta = "ZIP · 4.8 MB",
                        preview = SlockWorkspaceFilePreview.Unsupported("ZIP archives are not previewable in the mobile sample shell.")
                    )
                )
            )
        )
    )

    val expandedIds = setOf("root", "notes", "assets")

    val treeState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "readme"
    )

    val markdownPreviewState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "readme",
        showPreview = true
    )

    val imagePreviewState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "image",
        showPreview = true
    )

    val unsupportedPreviewState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "archive",
        showPreview = true
    )

    val loadingPreviewState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "image-loading",
        showPreview = true
    )

    val failedPreviewState = SlockWorkspaceTreeState.Content(
        rootPath = root.path,
        root = root,
        expandedNodeIds = expandedIds,
        selectedFileId = "image-failed",
        showPreview = true
    )
}
