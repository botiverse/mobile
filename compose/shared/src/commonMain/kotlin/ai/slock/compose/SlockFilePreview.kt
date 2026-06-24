package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.NetworkSlockThreadApi
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.SlockThreadApiClient
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.network.SlockNetworkModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.LinearProgressIndicator
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
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

internal const val SLOCK_FILE_PREVIEW_PAGE = "compose_file_preview"
private const val SlockFilePreviewOpenWithActionId = "open-with"
private const val SlockFilePreviewSourceThreadAttachment = "thread_attachment"
private const val SlockFilePreviewSourceAgentWorkspace = "agent_workspace"
private const val SlockFilePreviewMarkdownMaxChars = 80_000
private const val SlockFilePreviewTextMaxChars = 96_000
private const val SlockFilePreviewLoadingLabel = "Loading..."

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
    val externalMimeType: String = "",
    val source: String = "",
    val attachmentId: String = "",
    val agentId: String = ""
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
    val sharedStores = remember { SlockSharedStoreRegistry.get(pager, bridge) }
    val networkModule = remember { pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME) }
    val networkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = sharedStores.authStore,
                networkModule = networkModule,
                warningLogger = { message -> bridge.log("file_preview_network $message") }
            )
        }
    val threadApi =
        remember(networkClient) {
            SlockThreadApiClient(
                transport = NetworkSlockThreadApi(networkClient),
                diagnosticsLogger = { message -> bridge.log("file_preview_thread_api $message") }
            )
        }
    val memberRepository =
        remember(networkClient) {
            RealMemberRepository(
                sessionStore = sharedStores.authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("file_preview_member_warning $message") },
                logger = { message -> bridge.log("file_preview_member $message") }
            )
        }
    val coroutineScope = rememberCoroutineScope()
    var payload by remember(pager.pageData.params.toString()) {
        mutableStateOf(
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
        )
    }
    var downloadStatus by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("idle") }
    var downloadMessage by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("") }
    var downloadProgressPercent by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<Int?>(null) }
    LaunchedEffect(payload.source, payload.attachmentId, payload.agentId, payload.path, payload.kind) {
        val nextPayload =
            when (payload.source) {
                SlockFilePreviewSourceThreadAttachment ->
                    payload.loadThreadAttachmentPreview(threadApi, networkModule)
                SlockFilePreviewSourceAgentWorkspace ->
                    payload.loadAgentWorkspacePreview(memberRepository)
                else -> null
            }
        if (nextPayload != null) {
            payload = nextPayload
        }
    }
    suspend fun openPayloadExternally(currentPayload: SlockFilePreviewPayload): String? {
        val externalUrl =
            currentPayload.externalUrl?.takeIf { it.isNotBlank() }
                ?: currentPayload.threadAttachmentExternalUrl(threadApi)
                ?: return "Unable to download attachment"
        val result =
            bridge.openAttachment(
                url = externalUrl,
                attachmentId = currentPayload.attachmentId.ifBlank { currentPayload.path.ifBlank { currentPayload.name } },
                fileName =
                    currentPayload.externalFileName
                        .ifBlank { currentPayload.name }
                        .ifBlank { currentPayload.path }
                        .ifBlank { "attachment" },
                mimeType = currentPayload.externalMimeType,
                onProgress = { progress ->
                    downloadProgressPercent = progress.percent
                }
            )
        return when (result?.optString("status").orEmpty()) {
            "opened", "" -> null
            "no_app" -> result?.optString("message").orEmpty().ifBlank { "No app can open this file" }
            "storage_failed" -> result?.optString("message").orEmpty().ifBlank { "Storage unavailable" }
            else -> result?.optString("message").orEmpty().ifBlank { "Unable to open attachment" }
        }
    }
    val canOpenWith =
        payload.externalUrl?.isNotBlank() == true ||
            (payload.source == SlockFilePreviewSourceThreadAttachment && payload.attachmentId.isNotBlank())
    fun requestExternalOpen() {
        if (!canOpenWith || downloadStatus == "downloading") return
        downloadStatus = "downloading"
        downloadMessage = ""
        downloadProgressPercent = null
        coroutineScope.launch {
            val error = openPayloadExternally(payload)
            if (error == null) {
                downloadStatus = "idle"
                downloadMessage = ""
                downloadProgressPercent = null
            } else {
                downloadStatus = "failed"
                downloadMessage = error
                downloadProgressPercent = null
                bridge.toast(error)
            }
        }
    }
    SlockFilePreviewPageScaffold(
        title = payload.previewHeaderTitle(),
        subtitle = payload.previewHeaderSubtitle(),
        iconName = payload.previewIconName(),
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        contentPadding = 0.dp,
        overflowMenuItems =
            if (canOpenWith) {
                listOf(
                    SlockBrutalDropdownMenuItem(
                        id = SlockFilePreviewOpenWithActionId,
                        label = "Open with...",
                        icon = SlockLucideIconName.ExternalLink
                    )
                )
            } else {
                emptyList()
            },
        onOverflowMenuSelect = { id ->
            if (id != SlockFilePreviewOpenWithActionId) return@SlockFilePreviewPageScaffold
            requestExternalOpen()
        }
    ) {
        SlockFilePreviewPayloadContent(
            payload = payload,
            onOpenUrl = {},
            downloadStatusLabel =
                when (downloadStatus) {
                    "downloading" -> downloadProgressPercent?.let { "Downloading $it%" } ?: "Downloading..."
                    "failed" -> downloadMessage.ifBlank { "Download failed" }
                    else -> null
                },
            downloadInProgress = downloadStatus == "downloading",
            downloadProgressPercent = downloadProgressPercent,
            onDownload = if (canOpenWith) ::requestExternalOpen else null,
            modifier = Modifier.weight(1f).fillMaxWidth()
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

internal fun KuiklyThreadAttachmentItem.toThreadAttachmentLazyPreviewPayload(kind: String): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        title = when (kind) {
            "image" -> "Image preview"
            "markdown" -> "Markdown preview"
            "text" -> "Text preview"
            else -> "File preview"
        },
        subtitle = name.ifBlank { "Attachment" },
        name = name.ifBlank { "Attachment" },
        path = name,
        meta = genericAttachmentMetaLine(),
        message = SlockFilePreviewLoadingLabel,
        externalFileName = name.ifBlank { "attachment-${id.take(8)}" },
        externalMimeType = mimeType,
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = id
    )

internal fun agentWorkspaceLazyPreviewPayload(agentId: String, path: String): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        title = "File preview",
        subtitle = path,
        name = path,
        path = path,
        message = SlockFilePreviewLoadingLabel,
        source = SlockFilePreviewSourceAgentWorkspace,
        agentId = agentId
    )

private suspend fun SlockFilePreviewPayload.loadThreadAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkModule: SlockNetworkModule
): SlockFilePreviewPayload? {
    if (source != SlockFilePreviewSourceThreadAttachment || attachmentId.isBlank() || kind != "loading") return null
    val wantsMarkdown =
        slockFilePreviewIsMarkdownAttachment(
            fileName = name.ifBlank { path.ifBlank { externalFileName } },
            mimeType = externalMimeType
        )
    val wantsImage = externalMimeType.substringBefore(';').trim().startsWith("image/", ignoreCase = true)
    val wantsText =
        !wantsMarkdown &&
            slockFilePreviewIsTextLikeAttachment(
                fileName = name.ifBlank { path.ifBlank { externalFileName } },
                mimeType = externalMimeType
            )
    return when {
        wantsMarkdown -> loadThreadMarkdownAttachmentPreview(threadApi, networkModule)
        wantsImage -> loadThreadImageAttachmentPreview(threadApi)
        wantsText -> loadThreadTextAttachmentPreview(threadApi, networkModule)
        else ->
            copy(
                kind = "unsupported",
                title = "File preview",
                message = "No preview is available for this file type."
            )
    }
}

private suspend fun SlockFilePreviewPayload.loadThreadImageAttachmentPreview(
    threadApi: SlockThreadApiClient
): SlockFilePreviewPayload {
    val imageUrl =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        }
    return copy(
        kind = "image",
        title = "Image preview",
        imageUrl = imageUrl,
        message = if (imageUrl == null) "Image preview is unavailable." else "",
        error = if (imageUrl == null) "image_preview_unavailable" else null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadTextAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkModule: SlockNetworkModule
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            title = "Preview unavailable",
            message = "Unable to load text preview.",
            error = "text_preview_url_failed"
        )
    val response =
        try {
            networkModule.request(
                JSONObject().apply {
                    put("url", target)
                    put("method", "GET")
                    put("headers", JSONObject())
                    put("timeoutMillis", 30_000)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                title = "Preview unavailable",
                message = "Unable to load text preview.",
                error = "text_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            title = "Preview unavailable",
            message = "Unable to load text preview.",
            error = "text_preview_fetch_failed"
        )
    }
    val body = response.bodyText.orEmpty()
    val previewBody =
        if (body.length > SlockFilePreviewTextMaxChars) {
            body.take(SlockFilePreviewTextMaxChars) +
                "\n\nPreview truncated. Open with system viewer for the full file."
        } else {
            body
        }
    return copy(
        kind = "text",
        title = "Text preview",
        content = previewBody,
        markdown = false,
        message = "",
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadMarkdownAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkModule: SlockNetworkModule
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            title = "Preview unavailable",
            message = "Unable to load markdown preview.",
            error = "markdown_preview_url_failed"
        )
    val response =
        try {
            networkModule.request(
                JSONObject().apply {
                    put("url", target)
                    put("method", "GET")
                    put("headers", JSONObject())
                    put("timeoutMillis", 30_000)
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                title = "Preview unavailable",
                message = "Unable to load markdown preview.",
                error = "markdown_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            title = "Preview unavailable",
            message = "Unable to load markdown preview.",
            error = "markdown_preview_fetch_failed"
        )
    }
    val body =
        response.bodyText.takeIf { it.isNotBlank() }
            ?: return copy(
                kind = "error",
                title = "Preview unavailable",
                message = "Markdown file is empty.",
                error = "markdown_preview_empty"
            )
    val previewBody =
        if (body.length > SlockFilePreviewMarkdownMaxChars) {
            body.take(SlockFilePreviewMarkdownMaxChars) +
                "\n\n_Preview truncated. Open with system viewer for the full file._"
        } else {
            body
        }
    return copy(
        kind = "markdown",
        title = "Markdown preview",
        content = previewBody,
        markdown = true,
        message = "",
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.threadAttachmentExternalUrl(
    threadApi: SlockThreadApiClient
): String? {
    if (source != SlockFilePreviewSourceThreadAttachment || attachmentId.isBlank()) return null
    return when (val response = threadApi.attachmentUrl(attachmentId, disposition = "attachment")) {
        is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
        is ThreadApiResult.Failure -> null
    }
}

private suspend fun SlockFilePreviewPayload.loadAgentWorkspacePreview(
    memberRepository: RealMemberRepository
): SlockFilePreviewPayload? {
    if (source != SlockFilePreviewSourceAgentWorkspace || agentId.isBlank() || path.isBlank() || kind != "loading") return null
    return when (val result = memberRepository.readAgentWorkspaceFile(DataRequestPriority.Visible, MemberId(agentId), path)) {
        is DataResult.Content -> result.data.preview.toSlockFilePreviewPayload(result.data.path).copy(source = source, agentId = agentId)
        is DataResult.Empty ->
            copy(
                kind = "error",
                title = "Preview unavailable",
                message = "File content is unavailable.",
                error = "empty_preview"
            )
        is DataResult.Error ->
            copy(
                kind = "error",
                title = "Preview unavailable",
                message = "Failed to load: ${result.error.code}",
                error = result.error.code
            )
        is DataResult.NoPermission ->
            copy(
                kind = "error",
                title = "Preview hidden",
                message = "File hidden: ${result.blocker.code}",
                error = result.blocker.code
            )
    }
}

internal fun slockFilePreviewIsMarkdownAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    return normalizedMime.equals("text/markdown", ignoreCase = true) ||
        normalizedMime.equals("text/x-markdown", ignoreCase = true) ||
        fileName.endsWith(".md", ignoreCase = true) ||
        fileName.endsWith(".markdown", ignoreCase = true)
}

internal fun slockFilePreviewIsTextLikeAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    if (normalizedMime.startsWith("text/")) return true
    if (normalizedMime in SlockFilePreviewTextMimeTypes) return true
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in SlockFilePreviewTextFileExtensions
}

private val SlockFilePreviewTextMimeTypes =
    setOf(
        "application/csv",
        "application/ecmascript",
        "application/javascript",
        "application/json",
        "application/ld+json",
        "application/sql",
        "application/toml",
        "application/typescript",
        "application/x-javascript",
        "application/x-ndjson",
        "application/x-python-code",
        "application/x-sh",
        "application/x-yaml",
        "application/xml",
        "image/svg+xml"
    )

private val SlockFilePreviewTextFileExtensions =
    setOf(
        "bash",
        "c",
        "cc",
        "cfg",
        "cjs",
        "conf",
        "cpp",
        "cs",
        "css",
        "csv",
        "diff",
        "env",
        "go",
        "h",
        "hpp",
        "htm",
        "html",
        "ini",
        "java",
        "js",
        "json",
        "jsonl",
        "jsx",
        "kt",
        "kts",
        "less",
        "log",
        "mjs",
        "patch",
        "php",
        "properties",
        "py",
        "rb",
        "rs",
        "sass",
        "scss",
        "sh",
        "sql",
        "svg",
        "swift",
        "text",
        "toml",
        "ts",
        "tsx",
        "tsv",
        "txt",
        "xml",
        "yaml",
        "yml",
        "zsh"
    )

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
                title = "File preview",
                subtitle = path,
                name = path,
                path = path,
                message = detail.ifBlank { SlockFilePreviewLoadingLabel }
            )
    }

@Composable
internal fun SlockFilePreviewPayloadContent(
    payload: SlockFilePreviewPayload,
    onOpenUrl: (String) -> Unit,
    downloadStatusLabel: String? = null,
    downloadInProgress: Boolean = false,
    downloadProgressPercent: Int? = null,
    onDownload: (() -> Unit)? = null,
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
                modifier = modifier.fillMaxSize(),
                performanceKey = "preview:${payload.path}",
                textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 18.sp),
                showChrome = false
            )
        "text" ->
            SlockFilePreviewTextPanel(
                name = payload.name.ifBlank { payload.path },
                meta = payload.meta,
                content = payload.content,
                modifier = modifier.fillMaxSize(),
                showChrome = false
            )
        "image" ->
            if (payload.imageUrl != null) {
                SlockFilePreviewImagePanel(
                    source = payload.imageUrl,
                    contentDescription = payload.name.ifBlank { payload.path },
                    modifier = modifier.fillMaxSize(),
                    backgroundColor = SlockColor.SurfaceWhite,
                    borderWidth = 0.dp
                )
            } else {
                SlockFilePreviewGenericCard(
                    iconName = SlockLucideIconName.Image,
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    message = payload.message.ifBlank { "Image preview is unavailable." },
                    statusLabel = downloadStatusLabel,
                    showProgress = downloadInProgress,
                    progressPercent = downloadProgressPercent,
                    actionLabel = if (onDownload != null) {
                        if (downloadInProgress) "Downloading..." else "Download"
                    } else {
                        null
                    },
                    actionIcon = if (downloadInProgress) SlockLucideIconName.Loader else SlockLucideIconName.Download,
                    actionEnabled = !downloadInProgress,
                    onAction = onDownload,
                    modifier = modifier.fillMaxSize().padding(SlockSpacing.Md)
                )
            }
        "loading" -> {
            SlockFilePreviewLoadingIndicator(
                modifier = modifier.fillMaxSize()
            )
        }
        "error" ->
            SlockFilePreviewMessage(
                iconName = SlockLucideIconName.TriangleAlert,
                title = "Preview unavailable",
                detail = payload.error ?: payload.message.ifBlank { "Unable to load this file preview." },
                modifier = modifier.fillMaxWidth().padding(SlockSpacing.Md)
            )
        else ->
            SlockFilePreviewGenericCard(
                iconName = SlockLucideIconName.File,
                name = payload.name.ifBlank { payload.path.ifBlank { "File" } },
                meta = payload.meta,
                message = payload.message.ifBlank { "No preview is available for this file type." },
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                actionLabel = if (onDownload != null) {
                    if (downloadInProgress) "Downloading..." else "Download"
                } else {
                    null
                },
                actionIcon = if (downloadInProgress) SlockLucideIconName.Loader else SlockLucideIconName.Download,
                actionEnabled = !downloadInProgress,
                onAction = onDownload,
                modifier = modifier.fillMaxSize().padding(SlockSpacing.Md)
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

private fun SlockFilePreviewPayload.previewHeaderTitle(): String {
    val source =
        name
            .ifBlank { path }
            .ifBlank { externalFileName }
            .ifBlank { title }
            .ifBlank { "File" }
    return source.slockFilePreviewFileName()
}

private fun SlockFilePreviewPayload.previewHeaderSubtitle(): String? {
    val headerTitle = previewHeaderTitle()
    return listOf(meta, path, subtitle, externalMimeType)
        .asSequence()
        .map { it.trim() }
        .firstOrNull { candidate ->
            candidate.isNotBlank() &&
                candidate != headerTitle &&
                candidate.slockFilePreviewFileName() != headerTitle &&
                !candidate.endsWith("preview", ignoreCase = true)
        }
}

private fun String.slockFilePreviewFileName(): String {
    val normalized = trim().replace('\\', '/')
    return normalized.substringAfterLast('/').ifBlank { normalized.ifBlank { "File" } }
}

@Composable
internal fun SlockFilePreviewOverflowMenuButton(
    onOpenMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    SlockBrutalIconButton(
        iconName = SlockLucideIconName.Ellipsis,
        onClick = { onOpenMenu(menuAnchor) },
        size = 30.dp,
        shadowOffset = 2.dp,
        modifier = modifier.onGloballyPositioned { coordinates ->
            val pos = coordinates.positionInRoot()
            menuAnchor =
                Offset(
                    x = pos.x + coordinates.size.width.toFloat() / 2f,
                    y = pos.y + coordinates.size.height.toFloat() / 2f
                )
        }
    )
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
    subtitle: String? = null,
    iconName: SlockLucideIconName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Md,
    overflowMenuItems: List<SlockBrutalDropdownMenuItem> = emptyList(),
    onOverflowMenuSelect: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                trailingContent =
                    if (overflowMenuItems.isNotEmpty()) {
                        {
                            SlockFilePreviewOverflowMenuButton(
                                onOpenMenu = { anchor ->
                                    menuAnchor = anchor
                                    menuOpen = true
                                }
                            )
                        }
                    } else {
                        null
                    }
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                content()
            }
        }
        if (menuOpen && overflowMenuItems.isNotEmpty()) {
            SlockBrutalDropdownMenu(
                anchor = menuAnchor,
                items = overflowMenuItems,
                onSelect = { id ->
                    menuOpen = false
                    onOverflowMenuSelect(id)
                },
                onDismiss = { menuOpen = false },
                widthDp = 180f
            )
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
    showProgress: Boolean = false,
    progressPercent: Int? = null,
    actionLabel: String? = null,
    actionIcon: SlockLucideIconName? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(72.dp), color = "#FF000000")
        Box(modifier = Modifier.height(SlockSpacing.Md))
        Text(
            text = name.ifBlank { "File" },
            style = SlockType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = SlockColor.TextBlack,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (meta.isNotBlank()) {
            Box(modifier = Modifier.height(4.dp))
            Text(
                text = meta,
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Box(modifier = Modifier.height(SlockSpacing.Md))
        Text(
            text = message,
            style = SlockType.Body,
            color = SlockColor.TextMutedGray,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        statusLabel?.let {
            Box(modifier = Modifier.height(SlockSpacing.Sm))
            Text(
                text = it,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (showProgress) {
            Box(modifier = Modifier.height(SlockSpacing.Md))
            SlockFilePreviewProgressBar(progressPercent = progressPercent)
        }
        if (actionLabel != null && onAction != null) {
            Box(modifier = Modifier.height(SlockSpacing.Lg))
            SlockBrutalActionButton(
                label = actionLabel,
                onClick = onAction,
                enabled = actionEnabled,
                leadingIcon = actionIcon,
                minWidth = 132.dp,
                minHeight = 40.dp
            )
        }
    }
}

@Composable
private fun SlockFilePreviewProgressBar(progressPercent: Int?) {
    val percent = progressPercent?.coerceIn(0, 100)
    if (percent == null) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = SlockColor.AccentCyan,
            trackColor = SlockColor.SurfaceCream
        )
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
    ) {
        if (percent > 0) {
            Box(
                modifier =
                    Modifier
                        .weight(percent.toFloat())
                        .fillMaxSize()
                        .background(SlockColor.AccentCyan)
            )
        }
        if (percent < 100) {
            Box(
                modifier =
                    Modifier
                        .weight((100 - percent).toFloat())
                        .fillMaxSize()
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
    textStyle: TextStyle? = null,
    showChrome: Boolean = true
) {
    if (!showChrome && status == "content") {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
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
        return
    }
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
                SlockFilePreviewLoadingIndicator(
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
    modifier: Modifier = Modifier,
    showChrome: Boolean = true
) {
    if (!showChrome) {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(SlockSpacing.Md)
        ) {
            item {
                Text(
                    text = content,
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip
                )
            }
        }
        return
    }
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
    placeholderLabel: String = "IMG",
    backgroundColor: com.tencent.kuikly.compose.ui.graphics.Color = SlockColor.SurfaceWhite,
    borderWidth: com.tencent.kuikly.compose.ui.unit.Dp = SlockStroke.Default
) {
    SlockAspectFitImagePreviewSurface(
        source = source,
        contentDescription = contentDescription,
        placeholderLabel = placeholderLabel,
        modifier = modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        borderWidth = borderWidth,
        zoomable = true
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
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SlockFilePreviewLoadingIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(SlockSpacing.Lg),
        contentAlignment = Alignment.Center
    ) {
        SlockHeaderRefreshIndicator()
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
