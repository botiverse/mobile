package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.model.SlockWorkspaceFilePreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

internal const val SLOCK_FILE_PREVIEW_PAGE = "compose_file_preview"
private const val SlockFilePreviewOpenWithActionId = "open-with"

private val SlockFilePreviewJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class SlockFilePreviewPayload(
    val kind: String = "unsupported",
    val title: String = "File preview",
    val subtitle: String = "",
    val name: String = "",
    val path: String = "",
    val meta: String = "",
    val content: String = "",
    val markdown: Boolean = false,
    val imageUrl: String? = null,
    val message: String = "",
    val error: String? = null,
    val externalUrl: String? = null,
    val externalFileName: String = "",
    val externalMimeType: String = ""
)

@Page(SLOCK_FILE_PREVIEW_PAGE, supportInLocal = true)
internal class ComposeSlockFilePreviewPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockFilePreviewStandalonePageContent() }
    }
}

@Composable
private fun SlockFilePreviewStandalonePageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val coroutineScope = rememberCoroutineScope()
    val payload = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optString("payload")
            .takeIf { it.isNotBlank() }
            ?.let { source ->
                runCatching { SlockFilePreviewJson.decodeFromString(SlockFilePreviewPayload.serializer(), source) }
                    .getOrNull()
            } ?: SlockFilePreviewPayload(
            title = "Preview unavailable",
            subtitle = "File preview",
            message = "Preview payload could not be loaded.",
            error = "payload_parse_failed"
        )
    }
    SlockFilePreviewPageScaffold(
        title = payload.title,
        subtitle = payload.subtitle.ifBlank { payload.name.ifBlank { payload.path } },
        iconName = payload.previewIconName(),
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        trailingContent =
            payload.externalUrl?.takeIf { it.isNotBlank() }?.let { externalUrl ->
                {
                    SlockFilePreviewExternalMenuButton(
                        onOpenWithSystem = {
                            coroutineScope.launch {
                                val result =
                                    bridge.openAttachment(
                                        url = externalUrl,
                                        attachmentId = payload.path.ifBlank { payload.name },
                                        fileName =
                                            payload.externalFileName
                                                .ifBlank { payload.name }
                                                .ifBlank { payload.path }
                                                .ifBlank { "attachment" },
                                        mimeType = payload.externalMimeType
                                    )
                                when (result?.optString("status").orEmpty()) {
                                    "opened", "" -> Unit
                                    "no_app" ->
                                        bridge.toast(
                                            result?.optString("message").orEmpty().ifBlank { "No app can open this file" }
                                        )
                                    "storage_failed" ->
                                        bridge.toast(
                                            result?.optString("message").orEmpty().ifBlank { "Storage unavailable" }
                                        )
                                    else ->
                                        bridge.toast(
                                            result?.optString("message").orEmpty().ifBlank { "Unable to open attachment" }
                                        )
                                }
                            }
                        }
                    )
                }
            }
    ) {
        SlockFilePreviewPayloadContent(
            payload = payload,
            onOpenUrl = {}
        )
    }
}

internal fun Pager.openSlockFilePreviewPage(payload: SlockFilePreviewPayload) {
    val pageData =
        JSONObject().apply {
            put("pageName", SLOCK_FILE_PREVIEW_PAGE)
            put("payload", SlockFilePreviewJson.encodeToString(payload))
        }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SLOCK_FILE_PREVIEW_PAGE, pageData)
}

internal fun SlockWorkspaceFilePreview.toSlockFilePreviewPayload(path: String): SlockFilePreviewPayload =
    when (this) {
        is SlockWorkspaceFilePreview.Text ->
            SlockFilePreviewPayload(
                kind = if (markdown) "markdown" else "text",
                title = if (markdown) "Markdown preview" else "Text preview",
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                content = content,
                markdown = markdown
            )
        is SlockWorkspaceFilePreview.Image ->
            SlockFilePreviewPayload(
                kind = "image",
                title = "Image preview",
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                imageUrl = imageUrl,
                message = description.orEmpty().ifBlank { "Image preview is unavailable." }
            )
        is SlockWorkspaceFilePreview.Unsupported ->
            SlockFilePreviewPayload(
                kind = "unsupported",
                title = "File preview",
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                message = reason
            )
        is SlockWorkspaceFilePreview.Error ->
            SlockFilePreviewPayload(
                kind = "error",
                title = "Preview unavailable",
                subtitle = path,
                name = path,
                path = path,
                message = reason,
                error = reason
            )
        is SlockWorkspaceFilePreview.Loading ->
            SlockFilePreviewPayload(
                kind = "loading",
                title = "Loading preview...",
                subtitle = path,
                name = path,
                path = path,
                message = detail
            )
    }

@Composable
internal fun SlockFilePreviewPayloadContent(
    payload: SlockFilePreviewPayload,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (payload.kind) {
        "markdown" ->
            SlockFilePreviewMarkdownPanel(
                name = payload.name.ifBlank { payload.path },
                meta = payload.meta,
                statusLabel = null,
                status = "content",
                body = payload.content,
                error = null,
                onOpenUrl = onOpenUrl,
                modifier = modifier.heightIn(min = 280.dp, max = 560.dp),
                performanceKey = "preview:${payload.path}",
                textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp)
            )
        "text" ->
            SlockFilePreviewTextPanel(
                name = payload.name.ifBlank { payload.path },
                meta = payload.meta,
                content = payload.content,
                modifier = modifier
            )
        "image" ->
            if (payload.imageUrl != null) {
                SlockFilePreviewImagePanel(
                    source = payload.imageUrl,
                    contentDescription = payload.name.ifBlank { payload.path },
                    modifier = modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)
                )
            } else {
                SlockFilePreviewGenericCard(
                    iconName = SlockLucideIconName.Image,
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    message = payload.message.ifBlank { "Image preview is unavailable." },
                    modifier = modifier.fillMaxWidth()
                )
            }
        "loading" ->
            SlockFilePreviewMessage(
                iconName = SlockLucideIconName.Loader,
                title = "Loading preview...",
                detail = payload.message.ifBlank { "Fetching preview content." },
                modifier = modifier.fillMaxWidth()
            )
        "error" ->
            SlockFilePreviewMessage(
                iconName = SlockLucideIconName.TriangleAlert,
                title = "Preview unavailable",
                detail = payload.error ?: payload.message.ifBlank { "Unable to load this file preview." },
                modifier = modifier.fillMaxWidth()
            )
        else ->
            SlockFilePreviewGenericCard(
                iconName = SlockLucideIconName.File,
                name = payload.name.ifBlank { payload.path.ifBlank { "File" } },
                meta = payload.meta,
                message = payload.message.ifBlank { "No preview is available for this file type." },
                modifier = modifier.fillMaxWidth()
            )
    }
}

private fun SlockFilePreviewPayload.previewIconName(): SlockLucideIconName =
    when (kind) {
        "markdown", "text" -> SlockLucideIconName.FileText
        "image" -> SlockLucideIconName.Image
        "loading" -> SlockLucideIconName.Loader
        "error" -> SlockLucideIconName.TriangleAlert
        else -> SlockLucideIconName.File
    }

@Composable
private fun SlockFilePreviewExternalMenuButton(
    onOpenWithSystem: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    Box {
        SlockBrutalIconButton(
            iconName = SlockLucideIconName.Ellipsis,
            onClick = { menuOpen = true },
            size = 30.dp,
            shadowOffset = 2.dp,
            modifier = Modifier.onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInRoot()
                menuAnchor =
                    Offset(
                        x = pos.x + coordinates.size.width.toFloat() / 2f,
                        y = pos.y + coordinates.size.height.toFloat() / 2f
                    )
            }
        )
        if (menuOpen) {
            SlockBrutalDropdownMenu(
                anchor = menuAnchor,
                items =
                    listOf(
                        SlockBrutalDropdownMenuItem(
                            id = SlockFilePreviewOpenWithActionId,
                            label = "Open with...",
                            icon = SlockLucideIconName.ExternalLink
                        )
                    ),
                onSelect = { id ->
                    if (id == SlockFilePreviewOpenWithActionId) {
                        onOpenWithSystem()
                    }
                },
                onDismiss = { menuOpen = false },
                widthDp = 180f
            )
        }
    }
}

private fun SlockWorkspaceFilePreview.Text.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Image.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Unsupported.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        mimeType?.takeIf { it.isNotBlank() },
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun Long?.slockFilePreviewSizeLabel(): String? {
    val bytes = this ?: return null
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

@Composable
internal fun SlockFilePreviewPageScaffold(
    title: String,
    subtitle: String,
    iconName: SlockLucideIconName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = onBack,
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            },
            trailingContent = trailingContent
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            content()
        }
    }
}

@Composable
internal fun SlockFilePreviewGenericCard(
    iconName: SlockLucideIconName,
    name: String,
    meta: String,
    message: String,
    statusLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(24.dp), color = "#FF000000")
        Text(
            text = name.ifBlank { "File" },
            style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = message,
            style = SlockType.Body,
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        statusLabel?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewMarkdownPanel(
    name: String,
    meta: String,
    statusLabel: String?,
    status: String,
    body: String?,
    error: String?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    performanceKey: String? = null,
    textStyle: TextStyle? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { "Markdown" },
            meta = meta,
            statusLabel = statusLabel
        )
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        when (status) {
            "content" ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(SlockSpacing.Md),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    item {
                        SlockMarkdownBody(
                            body = body.orEmpty(),
                            textStyle = textStyle ?: SlockType.Body,
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.fillMaxWidth(),
                            performanceKey = performanceKey
                        )
                    }
                }
            "error" ->
                SlockFilePreviewMessage(
                    iconName = SlockLucideIconName.FileText,
                    title = "Markdown preview unavailable",
                    detail = error.orEmpty().ifBlank { "Open with system viewer to inspect this file." },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            else ->
                SlockFilePreviewMessage(
                    iconName = SlockLucideIconName.Loader,
                    title = "Loading markdown preview...",
                    detail = name.ifBlank { "Markdown file" },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
        }
    }
}

@Composable
internal fun SlockFilePreviewTextPanel(
    name: String,
    meta: String,
    content: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        SlockFilePreviewPanelHeader(name = name.ifBlank { "Text file" }, meta = meta, statusLabel = null)
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .background(SlockColor.SurfaceCream)
                .padding(SlockSpacing.Sm)
        ) {
            Text(
                text = content,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 28,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewImagePanel(
    source: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String = "IMG"
) {
    SlockAspectFitImagePreviewSurface(
        source = source,
        contentDescription = contentDescription,
        placeholderLabel = placeholderLabel,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 420.dp),
        backgroundColor = SlockColor.SurfaceWhite,
        borderWidth = SlockStroke.Default
    )
}

@Composable
internal fun SlockFilePreviewMessage(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(SlockSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(34.dp), color = "#FF000000")
        Text(
            text = title,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SlockFilePreviewPanelHeader(
    name: String,
    meta: String,
    statusLabel: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = name,
            style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        statusLabel?.let {
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
