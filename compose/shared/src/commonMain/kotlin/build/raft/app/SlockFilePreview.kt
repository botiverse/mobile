package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.Utils
import build.raft.app.base.reduceAttachmentDownloadProgress
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.AttachmentPreview
import build.raft.app.data.AttachmentPreviewContent
import build.raft.app.data.parseSharedAttachmentPreview
import build.raft.app.data.DataResult
import build.raft.app.data.NetworkSlockThreadApi
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.SlockThreadApiClient
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadApiResult
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.model.MemberId
import build.raft.app.model.SlockWorkspaceFilePreview
import build.raft.app.network.binaryDownloadUrlDiagnostics
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.gestures.detectDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
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
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.extension.nativeRef
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.coil3.rememberAsyncImageBitmap
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.material3.LinearProgressIndicator
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.ImageBitmap
import com.tencent.kuikly.compose.ui.graphics.StrokeCap
import com.tencent.kuikly.compose.ui.graphics.drawscope.DrawScope
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.semantics.contentDescription
import com.tencent.kuikly.compose.ui.semantics.semantics
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.core.views.VideoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val SLOCK_FILE_PREVIEW_PAGE = "compose_file_preview"
private const val SLOCK_FILE_PREVIEW_EDITOR_VISUAL_PAGE = "compose_file_preview_editor_visual"
internal const val SLOCK_COMPOSER_IMAGE_EDIT_EVENT = "slock.composerImageEdit"
private const val SlockFilePreviewOpenWithActionId = "open-with"
private const val SlockFilePreviewDeleteDownloadActionId = "delete-download"
private const val SlockFilePreviewCommentsActionId = "comments"
private const val SlockFilePreviewSourceThreadAttachment = "thread_attachment"
private const val SlockFilePreviewSourceAgentWorkspace = "agent_workspace"
private const val SlockFilePreviewMarkdownMaxChars = 80_000
private const val SlockFilePreviewTextMaxChars = 96_000
private const val SlockFilePreviewVideoSeekProp = "seekToTimeMs"
private const val SlockFilePreviewImageGalleryMaxInFlightRequests = 2

private val SlockFilePreviewJson = Json { ignoreUnknownKeys = true }

internal data class SlockFilePreviewVideoSeekRequest(
    val id: Int,
    val timeMs: Int
)

@Serializable
internal data class WireSlockFilePreviewLaunchPayload(
    val previewId: String = ""
)

internal class SlockFilePreviewStore(
    private val maxEntries: Int = 32
) {
    private var nextId = 0
    private val entries = LinkedHashMap<String, SlockFilePreviewPayload>()

    fun put(payload: SlockFilePreviewPayload): String {
        nextId += 1
        val id = "file-preview-$nextId"
        entries[id] = payload
        while (entries.size > maxEntries) {
            val firstKey = entries.keys.firstOrNull() ?: break
            entries.remove(firstKey)
        }
        return id
    }

    fun get(id: String): SlockFilePreviewPayload? =
        entries[id]
}

internal object SlockFilePreviewLaunchPayloadStore {
    private val store = SlockFilePreviewStore()

    fun put(payload: SlockFilePreviewPayload): String =
        store.put(payload)

    fun get(id: String): SlockFilePreviewPayload? =
        store.get(id)
}

internal data class SlockFilePreviewPayload(
    val kind: String = "unsupported",
    val requestedKind: String = "",
    val title: String = "",
    val titleDesc: StringDesc = MR.strings.file_preview_title.desc(),
    val subtitle: String = "",
    val name: String = "",
    val path: String = "",
    val meta: String = "",
    val content: String = "",
    val markdown: Boolean = false,
    val imageUrl: String? = null,
    val message: String = "",
    val messageDesc: StringDesc? = MR.strings.file_preview_no_preview.desc(),
    val error: String? = null,
    val externalUrl: String? = null,
    val videoUrl: String? = null,
    val audioOnly: Boolean = false,
    val externalFileName: String = "",
    val externalMimeType: String = "",
    val source: String = "",
    val attachmentId: String = "",
    val attachmentCommentsEnabled: Boolean = false,
    val commentsInitiallyOpen: Boolean = false,
    val agentId: String = "",
    val imageGallery: List<SlockFilePreviewGalleryImage> = emptyList(),
    val imageGalleryIndex: Int = 0,
    val composerImageThreadKey: String = "",
    val composerImageActionId: String = "",
    val composerImageFileRef: String = "",
    val composerImageFileName: String = "",
    val composerImageMimeType: String = "",
    val composerImageSizeBytes: Long? = null,
    val composerImagePreviewUri: String = "",
    val composerImageReplaceAttachmentId: String = "",
    val composerImageCanComplete: Boolean = false,
    val composerImageCanEdit: Boolean = false
)

@Serializable
internal data class SlockFilePreviewGalleryImage(
    val attachmentId: String,
    val name: String = "",
    val mimeType: String = "",
    val sizeLabel: String = "",
    val thumbnailUrl: String? = null,
    val composerImageFileRef: String = "",
    val composerImagePreviewUri: String = "",
    val composerImageSizeBytes: Long? = null
)

@Page(SLOCK_FILE_PREVIEW_PAGE, supportInLocal = true)
internal class ComposeSlockFilePreviewPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockFilePreviewStandalonePageContent() }
    }
}

@Page(SLOCK_FILE_PREVIEW_EDITOR_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSlockFilePreviewEditorVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SlockFilePreviewEditorVisualContent() }
    }
}

@Composable
private fun SlockFilePreviewEditorVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val visualFixture = pager.pageData.params.optString("visualFixture")
    val payload =
        remember {
            SlockFilePreviewPayload(
                kind = "image",
                title = "Design crop",
                name = "design-crop.png",
                imageUrl = SlockFilePreviewEditorVisualPreviewUri,
                composerImageActionId = "visual-image-editor",
                composerImageFileRef = "visual-image-editor-file",
                composerImageCanEdit = true,
                composerImageCanComplete = true,
            )
        }
    val editorState = remember(payload.imageUrl) { SlockComposerImageEditorState() }
    if (visualFixture == "action_bar") {
        SlockFilePreviewPageScaffold(
            title = payload.previewHeaderTitle(),
            subtitle = payload.previewHeaderSubtitle(),
            iconName = payload.previewIconName(),
            onBack = {},
            contentPadding = 0.dp,
        ) {
            SlockFilePreviewPayloadContent(
                payload = payload,
                onOpenUrl = {},
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            SlockFilePreviewComposerImageActionBar(
                payload = payload,
                enabled = true,
                onEdit = {},
                onComplete = {},
            )
        }
        return
    }
    SlockFilePreviewPageScaffold(
        title = stringResource(MR.strings.file_preview_editor_title),
        iconName = SlockLucideIconName.Pencil,
        onBack = {},
        contentPadding = 0.dp,
        trailingContent = {
            SlockFilePreviewImageEditorHistoryActions(
                state = editorState,
                saving = false,
            )
        },
    ) {
        SlockFilePreviewComposerImageEditor(
            payload = payload,
            state = editorState,
            saving = false,
            onCaptureRef = {},
            onDone = {},
            initialTool =
                if (visualFixture == "text_ime") {
                    SlockComposerImageEditorTool.Text
                } else {
                    SlockComposerImageEditorTool.Pencil
                },
            initialPencilOptionsExpanded = visualFixture == "pencil_options",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun SlockFilePreviewStandalonePageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val eventBus = sharedStores.eventBus
    val networkClient = sharedStores.networkClient
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
    val launchPayloadTrace =
        remember(pager.pageData.params.toString()) {
            val source = pager.pageData.params.optString("payload")
            slockFilePreviewResolveLaunchPayload(source, SlockFilePreviewLaunchPayloadStore::get)
        }
    LaunchedEffect(launchPayloadTrace.durationMs, launchPayloadTrace.responseBytes, launchPayloadTrace.result) {
        bridge.log(
            "file_preview_launch_payload_parse source=pageData result=${launchPayloadTrace.result} " +
                "responseBytes=${launchPayloadTrace.responseBytes} durationMs=${launchPayloadTrace.durationMs} dispatcher=caller"
        )
    }
    var payload by remember(launchPayloadTrace.payload) {
        mutableStateOf(launchPayloadTrace.payload)
    }
    var attachmentCommentsState by remember(payload.attachmentId) {
        mutableStateOf<ThreadAttachmentCommentsDialogState?>(null)
    }
    var imageGalleryResolutionState by remember(launchPayloadTrace.payload.imageGallery) {
        mutableStateOf(SlockFilePreviewImageGalleryResolutionState())
    }
    val imageGalleryResolutionJobs = remember(launchPayloadTrace.payload.imageGallery) {
        mutableMapOf<String, Job>()
    }
    var composerImageEditMode by remember(payload.composerImageActionId, payload.attachmentId, payload.imageUrl) {
        mutableStateOf(false)
    }
    val composerImageEditorState =
        remember(payload.composerImageActionId, payload.attachmentId, payload.imageUrl, composerImageEditMode) {
            SlockComposerImageEditorState()
        }
    var composerImageEditSaving by remember(payload.composerImageActionId) { mutableStateOf(false) }
    var composerImageEditCaptureRef by remember(payload.composerImageActionId) {
        mutableStateOf<ViewRef<DeclarativeBaseView<*, *>>?>(null)
    }
    var downloadStatus by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("idle") }
    var downloadMessage by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf("") }
    var downloadProgress by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<BridgeAttachmentDownloadProgress?>(null) }
    var activeDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var nextDownloadGeneration by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf(0L) }
    var activeDownloadStartedAt by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<TimeMark?>(null) }
    var firstBridgeDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var firstRenderedDownloadId by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf<String?>(null) }
    var attachmentCacheState by remember(payload.source, payload.attachmentId, payload.path) { mutableStateOf(false) }
    val unableToSendComment = stringResource(MR.strings.file_preview_error_send_comment)
    val unableToResolveComment = stringResource(MR.strings.file_preview_error_resolve_comment)
    val attachmentFallback = stringResource(MR.strings.file_preview_fallback_attachment)
    val actionTerms = slockFilePreviewLocalizedActionTerms()
    val downloadStopped = stringResource(MR.strings.file_preview_download_stopped)
    val downloadFailed = stringResource(MR.strings.file_preview_download_failed)
    val downloadSaved = stringResource(MR.strings.file_preview_download_saved)
    val unableToDownloadAttachment = stringResource(MR.strings.file_preview_error_download_attachment)
    val noAppCanOpenFile = stringResource(MR.strings.file_preview_error_no_app)
    val storageUnavailable = stringResource(MR.strings.file_preview_error_storage_unavailable)
    val unableToOpenAttachment = stringResource(MR.strings.file_preview_error_open_attachment)
    val downloadedFileDeleted = stringResource(MR.strings.file_preview_download_deleted)
    val unableToDeleteDownload = stringResource(MR.strings.file_preview_error_delete_download)
    val imageEditorLoading = stringResource(MR.strings.file_preview_error_editor_loading)
    val imageEditorExportFailed = stringResource(MR.strings.file_preview_error_editor_export)
    val truncatedPreviewHint = stringResource(MR.strings.file_preview_truncated_open_with_hint)
    val attachmentCacheId = payload.attachmentCacheId()
    val attachmentCacheFileName = payload.attachmentCacheFileName()
    LaunchedEffect(downloadProgress?.downloadId, downloadProgress?.downloadedBytes) {
        val progress = downloadProgress ?: return@LaunchedEffect
        if (progress.downloadedBytes <= 0L || firstRenderedDownloadId == progress.downloadId) return@LaunchedEffect
        withFrameNanos { _ -> }
        firstRenderedDownloadId = progress.downloadId
        bridge.log(
            "file_preview_download phase=first_render downloadIdHash=${progress.downloadId.hashCode().toString(16)} " +
                "sequence=${progress.sequence} downloadedBytes=${progress.downloadedBytes} " +
                "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}"
        )
    }
    fun setAttachmentCommentsState(next: ThreadAttachmentCommentsDialogState) {
        attachmentCommentsState =
            attachmentCommentsState
                ?.takeIf { current -> current.attachment.id == next.attachment.id }
                ?.let { next }
    }
    fun loadAttachmentComments(state: ThreadAttachmentCommentsDialogState) {
        coroutineScope.launch {
            val next =
                when (val result = threadApi.attachmentComments(state.attachment.id)) {
                    is ThreadApiResult.Success ->
                        state.copy(
                            status = "loaded",
                            page = result.data,
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = null
                        )
                    is ThreadApiResult.Failure ->
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet(),
                            errorCode = result.contractCode ?: "http_${result.code}"
                        )
                }
            setAttachmentCommentsState(next)
        }
    }
    fun openAttachmentComments() {
        if (!payload.supportsAttachmentComments()) return
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment =
                    KuiklyThreadAttachmentItem(
                        id = payload.attachmentId,
                        name = payload.externalFileName.ifBlank { payload.name.ifBlank { attachmentFallback } },
                        mimeType = payload.externalMimeType
                    ),
                status = "loading",
                pendingAnchorLabel = payload.externalFileName.ifBlank { payload.name.ifBlank { attachmentFallback } }
            )
        attachmentCommentsState = state
        loadAttachmentComments(state)
    }
    fun reloadAttachmentComments() {
        val current = attachmentCommentsState ?: return
        val loading = current.copy(status = "loading", errorCode = null)
        attachmentCommentsState = loading
        loadAttachmentComments(loading)
    }
    fun sendAttachmentComment() {
        val current = attachmentCommentsState ?: return
        val body = current.draft.trim()
        if (body.isBlank() || current.sending) return
        attachmentCommentsState = current.copy(sending = true, errorCode = null)
        coroutineScope.launch {
            when (val result = threadApi.sendAttachmentComment(current.attachment.id, body)) {
                is ThreadApiResult.Success -> {
                    val loading =
                        current.copy(
                            status = "loading",
                            draft = "",
                            sending = false,
                            pendingQuoteLabel = null,
                            errorCode = null
                        )
                    attachmentCommentsState = loading
                    loadAttachmentComments(loading)
                }
                is ThreadApiResult.Failure -> {
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false, errorCode = result.contractCode ?: "http_${result.code}")
                    bridge.toast(unableToSendComment)
                }
            }
        }
    }
    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) {
        val current = attachmentCommentsState ?: return
        if (comment.id.isBlank() || comment.isThreadAttachmentCommentResolved()) return
        if (comment.id in current.resolvingCommentIds) return
        attachmentCommentsState =
            current.copy(resolvingCommentIds = current.resolvingCommentIds + comment.id, errorCode = null)
        coroutineScope.launch {
            when (val result = threadApi.addReaction(comment.id, "✅")) {
                is ThreadApiResult.Success -> {
                    val loading =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(status = "loading", resolvingCommentIds = emptySet(), errorCode = null)
                            ?: return@launch
                    attachmentCommentsState = loading
                    loadAttachmentComments(loading)
                }
                is ThreadApiResult.Failure -> {
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id,
                                    errorCode = result.contractCode ?: "http_${result.code}"
                                )
                            }
                    bridge.toast(unableToResolveComment)
                }
            }
        }
    }
    LaunchedEffect(payload.source, payload.attachmentId, payload.agentId, payload.path, payload.kind) {
        if (payload.isPendingThreadImageGalleryResolution()) {
            imageGalleryResolutionState.resolvedPayloadByAttachmentId[payload.attachmentId]
                ?.let(payload::withResolvedImageGalleryPayload)
                ?.let { cachedPayload ->
                    payload = cachedPayload
                    return@LaunchedEffect
                }
            val start = imageGalleryResolutionState.beginResolution(payload)
            imageGalleryResolutionState = start.state
            val requestPayload = start.requestPayload ?: return@LaunchedEffect
            start.cancelAttachmentId?.let { attachmentId ->
                imageGalleryResolutionJobs.remove(attachmentId)?.cancel()
                bridge.log(
                    "file_preview_image_resolve status=cancelled attachment=${attachmentId.take(8)} reason=concurrency_limit"
                )
            }
            bridge.log(
                "file_preview_image_resolve status=start attachment=${requestPayload.attachmentId.take(8)} " +
                    "kind=${requestPayload.kind} thumbnail=${requestPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
            val resolutionJob = coroutineScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val nextPayload =
                        requestPayload.loadThreadAttachmentPreview(
                            threadApi = threadApi,
                            networkClient = networkClient,
                            truncatedPreviewHint = truncatedPreviewHint
                        ) ?: return@launch
                    if (
                        !imageGalleryResolutionJobs.ownsImageGalleryResolution(
                            requestPayload.attachmentId,
                            currentCoroutineContext()[Job]
                        )
                    ) {
                        return@launch
                    }
                    imageGalleryResolutionState =
                        imageGalleryResolutionState.completeResolution(
                            requestPayload = requestPayload,
                            resolvedPayload = nextPayload
                        )
                    val visiblePayload = payload.withResolvedImageGalleryPayload(nextPayload)
                    if (visiblePayload != null) {
                        payload = visiblePayload
                    }
                    bridge.log(
                        "file_preview_image_resolve status=committed attachment=${nextPayload.attachmentId.take(8)} " +
                            "visible=${visiblePayload != null} cached=${nextPayload.hasResolvedRemoteImageComparedTo(requestPayload)} " +
                            "kind=${nextPayload.kind} error=${nextPayload.error ?: "none"} " +
                            "primary=${nextPayload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                            "fallback=${nextPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    if (
                        !imageGalleryResolutionJobs.ownsImageGalleryResolution(
                            requestPayload.attachmentId,
                            currentCoroutineContext()[Job]
                        )
                    ) {
                        return@launch
                    }
                    val failedPayload = requestPayload.imageGalleryResolutionFailurePayload()
                    payload.withResolvedImageGalleryPayload(failedPayload)?.let { visiblePayload ->
                        payload = visiblePayload
                    }
                    bridge.log(
                        "file_preview_image_resolve status=failed attachment=${requestPayload.attachmentId.take(8)} " +
                            "type=${throwable::class.simpleName ?: "unknown"}"
                    )
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (imageGalleryResolutionJobs.ownsImageGalleryResolution(requestPayload.attachmentId, currentJob)) {
                        imageGalleryResolutionJobs.remove(requestPayload.attachmentId)
                        imageGalleryResolutionState =
                            imageGalleryResolutionState.finishResolution(requestPayload.attachmentId)
                    }
                }
            }
            imageGalleryResolutionJobs[requestPayload.attachmentId] = resolutionJob
            resolutionJob.start()
            return@LaunchedEffect
        }
        if (
            payload.source == SlockFilePreviewSourceThreadAttachment &&
            payload.kind == "loading" &&
            payload.requestedKind == "image"
        ) {
            bridge.log(
                "file_preview_image_resolve status=start attachment=${payload.attachmentId.take(8)} " +
                    "kind=${payload.kind} thumbnail=${payload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
        }
        val nextPayload =
            when (payload.source) {
                SlockFilePreviewSourceThreadAttachment ->
                    payload.loadThreadAttachmentPreview(
                        threadApi = threadApi,
                        networkClient = networkClient,
                        truncatedPreviewHint = truncatedPreviewHint
                    )
                SlockFilePreviewSourceAgentWorkspace ->
                    payload.loadAgentWorkspacePreview(memberRepository)
                else -> null
        }
        if (nextPayload != null) {
            payload = nextPayload
            if (nextPayload.source == SlockFilePreviewSourceThreadAttachment && nextPayload.requestedKind == "image") {
                bridge.log(
                    "file_preview_image_resolve status=committed attachment=${nextPayload.attachmentId.take(8)} " +
                        "kind=${nextPayload.kind} error=${nextPayload.error ?: "none"} " +
                        "primary=${nextPayload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                        "fallback=${nextPayload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
                )
            }
        }
    }
    LaunchedEffect(payload.commentsInitiallyOpen, payload.attachmentId, payload.source) {
        if (payload.commentsInitiallyOpen && attachmentCommentsState == null) {
            openAttachmentComments()
        }
    }
    LaunchedEffect(payload.kind, payload.requestedKind, payload.attachmentId, payload.imageUrl) {
        if (payload.shouldRenderImagePreview()) {
            bridge.log(
                "file_preview_image_render attachment=${payload.attachmentId.take(8)} kind=${payload.kind} " +
                    "primary=${payload.imageUrl?.let(::binaryDownloadUrlDiagnostics) ?: "none"} " +
                    "fallback=${payload.currentImageThumbnailUrl()?.let(::binaryDownloadUrlDiagnostics) ?: "none"}"
            )
        }
    }
    val canOpenWith = canOpenPayloadWithExternalViewer(payload)
    val canOpenComments = payload.supportsAttachmentComments()
    LaunchedEffect(canOpenWith, attachmentCacheId, attachmentCacheFileName, payload.externalMimeType) {
        attachmentCacheState = false
        if (!canOpenWith) return@LaunchedEffect
        val result =
            bridge.attachmentCacheStatus(
                attachmentId = attachmentCacheId,
                fileName = attachmentCacheFileName,
                mimeType = payload.externalMimeType
            )
        attachmentCacheState = result?.optBoolean("cached") == true
    }
    suspend fun openPayloadExternally(currentPayload: SlockFilePreviewPayload, trackProgress: Boolean): String? {
        val externalUrl =
            currentPayload.externalUrl?.takeIf { it.isNotBlank() }
                ?: currentPayload.threadAttachmentExternalUrl(threadApi)
                ?: return unableToDownloadAttachment
        val downloadId =
            if (trackProgress) {
                activeDownloadId ?: currentPayload.filePreviewDownloadId()
            } else {
                null
            }
        if (trackProgress) {
            activeDownloadId = downloadId
            bridge.log(
                "file_preview_download phase=url_resolved downloadIdHash=${downloadId.orEmpty().hashCode().toString(16)} " +
                    "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} " +
                    binaryDownloadUrlDiagnostics(externalUrl)
            )
        }
        val result =
            bridge.openAttachment(
                url = externalUrl,
                attachmentId = currentPayload.attachmentCacheId(),
                fileName = currentPayload.attachmentCacheFileName(),
                mimeType = currentPayload.externalMimeType,
                downloadId = downloadId,
                onProgress =
                    if (trackProgress) {
                        { progress ->
                            activeDownloadId?.let { expectedDownloadId ->
                                if (progress.downloadedBytes > 0L && firstBridgeDownloadId != expectedDownloadId) {
                                    firstBridgeDownloadId = expectedDownloadId
                                    bridge.log(
                                        "file_preview_download phase=first_bridge downloadIdHash=${expectedDownloadId.hashCode().toString(16)} " +
                                            "sequence=${progress.sequence} downloadedBytes=${progress.downloadedBytes} " +
                                            "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}"
                                    )
                                }
                                downloadProgress =
                                    reduceAttachmentDownloadProgress(
                                        current = downloadProgress,
                                        incoming = progress,
                                        expectedDownloadId = expectedDownloadId
                                    )
                            }
                        }
                    } else {
                        null
                    }
            )
        return when (result?.optString("status").orEmpty()) {
            "opened", "" -> {
                attachmentCacheState = result?.optBoolean("cached", true) ?: true
                activeDownloadId = null
                null
            }
            "cancelled" -> {
                activeDownloadId = null
                downloadStopped
            }
            "no_app" -> result?.optString("message").orEmpty().ifBlank { noAppCanOpenFile }
            "storage_failed" -> result?.optString("message").orEmpty().ifBlank { storageUnavailable }
            else -> result?.optString("message").orEmpty().ifBlank { unableToOpenAttachment }
        }
    }
    suspend fun savePayloadExternally(currentPayload: SlockFilePreviewPayload, trackProgress: Boolean): String? {
        val externalUrl =
            currentPayload.externalUrl?.takeIf { it.isNotBlank() }
                ?: currentPayload.threadAttachmentExternalUrl(threadApi)
                ?: return unableToDownloadAttachment
        val downloadId =
            if (trackProgress) {
                activeDownloadId ?: currentPayload.filePreviewDownloadId()
            } else {
                null
            }
        if (trackProgress) {
            activeDownloadId = downloadId
            bridge.log(
                "file_preview_download phase=url_resolved downloadIdHash=${downloadId.orEmpty().hashCode().toString(16)} " +
                    "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L} " +
                    binaryDownloadUrlDiagnostics(externalUrl)
            )
        }
        val result =
            bridge.saveAttachment(
                url = externalUrl,
                attachmentId = currentPayload.attachmentCacheId(),
                fileName = currentPayload.attachmentCacheFileName(),
                mimeType = currentPayload.externalMimeType,
                downloadId = downloadId,
                onProgress =
                    if (trackProgress) {
                        { progress ->
                            activeDownloadId?.let { expectedDownloadId ->
                                if (progress.downloadedBytes > 0L && firstBridgeDownloadId != expectedDownloadId) {
                                    firstBridgeDownloadId = expectedDownloadId
                                    bridge.log(
                                        "file_preview_download phase=first_bridge downloadIdHash=${expectedDownloadId.hashCode().toString(16)} " +
                                            "sequence=${progress.sequence} downloadedBytes=${progress.downloadedBytes} " +
                                            "elapsedMs=${activeDownloadStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L}"
                                    )
                                }
                                downloadProgress =
                                    reduceAttachmentDownloadProgress(
                                        current = downloadProgress,
                                        incoming = progress,
                                        expectedDownloadId = expectedDownloadId
                                    )
                            }
                        }
                    } else {
                        null
                    }
            )
        return when (result?.optString("status").orEmpty()) {
            "saved" -> {
                attachmentCacheState = result?.optBoolean("cached", attachmentCacheState) ?: attachmentCacheState
                activeDownloadId = null
                bridge.toast(result?.optString("message").orEmpty().ifBlank { downloadSaved })
                null
            }
            "cancelled" -> {
                activeDownloadId = null
                downloadStopped
            }
            "storage_failed" -> result?.optString("message").orEmpty().ifBlank { storageUnavailable }
            else -> result?.optString("message").orEmpty().ifBlank { unableToDownloadAttachment }
        }
    }
    fun requestExternalSave() {
        if (!canOpenWith || downloadStatus == "downloading" || downloadStatus == "paused") return
        // Saving a cached file still performs a potentially slow MediaStore export. Keep the
        // request observable so cache hits show Saving progress and remain cancellable/bounded.
        val trackProgress = true
        downloadMessage = ""
        if (trackProgress) {
            nextDownloadGeneration += 1L
            downloadStatus = "downloading"
            activeDownloadId = payload.filePreviewDownloadId(nextDownloadGeneration)
            activeDownloadStartedAt = TimeSource.Monotonic.markNow()
            firstBridgeDownloadId = null
            firstRenderedDownloadId = null
            downloadProgress = SlockFilePreviewInitialDownloadProgress.copy(downloadId = activeDownloadId.orEmpty())
        } else {
            downloadStatus = "idle"
            activeDownloadId = null
            downloadProgress = null
        }
        coroutineScope.launch {
            val error = savePayloadExternally(payload, trackProgress)
            if (error == null) {
                downloadStatus = "idle"
                downloadMessage = ""
                downloadProgress = null
                activeDownloadId = null
            } else {
                downloadStatus = if (error == downloadStopped) "idle" else "failed"
                downloadMessage = error
                downloadProgress = null
                activeDownloadId = null
                if (error != downloadStopped) {
                    bridge.toast(error)
                }
            }
        }
    }
    fun requestExternalOpen() {
        if (!canOpenWith || downloadStatus == "downloading" || downloadStatus == "paused") return
        val trackProgress = slockFilePreviewShouldTrackOpenProgress(attachmentCacheState)
        downloadMessage = ""
        if (trackProgress) {
            nextDownloadGeneration += 1L
            downloadStatus = "downloading"
            activeDownloadId = payload.filePreviewDownloadId(nextDownloadGeneration)
            activeDownloadStartedAt = TimeSource.Monotonic.markNow()
            firstBridgeDownloadId = null
            firstRenderedDownloadId = null
            downloadProgress = SlockFilePreviewInitialDownloadProgress.copy(downloadId = activeDownloadId.orEmpty())
        } else {
            downloadStatus = "idle"
            activeDownloadId = null
            downloadProgress = null
        }
        coroutineScope.launch {
            val error = openPayloadExternally(payload, trackProgress)
            if (error == null) {
                downloadStatus = "idle"
                downloadMessage = ""
                downloadProgress = null
                activeDownloadId = null
            } else {
                downloadStatus = if (error == downloadStopped) "idle" else "failed"
                downloadMessage = error
                downloadProgress = null
                activeDownloadId = null
                bridge.toast(error)
            }
        }
    }
    fun requestDownloadControl(action: String) {
        val downloadId = activeDownloadId ?: return
        if (!slockAttachmentDownloadControlAllowed(downloadProgress, action)) {
            // MediaStore export is cancellable but not resumable. Do not optimistically show a
            // fake Paused state while the native copy continues.
            return
        }
        when (action) {
            "pause" -> downloadStatus = "paused"
            "resume" -> downloadStatus = "downloading"
            "stop" -> {
                downloadStatus = "idle"
                downloadMessage = downloadStopped
            }
        }
        coroutineScope.launch {
            bridge.attachmentDownloadControl(
                downloadId = downloadId,
                expectedAttemptToken = downloadProgress?.attemptToken,
                action = action
            )
        }
    }
    fun requestDeleteDownload() {
        if (!canOpenWith || !attachmentCacheState || downloadStatus == "downloading" || downloadStatus == "paused") return
        coroutineScope.launch {
            val result =
                bridge.deleteAttachmentCache(
                    attachmentId = attachmentCacheId,
                    fileName = attachmentCacheFileName,
                    mimeType = payload.externalMimeType
                )
            val status = result?.optString("status").orEmpty()
            if (status == "ok" || status.isBlank()) {
                attachmentCacheState = false
                downloadStatus = "idle"
                downloadMessage = ""
                downloadProgress = null
                bridge.toast(downloadedFileDeleted)
            } else {
                val error = result?.optString("message").orEmpty().ifBlank { unableToDeleteDownload }
                bridge.toast(error)
            }
        }
    }
    fun selectImageGalleryIndex(index: Int) {
        payload.withImageGalleryIndex(index, imageGalleryResolutionState.resolvedPayloadByAttachmentId)?.let { nextPayload ->
            payload = nextPayload
            downloadStatus = "idle"
            downloadMessage = ""
            downloadProgress = null
            activeDownloadId = null
        }
    }
    fun postComposerImageResult(
        fileRef: String,
        previewUri: String?,
        actionId: String = payload.composerImageActionId,
        fileName: String = payload.composerImageFileName.ifBlank { payload.externalFileName.ifBlank { payload.name.ifBlank { "image.png" } } },
        mimeType: String = payload.composerImageMimeType.ifBlank { payload.externalMimeType.ifBlank { "image/png" } },
        sizeBytes: Long? = payload.composerImageSizeBytes,
        replaceAttachmentId: String = payload.composerImageReplaceAttachmentId,
        closePage: Boolean = true
    ) {
        if (actionId.isBlank() || fileRef.isBlank()) return
        eventBus.post(
            SLOCK_COMPOSER_IMAGE_EDIT_EVENT,
            JSONObject().apply {
                put("threadKey", payload.composerImageThreadKey)
                put("actionId", actionId)
                put("action", if (replaceAttachmentId.isBlank()) "complete" else "replace")
                put("fileRef", fileRef)
                put("previewUri", previewUri.orEmpty())
                put("fileName", fileName)
                put("mimeType", mimeType)
                sizeBytes?.takeIf { it > 0L }?.let { put("sizeBytes", it) }
                put("replaceAttachmentId", replaceAttachmentId)
            },
            crossProcess = true
        )
        if (closePage) {
            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
    }
    fun postComposerImageSelection() {
        val gallery = payload.imageGallery.takeIf { it.isNotEmpty() }
        if (gallery == null) {
            postComposerImageResult(
                fileRef = payload.composerImageFileRef,
                previewUri = payload.composerImagePreviewUri.ifBlank { payload.imageUrl }
            )
            return
        }
        gallery.forEachIndexed { index, image ->
            postComposerImageResult(
                fileRef = image.composerImageFileRef,
                previewUri = image.composerImagePreviewUri.ifBlank { image.thumbnailUrl.orEmpty() },
                actionId = "${payload.composerImageActionId}-${image.attachmentId.take(12)}-$index",
                fileName = image.name.ifBlank { "image.png" },
                mimeType = image.mimeType.ifBlank { "image/png" },
                sizeBytes = image.composerImageSizeBytes,
                replaceAttachmentId = "",
                closePage = false
            )
        }
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }
    fun requestComposerImageEditSave() {
        if (composerImageEditSaving) return
        val captureView = composerImageEditCaptureRef?.view
        if (captureView == null) {
            bridge.toast(imageEditorLoading)
            return
        }
        composerImageEditSaving = true
        captureView.toImage(DeclarativeBaseView.ImageType.FILE, 1) { result ->
            val code = result?.optInt("code", -1) ?: -1
            val fileRef = result?.optString("data").orEmpty()
            composerImageEditSaving = false
            if (code == 0 && fileRef.isNotBlank()) {
                val currentGalleryIndex = payload.imageGalleryIndex.coerceIn(0, payload.imageGallery.lastIndex.coerceAtLeast(0))
                val currentGalleryImage = payload.imageGallery.getOrNull(currentGalleryIndex)
                if (payload.composerImageCanComplete && currentGalleryImage != null) {
                    payload = payload.withComposerImageEditResult(fileRef)
                    composerImageEditMode = false
                } else {
                    postComposerImageResult(fileRef = fileRef, previewUri = fileRef)
                }
            } else {
                bridge.toast(result?.optString("message").orEmpty().ifBlank { imageEditorExportFailed })
            }
        }
    }
    SlockFilePreviewPageScaffold(
        title = if (composerImageEditMode) stringResource(MR.strings.file_preview_editor_title) else payload.previewHeaderTitle(),
        subtitle = if (composerImageEditMode) null else payload.previewHeaderSubtitle(),
        iconName = if (composerImageEditMode) SlockLucideIconName.Pencil else payload.previewIconName(),
        onBack = {
            if (composerImageEditMode && !composerImageEditSaving) {
                composerImageEditMode = false
            } else if (!composerImageEditMode) {
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        },
        contentPadding = 0.dp,
        trailingContent =
            if (composerImageEditMode) {
                {
                    SlockFilePreviewImageEditorHistoryActions(
                        state = composerImageEditorState,
                        saving = composerImageEditSaving,
                    )
                }
            } else {
                null
            },
        overflowMenuItems =
            if (composerImageEditMode) {
                emptyList()
            } else listOfNotNull(
                if (canOpenComments) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewCommentsActionId,
                        label = stringResource(MR.strings.file_preview_action_comments),
                        icon = SlockLucideIconName.MessageCircle
                    )
                } else {
                    null
                },
                if (canOpenWith) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewOpenWithActionId,
                        label = slockFilePreviewOverflowActionLabel(payload, attachmentCacheState, actionTerms),
                        icon = SlockLucideIconName.ExternalLink
                    )
                } else {
                    null
                },
                if (canOpenWith && attachmentCacheState) {
                    SlockRaftUiMenuItem(
                        id = SlockFilePreviewDeleteDownloadActionId,
                        label = stringResource(MR.strings.file_preview_action_delete_downloaded),
                        icon = SlockLucideIconName.Trash2
                    )
                } else {
                    null
                }
            ),
        onOverflowMenuSelect = { id ->
            when (id) {
                SlockFilePreviewCommentsActionId -> openAttachmentComments()
                SlockFilePreviewOpenWithActionId -> requestExternalOpen()
                SlockFilePreviewDeleteDownloadActionId -> requestDeleteDownload()
            }
        }
    ) {
        val downloadActive = downloadStatus == "downloading" || downloadStatus == "paused"
        if (composerImageEditMode) {
            SlockFilePreviewComposerImageEditor(
                payload = payload,
                state = composerImageEditorState,
                saving = composerImageEditSaving,
                onCaptureRef = { composerImageEditCaptureRef = it },
                onDone = ::requestComposerImageEditSave,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SlockFilePreviewPayloadContent(
                    payload = payload,
                    onOpenUrl = {},
                    downloadStatusLabel =
                        when (downloadStatus) {
                            "downloading", "paused" ->
                                slockFilePreviewLocalizedActiveDownloadStatusLabel(downloadProgress, downloadStatus)
                            "failed" -> downloadMessage.ifBlank { downloadFailed }
                            else -> null
                        },
                    downloadInProgress = downloadActive,
                    downloadPaused = downloadStatus == "paused",
                    downloadProgressPercent = downloadProgress?.percent ?: if (downloadActive) 0 else null,
                    downloadCached = attachmentCacheState,
                    onDownload =
                        if (canOpenWith) {
                            when (slockFilePreviewPrimaryAction(attachmentCacheState)) {
                                SlockFilePreviewPrimaryAction.Save -> ::requestExternalSave
                                SlockFilePreviewPrimaryAction.Open -> ::requestExternalOpen
                            }
                        } else {
                            null
                        },
                    onPauseDownload = { requestDownloadControl("pause") },
                    onResumeDownload = { requestDownloadControl("resume") },
                    onStopDownload = { requestDownloadControl("stop") },
                    onImageGalleryIndexChange = ::selectImageGalleryIndex,
                    modifier = Modifier.fillMaxSize()
                )
                SlockFilePreviewImageGalleryCacheKeepAlive(
                    sources =
                        payload.imageGalleryKeepAliveSources(
                            imageGalleryResolutionState.resolvedPayloadByAttachmentId
                        )
                )
            }
            SlockFilePreviewComposerImageActionBar(
                payload = payload,
                enabled = !downloadActive,
                onEdit = { composerImageEditMode = true },
                onComplete = ::postComposerImageSelection
            )
        }
        attachmentCommentsState?.let { state ->
            SlockFilePreviewAttachmentCommentsPanel(
                state = state,
                onClose = { attachmentCommentsState = null },
                onFilterChange = { filter ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.copy(filter = filter)
                },
                onDraftChange = { draft ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.copy(draft = draft, errorCode = null)
                },
                onRetry = ::reloadAttachmentComments,
                onSend = ::sendAttachmentComment,
                onResolve = ::resolveAttachmentComment,
                onQuote = { comment ->
                    attachmentCommentsState =
                        attachmentCommentsState
                            ?.takeIf { it.attachment.id == state.attachment.id }
                            ?.let { current ->
                                current.copy(
                                    draft = appendThreadQuoteToComposer(current.draft, formatThreadMessageQuote(comment.content)),
                                    pendingQuoteLabel = comment.senderName,
                                    pendingAnchorLabel = comment.anchorLabel ?: current.attachment.name,
                                    errorCode = null
                                )
                            }
                }
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewAttachmentCommentsPanel(
    state: ThreadAttachmentCommentsDialogState,
    onClose: () -> Unit,
    onFilterChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
    onResolve: (ThreadAttachmentCommentItem) -> Unit,
    onQuote: (ThreadAttachmentCommentItem) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(MR.strings.thread_comments_title),
                style = SlockType.Input.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.X,
                onClick = onClose,
                size = 28.dp,
                shadowOffset = 2.dp
            )
        }
        ThreadAttachmentCommentsContent(
            state = state,
            onFilterChange = onFilterChange,
            onDraftChange = onDraftChange,
            onRetry = onRetry,
            onSend = onSend,
            onResolve = onResolve,
            onQuote = onQuote,
            containerTapRequestsFocus = true,
            compact = true
        )
    }
}

@Composable
private fun SlockFilePreviewComposerImageActionBar(
    payload: SlockFilePreviewPayload,
    enabled: Boolean,
    onEdit: () -> Unit,
    onComplete: () -> Unit
) {
    if (payload.composerImageActionId.isBlank()) return
    if (!payload.composerImageCanEdit && !payload.composerImageCanComplete) return
    val bottomSafeArea = slockBottomSafeAreaPadding()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(
                    start = SlockSpacing.Md,
                    top = SlockSpacing.Sm,
                    end = SlockSpacing.Md,
                    bottom = SlockSpacing.Sm + bottomSafeArea,
                ),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        slockFilePreviewComposerImageActionSlots(
            canEdit = payload.composerImageCanEdit,
            canComplete = payload.composerImageCanComplete,
        ).forEach { slot ->
            when (slot) {
                SlockFilePreviewComposerImageActionSlot.Edit ->
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_edit),
                        onClick = onEdit,
                        enabled = enabled && payload.imageUrl != null,
                        leadingIcon = SlockLucideIconName.Pencil,
                        leadingIconSize = 14.dp,
                        contentSpacing = 8.dp,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        shadowOffset = 2.dp,
                        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp)
                    )
                SlockFilePreviewComposerImageActionSlot.FlexibleSpacer ->
                    Box(modifier = Modifier.weight(1f))
                SlockFilePreviewComposerImageActionSlot.Done ->
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_done),
                        onClick = onComplete,
                        enabled = enabled && payload.canCompleteComposerImageSelection(),
                        leadingIcon = SlockLucideIconName.Check,
                        leadingIconSize = 14.dp,
                        contentSpacing = 8.dp,
                        backgroundColor = SlockColor.BrandYellow,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        shadowOffset = 2.dp,
                        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp)
                    )
            }
        }
    }
}

internal enum class SlockFilePreviewComposerImageActionSlot {
    Edit,
    FlexibleSpacer,
    Done,
}

internal fun slockFilePreviewComposerImageActionSlots(
    canEdit: Boolean,
    canComplete: Boolean,
): List<SlockFilePreviewComposerImageActionSlot> =
    buildList {
        if (canEdit) add(SlockFilePreviewComposerImageActionSlot.Edit)
        add(SlockFilePreviewComposerImageActionSlot.FlexibleSpacer)
        if (canComplete) add(SlockFilePreviewComposerImageActionSlot.Done)
    }

internal enum class SlockComposerImageEditorTool {
    Pencil,
    Text,
    Mosaic,
}

private enum class SlockComposerImagePencilMode {
    Ink,
    Eraser,
}

private val SlockComposerImageEditorDefaultPenColor = Color(0xFFFF3B30)
private val SlockComposerImageEditorPenColors =
    listOf(
        Color(0xFFFFFFFF),
        Color(0xFF111111),
        SlockComposerImageEditorDefaultPenColor,
        Color(0xFFFF9500),
        Color(0xFFFFCC00),
        Color(0xFF34C759),
        Color(0xFF32ADE6),
    )
private const val SlockComposerImageMosaicTileSizePx = 18f
private const val SlockComposerImageMosaicBrushRadius = 1

@Composable
private fun SlockFilePreviewImageEditorDoneButton(
    saving: Boolean,
    imageAvailable: Boolean,
    onDone: () -> Unit,
) {
    SlockBrutalActionButton(
        label =
            if (saving) {
                stringResource(MR.strings.file_preview_editor_saving)
            } else {
                stringResource(MR.strings.common_done)
            },
        onClick = onDone,
        enabled = !saving && imageAvailable,
        leadingIcon = if (saving) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Check,
        leadingIconSize = 14.dp,
        contentSpacing = 8.dp,
        backgroundColor = SlockColor.BrandYellow,
        minWidth = 0.dp,
        minHeight = 32.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 14.sp),
    )
}

internal data class SlockComposerImageTextAnnotation(
    val id: Int,
    val text: String,
    val position: Offset
)

internal data class SlockComposerImageStroke(
    val points: List<Offset>,
    val color: Color = Color(0xFFFF3B30),
)

internal data class SlockComposerImageMosaicTile(
    val column: Int,
    val row: Int,
)

internal data class SlockComposerImageMosaicSample(
    val sourceX: Int,
    val sourceY: Int,
    val destinationX: Int,
    val destinationY: Int,
    val destinationWidth: Int,
    val destinationHeight: Int,
)

internal sealed interface SlockComposerImageDrawOperation {
    data class Mosaic(val tiles: Set<SlockComposerImageMosaicTile>) : SlockComposerImageDrawOperation

    data class Pencil(val stroke: SlockComposerImageStroke) : SlockComposerImageDrawOperation
}

internal data class SlockComposerImageEditorContent(
    val strokes: List<SlockComposerImageStroke> = emptyList(),
    val mosaicTiles: Set<SlockComposerImageMosaicTile> = emptySet(),
    val textAnnotations: List<SlockComposerImageTextAnnotation> = emptyList(),
    val drawOperations: List<SlockComposerImageDrawOperation> = emptyList(),
)

internal data class SlockComposerImageEditorHistory(
    val content: SlockComposerImageEditorContent = SlockComposerImageEditorContent(),
    val undoStack: List<SlockComposerImageEditorContent> = emptyList(),
    val redoStack: List<SlockComposerImageEditorContent> = emptyList(),
) {
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    fun apply(nextContent: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (nextContent == content) {
            this
        } else {
            copy(content = nextContent, undoStack = undoStack + content, redoStack = emptyList())
        }

    fun replaceWithoutHistory(nextContent: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (nextContent == content) this else copy(content = nextContent)

    fun commitTransaction(origin: SlockComposerImageEditorContent): SlockComposerImageEditorHistory =
        if (origin == content) this else copy(undoStack = undoStack + origin, redoStack = emptyList())

    fun undo(): SlockComposerImageEditorHistory {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(content = previous, undoStack = undoStack.dropLast(1), redoStack = redoStack + content)
    }

    fun redo(): SlockComposerImageEditorHistory {
        val next = redoStack.lastOrNull() ?: return this
        return copy(content = next, undoStack = undoStack + content, redoStack = redoStack.dropLast(1))
    }
}

private class SlockComposerImageEditorState(
    initialHistory: SlockComposerImageEditorHistory = SlockComposerImageEditorHistory(),
) {
    var history by mutableStateOf(initialHistory)
        private set

    fun update(nextHistory: SlockComposerImageEditorHistory) {
        history = nextHistory
    }

    fun undo() {
        history = history.undo()
    }

    fun redo() {
        history = history.redo()
    }
}

@Composable
private fun SlockFilePreviewImageEditorHistoryActions(
    state: SlockComposerImageEditorState,
    saving: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Undo2,
            label = stringResource(MR.strings.common_undo),
            onClick = state::undo,
            enabled = !saving && state.history.canUndo,
            size = 24.dp,
            iconSize = 17.dp,
        )
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Redo2,
            label = stringResource(MR.strings.common_redo),
            onClick = state::redo,
            enabled = !saving && state.history.canRedo,
            size = 24.dp,
            iconSize = 17.dp,
        )
        Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
    }
}

@Composable
private fun SlockFilePreviewComposerImageEditor(
    payload: SlockFilePreviewPayload,
    state: SlockComposerImageEditorState,
    saving: Boolean,
    onCaptureRef: (ViewRef<DeclarativeBaseView<*, *>>) -> Unit,
    onDone: () -> Unit,
    initialTool: SlockComposerImageEditorTool = SlockComposerImageEditorTool.Pencil,
    initialPencilOptionsExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeStrokePoints = remember(payload.imageUrl) { mutableListOf<Offset>() }
    var activeStrokeRevision by remember(payload.imageUrl) { mutableStateOf(0) }
    val activeMosaicTiles = remember(payload.imageUrl) { mutableSetOf<SlockComposerImageMosaicTile>() }
    var activeMosaicRevision by remember(payload.imageUrl) { mutableStateOf(0) }
    var eraseGestureOrigin by remember(payload.imageUrl) { mutableStateOf<SlockComposerImageEditorContent?>(null) }
    var textGestureOrigin by remember(payload.imageUrl) { mutableStateOf<SlockComposerImageEditorContent?>(null) }
    var activeTextAnnotationId by remember(payload.imageUrl) { mutableStateOf<Int?>(null) }
    var textDraft by remember(payload.imageUrl) { mutableStateOf("") }
    var textPosition by remember(payload.imageUrl) { mutableStateOf(Offset(32f, 32f)) }
    var editorCanvasSize by remember(payload.imageUrl) { mutableStateOf(IntSize(0, 0)) }
    val textAnnotationSizes = remember(payload.imageUrl) { mutableStateMapOf<Int, IntSize>() }
    var nextTextAnnotationId by remember(payload.imageUrl) { mutableStateOf(0) }
    var tool by remember(payload.imageUrl, initialTool) { mutableStateOf(initialTool) }
    var pencilMode by remember(payload.imageUrl) { mutableStateOf(SlockComposerImagePencilMode.Ink) }
    var selectedPenColor by remember(payload.imageUrl) { mutableStateOf(SlockComposerImageEditorDefaultPenColor) }
    var pencilOptionsExpanded by remember(payload.imageUrl, initialPencilOptionsExpanded) {
        mutableStateOf(initialPencilOptionsExpanded)
    }
    var keyboardInsetDp by remember(payload.imageUrl) { mutableStateOf(0f) }
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val keyboardInsetExcludesBottomSafeArea = slockKeyboardInsetExcludesBottomSafeArea()
    val mosaicImage = rememberAsyncImageBitmap(payload.imageUrl)
    val focusManager = LocalFocusManager.current
    val editorDensity = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    fun selectTool(nextTool: SlockComposerImageEditorTool) {
        if (tool == SlockComposerImageEditorTool.Text && nextTool != SlockComposerImageEditorTool.Text) {
            focusManager.clearFocus(force = true)
            keyboardInsetDp = 0f
        }
        pencilOptionsExpanded =
            slockFilePreviewPencilOptionsExpandedAfterToolSelection(
                currentTool = tool,
                selectedTool = nextTool,
                currentlyExpanded = pencilOptionsExpanded,
            )
        tool = nextTool
    }
    fun erasedContentAt(point: Offset): SlockComposerImageEditorContent {
        val content = state.history.content
        val nextStrokes = slockComposerImageEraseStrokes(content.strokes, point)
        val shouldClearActiveStroke = slockComposerImageStrokeHit(activeStrokePoints, point)
        val nextMosaicTiles = slockComposerImageEraseMosaicTiles(content.mosaicTiles, point)
        val nextActiveMosaicTiles = slockComposerImageEraseMosaicTiles(activeMosaicTiles, point)
        val nextTexts =
            slockComposerImageEraseTextAnnotations(
                annotations = content.textAnnotations,
                point = point,
                layoutSizes = textAnnotationSizes,
                densityScale = editorDensity.density,
                fontScale = editorDensity.fontScale,
            )
        if (shouldClearActiveStroke) {
            activeStrokePoints.clear()
            activeStrokeRevision += 1
        }
        if (nextActiveMosaicTiles != activeMosaicTiles) {
            activeMosaicTiles.clear()
            activeMosaicTiles.addAll(nextActiveMosaicTiles)
            activeMosaicRevision += 1
        }
        return content
            .copy(
                strokes = nextStrokes,
                mosaicTiles = nextMosaicTiles,
                textAnnotations = nextTexts,
            )
            .slockComposerImageRetainDrawOperations(
                strokes = nextStrokes,
                mosaicTiles = nextMosaicTiles,
            )
    }
    fun eraseAt(point: Offset, recordHistory: Boolean) {
        val nextContent = erasedContentAt(point)
        state.update(
            if (recordHistory) {
                state.history.apply(nextContent)
            } else {
                state.history.replaceWithoutHistory(nextContent)
            }
        )
    }
    fun addTextAnnotation(): Boolean {
        val nextText = textDraft.trim()
        if (nextText.isBlank()) return false
        val nextContent =
            slockComposerImageAddTextDraft(
                content = state.history.content,
                draft = nextText,
                position =
                    slockComposerImageClampTextPosition(
                        position = textPosition,
                        annotationSize =
                            slockComposerImageTextFallbackSize(
                                text = nextText,
                                densityScale = editorDensity.density,
                                fontScale = editorDensity.fontScale,
                            ),
                        canvasSize = editorCanvasSize,
                    ),
                annotationId = nextTextAnnotationId,
            )
        state.update(
            state.history.apply(nextContent)
        )
        nextTextAnnotationId += 1
        textDraft = ""
        return true
    }
    fun requestDone() {
        if (!addTextAnnotation()) {
            onDone()
            return
        }
        coroutineScope.launch {
            withFrameNanos { _ -> }
            onDone()
        }
    }
    val editorBottomPadding =
        slockFilePreviewEditorBottomPadding(
            keyboardInsetDp = keyboardInsetDp,
            bottomSafeArea = bottomSafeArea,
            keyboardInsetExcludesBottomSafeArea = keyboardInsetExcludesBottomSafeArea,
        )
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF000000))
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .onGloballyPositioned { coordinates -> editorCanvasSize = coordinates.size }
                    .nativeRef { ref -> onCaptureRef(ref) }
                    .pointerInput(payload.imageUrl, tool, pencilMode, selectedPenColor) {
                        detectTapGestures { position ->
                            when (tool) {
                                SlockComposerImageEditorTool.Pencil ->
                                    if (pencilMode == SlockComposerImagePencilMode.Eraser) {
                                        eraseAt(position, recordHistory = true)
                                    } else {
                                        state.update(
                                            state.history.apply(
                                                state.history.content.slockComposerImageAppendStroke(
                                                    SlockComposerImageStroke(
                                                        points = listOf(position, position),
                                                        color = selectedPenColor,
                                                    )
                                                )
                                            )
                                        )
                                    }
                                SlockComposerImageEditorTool.Text -> textPosition = position
                                SlockComposerImageEditorTool.Mosaic -> {
                                    state.update(
                                        slockComposerImageCommitMosaicGesture(
                                            history = state.history,
                                            tiles = slockComposerImageMosaicTilesAt(position),
                                        )
                                    )
                                }
                            }
                        }
                    }
                    .pointerInput(payload.imageUrl, tool, pencilMode, selectedPenColor) {
                        if (tool == SlockComposerImageEditorTool.Text) return@pointerInput
                        detectDragGestures(
                            onDragStart = { start ->
                                when (tool) {
                                    SlockComposerImageEditorTool.Pencil ->
                                        if (pencilMode == SlockComposerImagePencilMode.Ink) {
                                            activeStrokePoints.clear()
                                            activeStrokePoints.add(start)
                                            activeStrokeRevision += 1
                                        } else {
                                            eraseGestureOrigin = state.history.content
                                            eraseAt(start, recordHistory = false)
                                        }
                                    SlockComposerImageEditorTool.Text -> {
                                        activeTextAnnotationId = null
                                        textPosition = start
                                    }
                                    SlockComposerImageEditorTool.Mosaic -> {
                                        activeMosaicTiles.clear()
                                        slockComposerImageAccumulateMosaicTiles(activeMosaicTiles, start)
                                        activeMosaicRevision += 1
                                    }
                                }
                            },
                            onDragEnd = {
                                when {
                                    tool == SlockComposerImageEditorTool.Pencil &&
                                        pencilMode == SlockComposerImagePencilMode.Ink &&
                                        activeStrokePoints.isNotEmpty() -> {
                                        val committedPoints =
                                            if (activeStrokePoints.size == 1) {
                                                listOf(activeStrokePoints.first(), activeStrokePoints.first())
                                            } else {
                                                activeStrokePoints.toList()
                                            }
                                        state.update(
                                            state.history.apply(
                                                state.history.content.slockComposerImageAppendStroke(
                                                    SlockComposerImageStroke(
                                                        points = committedPoints,
                                                        color = selectedPenColor,
                                                    )
                                                )
                                            )
                                        )
                                    }
                                    tool == SlockComposerImageEditorTool.Pencil &&
                                        pencilMode == SlockComposerImagePencilMode.Eraser ->
                                        eraseGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                    tool == SlockComposerImageEditorTool.Mosaic && activeMosaicTiles.isNotEmpty() ->
                                        state.update(
                                            slockComposerImageCommitMosaicGesture(
                                                history = state.history,
                                                tiles = activeMosaicTiles,
                                            )
                                        )
                                    tool == SlockComposerImageEditorTool.Text && activeTextAnnotationId != null ->
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                }
                                eraseGestureOrigin = null
                                textGestureOrigin = null
                                activeTextAnnotationId = null
                                activeStrokePoints.clear()
                                activeStrokeRevision += 1
                                if (activeMosaicTiles.isNotEmpty()) {
                                    activeMosaicTiles.clear()
                                    activeMosaicRevision += 1
                                }
                            },
                            onDragCancel = {
                                eraseGestureOrigin?.let { origin ->
                                    state.update(state.history.replaceWithoutHistory(origin))
                                }
                                textGestureOrigin?.let { origin ->
                                    state.update(state.history.replaceWithoutHistory(origin))
                                }
                                eraseGestureOrigin = null
                                textGestureOrigin = null
                                activeTextAnnotationId = null
                                activeStrokePoints.clear()
                                activeStrokeRevision += 1
                                if (activeMosaicTiles.isNotEmpty()) {
                                    activeMosaicTiles.clear()
                                    activeMosaicRevision += 1
                                }
                            }
                        ) { change, _ ->
                            when (tool) {
                                SlockComposerImageEditorTool.Pencil ->
                                    if (pencilMode == SlockComposerImagePencilMode.Ink) {
                                        activeStrokePoints.add(change.position)
                                        activeStrokeRevision += 1
                                    } else {
                                        eraseAt(change.position, recordHistory = false)
                                    }
                                SlockComposerImageEditorTool.Text -> textPosition = change.position
                                SlockComposerImageEditorTool.Mosaic ->
                                    if (slockComposerImageAccumulateMosaicTiles(activeMosaicTiles, change.position)) {
                                        activeMosaicRevision += 1
                                    }
                            }
                        }
                    },
            contentAlignment = Alignment.Center
        ) {
            payload.imageUrl?.let { source ->
                SlockAspectFitImagePreviewSurface(
                    source = source,
                    contentDescription = payload.name.ifBlank { payload.path },
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color(0xFF000000),
                    borderWidth = 0.dp,
                    zoomable = false,
                    showLoadingIndicator = true
                )
            }
            val committedContent = state.history.content
            key(committedContent) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Kuikly's native Canvas is retained across ordinary snapshot invalidations.
                    // Key the committed surface by immutable editor content so Undo/Redo disposes
                    // the old native node, starts from a blank surface, and replays only the current
                    // operation stack. This mirrors Android View.invalidate() + onDraw() replay.
                    committedContent.slockComposerImageResolvedDrawOperations().forEach { operation ->
                        when (operation) {
                            is SlockComposerImageDrawOperation.Mosaic ->
                                drawSlockComposerImageMosaic(
                                    image = mosaicImage,
                                    tiles = operation.tiles,
                                )
                            is SlockComposerImageDrawOperation.Pencil ->
                                operation.stroke.points.windowed(2).forEach { segment ->
                                    drawLine(
                                        color = operation.stroke.color,
                                        start = segment[0],
                                        end = segment[1],
                                        strokeWidth = 6f,
                                        cap = StrokeCap.Round
                                    )
                                }
                        }
                    }
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                activeMosaicRevision
                activeStrokeRevision
                drawSlockComposerImageMosaic(
                    image = mosaicImage,
                    tiles =
                        slockComposerImageActiveMosaicTiles(
                            touchedTiles = activeMosaicTiles,
                            committedTiles = state.history.content.mosaicTiles,
                        ),
                )
                activeStrokePoints.windowed(2).forEach { segment ->
                    drawLine(
                        color = selectedPenColor,
                        start = segment[0],
                        end = segment[1],
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            state.history.content.textAnnotations.forEach { annotation ->
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    x = annotation.position.x.roundToInt(),
                                    y = annotation.position.y.roundToInt(),
                                )
                            }
                            .widthIn(min = 48.dp, max = 260.dp)
                            .onGloballyPositioned { coordinates ->
                                val actualSize = coordinates.size
                                textAnnotationSizes[annotation.id] = actualSize
                                val clampedPosition =
                                    slockComposerImageClampTextPosition(
                                        position = annotation.position,
                                        annotationSize = actualSize,
                                        canvasSize = editorCanvasSize,
                                    )
                                if (clampedPosition != annotation.position) {
                                    state.update(
                                        state.history.replaceWithoutHistory(
                                            slockComposerImageMoveTextAnnotation(
                                                content = state.history.content,
                                                annotationId = annotation.id,
                                                position = clampedPosition,
                                            )
                                        )
                                    )
                                }
                            }
                            .pointerInput(payload.imageUrl, annotation.id, tool) {
                                if (tool != SlockComposerImageEditorTool.Text) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        textGestureOrigin = state.history.content
                                        activeTextAnnotationId = annotation.id
                                    },
                                    onDragEnd = {
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.commitTransaction(origin))
                                        }
                                        textGestureOrigin = null
                                        activeTextAnnotationId = null
                                    },
                                    onDragCancel = {
                                        textGestureOrigin?.let { origin ->
                                            state.update(state.history.replaceWithoutHistory(origin))
                                        }
                                        textGestureOrigin = null
                                        activeTextAnnotationId = null
                                    },
                                ) { _, dragAmount ->
                                    val annotationId = activeTextAnnotationId ?: return@detectDragGestures
                                    val current =
                                        state.history.content.textAnnotations.firstOrNull { it.id == annotationId }
                                            ?: return@detectDragGestures
                                    val nextPosition = current.position + dragAmount
                                    state.update(
                                        state.history.replaceWithoutHistory(
                                            slockComposerImageMoveTextAnnotation(
                                                content = state.history.content,
                                                annotationId = annotationId,
                                                position = slockComposerImageClampTextPosition(
                                                    position = nextPosition,
                                                    annotationSize =
                                                        textAnnotationSizes[annotationId]
                                                            ?: slockComposerImageTextFallbackSize(
                                                                text = current.text,
                                                                densityScale = editorDensity.density,
                                                                fontScale = editorDensity.fontScale,
                                                            ),
                                                    canvasSize = editorCanvasSize,
                                                ),
                                            )
                                        )
                                    )
                                }
                            }
                            .background(Color(0xCCFFFFFF))
                            .border(1.dp, SlockColor.BorderBlack)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = annotation.text,
                        style = SlockType.ButtonBold.copy(fontSize = 20.sp, lineHeight = 24.sp, color = Color(0xFFFF3B30)),
                        color = Color(0xFFFF3B30),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (tool == SlockComposerImageEditorTool.Pencil && pencilOptionsExpanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = true,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                SlockFilePreviewPencilOptions(
                    mode = pencilMode,
                    selectedColor = selectedPenColor,
                    enabled = !saving,
                    onSelectEraser = { pencilMode = SlockComposerImagePencilMode.Eraser },
                    onSelectColor = { color ->
                        selectedPenColor = color
                        pencilMode = SlockComposerImagePencilMode.Ink
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                top = 6.dp,
                                end = SlockSpacing.Sm,
                                bottom = SlockSpacing.Sm,
                            ),
                )
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        } else if (tool == SlockComposerImageEditorTool.Text) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = true,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Sm,
                                end = SlockSpacing.Sm,
                                bottom = SlockSpacing.Sm,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = textDraft,
                        onValueChange = { textDraft = it.take(80) },
                        placeholder = stringResource(MR.strings.file_preview_placeholder_tap_to_add_text),
                        placeholderColor = SlockColor.TextMutedGray,
                        autoFocus = true,
                        keyboardHeightChange = { params ->
                            keyboardInsetDp = params.height.coerceAtLeast(0f)
                        },
                        maxLines = 1,
                        textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp)
                                .background(SlockColor.SurfaceCream)
                                .border(SlockStroke.Default, SlockColor.BorderBlack)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Check,
                        contentDescription = stringResource(MR.strings.common_add),
                        onClick = { addTextAnnotation() },
                        enabled = !saving && textDraft.isNotBlank(),
                        backgroundColor = SlockColor.BrandYellow,
                        shadowOffset = 2.dp
                    )
                    Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
                }
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            slockFilePreviewEditorControlsHeight(
                                secondaryControlsVisible = false,
                                bottomPadding = editorBottomPadding,
                            )
                        )
                        .background(SlockColor.SurfaceWhite)
            ) {
                SlockFilePreviewImageEditorPrimaryControls(
                    tool = tool,
                    saving = saving,
                    imageAvailable = payload.imageUrl != null,
                    onSelectTool = ::selectTool,
                    onDone = ::requestDone,
                )
                Box(modifier = Modifier.fillMaxWidth().height(editorBottomPadding))
            }
        }
    }
}

@Composable
private fun SlockFilePreviewImageEditorPrimaryControls(
    tool: SlockComposerImageEditorTool,
    saving: Boolean,
    imageAvailable: Boolean,
    onSelectTool: (SlockComposerImageEditorTool) -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = SlockSpacing.Sm,
                    top = 6.dp,
                    end = SlockSpacing.Sm,
                ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_pen),
            icon = SlockLucideIconName.Pencil,
            selected = tool == SlockComposerImageEditorTool.Pencil,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Pencil) }
        )
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_text),
            icon = SlockLucideIconName.Type,
            selected = tool == SlockComposerImageEditorTool.Text,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Text) }
        )
        SlockFilePreviewEditorToolButton(
            label = stringResource(MR.strings.file_preview_toolbar_mosaic),
            icon = SlockLucideIconName.Grid3x3,
            selected = tool == SlockComposerImageEditorTool.Mosaic,
            enabled = !saving,
            onClick = { onSelectTool(SlockComposerImageEditorTool.Mosaic) }
        )
        Box(modifier = Modifier.weight(1f))
        SlockFilePreviewImageEditorDoneButton(
            saving = saving,
            imageAvailable = imageAvailable,
            onDone = onDone,
        )
        Box(modifier = Modifier.size(width = 12.dp, height = 1.dp))
    }
}

@Composable
private fun SlockFilePreviewPencilOptions(
    mode: SlockComposerImagePencilMode,
    selectedColor: Color,
    enabled: Boolean,
    onSelectEraser: () -> Unit,
    onSelectColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val penLabel = stringResource(MR.strings.file_preview_toolbar_pen)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SlockFilePreviewBorderlessIconButton(
            icon = SlockLucideIconName.Eraser,
            label = stringResource(MR.strings.file_preview_toolbar_erase),
            onClick = onSelectEraser,
            enabled = enabled,
            iconColor = slockFilePreviewEditorToolIconColor(mode == SlockComposerImagePencilMode.Eraser),
            size = SlockSize.IconButton,
            iconSize = 22.dp,
        )
        Box(
            modifier =
                Modifier
                    .size(width = 1.dp, height = 28.dp)
                    .background(SlockColor.TextMutedGray.copy(alpha = 0.45f))
        )
        SlockComposerImageEditorPenColors.forEachIndexed { index, color ->
            SlockFilePreviewColorSwatch(
                color = color,
                selected = mode == SlockComposerImagePencilMode.Ink && selectedColor == color,
                enabled = enabled,
                label = "$penLabel ${index + 1}",
                onClick = { onSelectColor(color) },
            )
        }
    }
}

@Composable
private fun SlockFilePreviewColorSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = false,
        pressOffset = 0.dp,
        modifier =
            Modifier
                .size(28.dp)
                .semantics { contentDescription = label },
    ) {
        Box(
            modifier =
                Modifier
                    .size(if (selected) 26.dp else 22.dp)
                    .borderRadius(20.dp)
                    .background(color)
                    .border(
                        width = if (selected) SlockStroke.Strong else SlockStroke.Thin,
                        color = SlockColor.BorderBlack,
                    )
        )
    }
}

@Composable
private fun SlockFilePreviewEditorToolButton(
    label: String,
    icon: SlockLucideIconName,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SlockFilePreviewBorderlessIconButton(
        icon = icon,
        label = label,
        onClick = onClick,
        enabled = enabled,
        iconColor = slockFilePreviewEditorToolIconColor(selected = selected),
        size = 40.dp,
        iconSize = 21.dp,
        modifier = modifier
    )
}

@Composable
private fun SlockFilePreviewBorderlessIconButton(
    icon: SlockLucideIconName,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    iconColor: String = SlockColor.TextBlackArgb,
    size: com.tencent.kuikly.compose.ui.unit.Dp = SlockSize.IconButton,
    iconSize: com.tencent.kuikly.compose.ui.unit.Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        pressOffset = 0.dp,
        modifier =
            modifier
                .size(size)
                .semantics { contentDescription = label },
    ) {
        SlockLucideIcon(
            name = icon,
            color = if (enabled) iconColor else SlockColor.TextMutedGrayArgb,
            modifier = Modifier.size(iconSize),
        )
    }
}

internal fun slockFilePreviewEditorToolIconColor(selected: Boolean): String =
    if (selected) SlockColor.AccentRedArgb else SlockColor.TextBlackArgb

internal fun slockFilePreviewPencilOptionsExpandedAfterToolSelection(
    currentTool: SlockComposerImageEditorTool,
    selectedTool: SlockComposerImageEditorTool,
    currentlyExpanded: Boolean,
): Boolean =
    when (selectedTool) {
        SlockComposerImageEditorTool.Pencil ->
            if (currentTool == SlockComposerImageEditorTool.Pencil) {
                !currentlyExpanded
            } else {
                true
            }
        SlockComposerImageEditorTool.Text,
        SlockComposerImageEditorTool.Mosaic -> false
    }

internal fun slockFilePreviewEditorControlsHeight(
    secondaryControlsVisible: Boolean,
    bottomPadding: com.tencent.kuikly.compose.ui.unit.Dp,
): com.tencent.kuikly.compose.ui.unit.Dp =
    6.dp +
        bottomPadding +
        SlockSize.IconButton +
        if (secondaryControlsVisible) SlockSpacing.Sm + SlockSize.IconButton else 0.dp

internal fun slockFilePreviewEditorBottomPadding(
    keyboardInsetDp: Float,
    bottomSafeArea: com.tencent.kuikly.compose.ui.unit.Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean,
): com.tencent.kuikly.compose.ui.unit.Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    val safeAreaInset =
        if (keyboardInsetDp > 0f && !keyboardInsetExcludesBottomSafeArea) {
            0.dp
        } else {
            bottomSafeArea
        }
    return 6.dp + keyboardInset + safeAreaInset
}

internal fun slockComposerImageTextFallbackSize(
    text: String,
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): IntSize {
    val charactersPerLine = 18
    val lineCount = ceil(text.length.coerceAtLeast(1) / charactersPerLine.toFloat()).toInt().coerceIn(1, 3)
    val safeDensity = densityScale.coerceAtLeast(0.1f)
    val safeFontScale = fontScale.coerceAtLeast(0.1f)
    val widthDp =
        if (text.length > charactersPerLine) {
            260f
        } else {
            (text.length.coerceAtLeast(1) * 13f + 18f).coerceIn(48f, 260f)
        }
    val heightPx = lineCount * 24f * safeDensity * safeFontScale + 10f * safeDensity
    return IntSize(
        width = (widthDp * safeDensity).roundToInt(),
        height = heightPx.roundToInt(),
    )
}

internal fun SlockComposerImageTextAnnotation.hitTest(
    point: Offset,
    layoutSize: IntSize = slockComposerImageTextFallbackSize(text),
): Boolean {
    val width = layoutSize.width.toFloat()
    val height = layoutSize.height.toFloat()
    return point.x >= position.x - 10f &&
        point.x <= position.x + width &&
        point.y >= position.y - 10f &&
        point.y <= position.y + height
}

internal fun slockComposerImageTopmostTextAnnotationAt(
    annotations: List<SlockComposerImageTextAnnotation>,
    point: Offset,
    layoutSizes: Map<Int, IntSize> = emptyMap(),
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): SlockComposerImageTextAnnotation? =
    annotations.lastOrNull { annotation ->
        annotation.hitTest(
            point = point,
            layoutSize =
                layoutSizes[annotation.id]
                    ?: slockComposerImageTextFallbackSize(
                        text = annotation.text,
                        densityScale = densityScale,
                        fontScale = fontScale,
                    ),
        )
    }

internal fun slockComposerImageEraseTextAnnotations(
    annotations: List<SlockComposerImageTextAnnotation>,
    point: Offset,
    layoutSizes: Map<Int, IntSize> = emptyMap(),
    densityScale: Float = 1f,
    fontScale: Float = 1f,
): List<SlockComposerImageTextAnnotation> =
    annotations.filterNot { annotation ->
        annotation.hitTest(
            point = point,
            layoutSize =
                layoutSizes[annotation.id]
                    ?: slockComposerImageTextFallbackSize(
                        text = annotation.text,
                        densityScale = densityScale,
                        fontScale = fontScale,
                    ),
        )
    }

internal fun slockComposerImageClampTextPosition(
    position: Offset,
    annotationSize: IntSize,
    canvasSize: IntSize,
): Offset {
    val maxX =
        if (canvasSize.width > 0) {
            (canvasSize.width - annotationSize.width).coerceAtLeast(0).toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
    val maxY =
        if (canvasSize.height > 0) {
            (canvasSize.height - annotationSize.height).coerceAtLeast(0).toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
    return Offset(
        x = position.x.coerceIn(0f, maxX),
        y = position.y.coerceIn(0f, maxY),
    )
}

internal fun slockComposerImageMoveTextAnnotation(
    content: SlockComposerImageEditorContent,
    annotationId: Int,
    position: Offset,
): SlockComposerImageEditorContent {
    var changed = false
    val nextAnnotations =
        content.textAnnotations.map { annotation ->
            if (annotation.id == annotationId && annotation.position != position) {
                changed = true
                annotation.copy(position = position)
            } else {
                annotation
            }
        }
    return if (changed) content.copy(textAnnotations = nextAnnotations) else content
}

internal fun slockComposerImageAddTextDraft(
    content: SlockComposerImageEditorContent,
    draft: String,
    position: Offset,
    annotationId: Int,
): SlockComposerImageEditorContent {
    val text = draft.trim()
    if (text.isEmpty()) return content
    return content.copy(
        textAnnotations =
            content.textAnnotations +
                SlockComposerImageTextAnnotation(
                    id = annotationId,
                    text = text,
                    position = position,
                )
    )
}

private const val SlockFilePreviewEditorVisualPreviewUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20390%20520%22%3E%3Crect%20width%3D%22390%22%20height%3D%22520%22%20fill%3D%22%23FDE047%22%2F%3E%3Ccircle%20cx%3D%22290%22%20cy%3D%22120%22%20r%3D%2270%22%20fill%3D%22%23F472B6%22%2F%3E%3Cpath%20d%3D%22M30%20460L135%20280l75%2090%2070-65%2080%20155H30z%22%20fill%3D%22%23000000%22%2F%3E%3C%2Fsvg%3E"

internal fun slockComposerImageEraseStrokes(
    strokes: List<SlockComposerImageStroke>,
    point: Offset,
): List<SlockComposerImageStroke> =
    strokes.filterNot { stroke -> slockComposerImageStrokeHit(stroke.points, point) }

internal fun slockComposerImageMosaicTilesAt(
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    brushRadius: Int = SlockComposerImageMosaicBrushRadius,
): Set<SlockComposerImageMosaicTile> {
    val tiles = mutableSetOf<SlockComposerImageMosaicTile>()
    slockComposerImageAccumulateMosaicTiles(
        target = tiles,
        point = point,
        tileSizePx = tileSizePx,
        brushRadius = brushRadius,
    )
    return tiles
}

internal fun slockComposerImageCommitMosaicGesture(
    history: SlockComposerImageEditorHistory,
    tiles: Collection<SlockComposerImageMosaicTile>,
): SlockComposerImageEditorHistory {
    if (tiles.isEmpty()) return history
    val committedTiles = tiles.toSet() - history.content.mosaicTiles
    if (committedTiles.isEmpty()) return history
    val nextTiles = history.content.mosaicTiles + committedTiles
    return history.apply(
        history.content.copy(
            mosaicTiles = nextTiles,
            drawOperations =
                history.content.slockComposerImageResolvedDrawOperations() +
                    SlockComposerImageDrawOperation.Mosaic(committedTiles),
        )
    )
}

internal fun slockComposerImageActiveMosaicTiles(
    touchedTiles: Set<SlockComposerImageMosaicTile>,
    committedTiles: Set<SlockComposerImageMosaicTile>,
): Set<SlockComposerImageMosaicTile> = touchedTiles - committedTiles

internal fun SlockComposerImageEditorContent.slockComposerImageAppendStroke(
    stroke: SlockComposerImageStroke,
): SlockComposerImageEditorContent =
    copy(
        strokes = strokes + stroke,
        drawOperations =
            slockComposerImageResolvedDrawOperations() +
                SlockComposerImageDrawOperation.Pencil(stroke),
    )

internal fun SlockComposerImageEditorContent.slockComposerImageResolvedDrawOperations():
    List<SlockComposerImageDrawOperation> =
    if (drawOperations.isNotEmpty()) {
        drawOperations
    } else {
        buildList {
            if (mosaicTiles.isNotEmpty()) add(SlockComposerImageDrawOperation.Mosaic(mosaicTiles))
            strokes.forEach { add(SlockComposerImageDrawOperation.Pencil(it)) }
        }
    }

internal fun SlockComposerImageEditorContent.slockComposerImageRetainDrawOperations(
    strokes: List<SlockComposerImageStroke>,
    mosaicTiles: Set<SlockComposerImageMosaicTile>,
): SlockComposerImageEditorContent {
    val retained =
        slockComposerImageResolvedDrawOperations().mapNotNull { operation ->
            when (operation) {
                is SlockComposerImageDrawOperation.Mosaic ->
                    (operation.tiles intersect mosaicTiles)
                        .takeIf { it.isNotEmpty() }
                        ?.let(SlockComposerImageDrawOperation::Mosaic)
                is SlockComposerImageDrawOperation.Pencil ->
                    operation.takeIf { operation.stroke in strokes }
            }
        }
    return copy(drawOperations = retained)
}

internal fun slockComposerImageAccumulateMosaicTiles(
    target: MutableSet<SlockComposerImageMosaicTile>,
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
    brushRadius: Int = SlockComposerImageMosaicBrushRadius,
): Boolean {
    if (tileSizePx <= 0f || brushRadius < 0) return false
    val centerColumn = floor(point.x / tileSizePx).toInt()
    val centerRow = floor(point.y / tileSizePx).toInt()
    var changed = false
    for (column in (centerColumn - brushRadius)..(centerColumn + brushRadius)) {
        for (row in (centerRow - brushRadius)..(centerRow + brushRadius)) {
            if (column >= 0 && row >= 0) {
                changed = target.add(SlockComposerImageMosaicTile(column = column, row = row)) || changed
            }
        }
    }
    return changed
}

internal fun slockComposerImageMosaicSample(
    tile: SlockComposerImageMosaicTile,
    imageWidth: Int,
    imageHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
): SlockComposerImageMosaicSample? {
    if (
        imageWidth <= 0 ||
        imageHeight <= 0 ||
        canvasWidth <= 0f ||
        canvasHeight <= 0f ||
        tileSizePx <= 0f
    ) {
        return null
    }
    val scale = min(canvasWidth / imageWidth.toFloat(), canvasHeight / imageHeight.toFloat())
    if (!scale.isFinite() || scale <= 0f) return null
    val renderedWidth = imageWidth * scale
    val renderedHeight = imageHeight * scale
    val renderedLeft = (canvasWidth - renderedWidth) / 2f
    val renderedTop = (canvasHeight - renderedHeight) / 2f
    val tileLeft = tile.column * tileSizePx
    val tileTop = tile.row * tileSizePx
    val destinationLeft = maxOf(tileLeft, renderedLeft)
    val destinationTop = maxOf(tileTop, renderedTop)
    val destinationRight = minOf(tileLeft + tileSizePx + 0.5f, renderedLeft + renderedWidth)
    val destinationBottom = minOf(tileTop + tileSizePx + 0.5f, renderedTop + renderedHeight)
    if (destinationRight <= destinationLeft || destinationBottom <= destinationTop) return null
    val sampleX =
        (((destinationLeft + destinationRight) / 2f - renderedLeft) / scale)
            .toInt()
            .coerceIn(0, imageWidth - 1)
    val sampleY =
        (((destinationTop + destinationBottom) / 2f - renderedTop) / scale)
            .toInt()
            .coerceIn(0, imageHeight - 1)
    val destinationX = floor(destinationLeft).toInt()
    val destinationY = floor(destinationTop).toInt()
    return SlockComposerImageMosaicSample(
        sourceX = sampleX,
        sourceY = sampleY,
        destinationX = destinationX,
        destinationY = destinationY,
        destinationWidth = (ceil(destinationRight).toInt() - destinationX).coerceAtLeast(1),
        destinationHeight = (ceil(destinationBottom).toInt() - destinationY).coerceAtLeast(1),
    )
}

private fun DrawScope.drawSlockComposerImageMosaic(
    image: ImageBitmap?,
    tiles: Collection<SlockComposerImageMosaicTile>,
) {
    val readyImage = image?.takeIf { it.width > 0 && it.height > 0 } ?: return
    tiles.forEach { tile ->
        val sample =
            slockComposerImageMosaicSample(
                tile = tile,
                imageWidth = readyImage.width,
                imageHeight = readyImage.height,
                canvasWidth = size.width,
                canvasHeight = size.height,
            ) ?: return@forEach
        drawImage(
            image = readyImage,
            srcOffset = IntOffset(sample.sourceX, sample.sourceY),
            srcSize = IntSize(1, 1),
            dstOffset = IntOffset(sample.destinationX, sample.destinationY),
            dstSize = IntSize(sample.destinationWidth, sample.destinationHeight),
        )
    }
}

internal fun slockComposerImageEraseMosaicTiles(
    tiles: Set<SlockComposerImageMosaicTile>,
    point: Offset,
    tileSizePx: Float = SlockComposerImageMosaicTileSizePx,
): Set<SlockComposerImageMosaicTile> {
    if (tiles.isEmpty() || tileSizePx <= 0f) return tiles
    val thresholdSquared = 24f * 24f
    return tiles.filterTo(mutableSetOf()) { tile ->
        val center =
            Offset(
                x = (tile.column + 0.5f) * tileSizePx,
                y = (tile.row + 0.5f) * tileSizePx,
            )
        center.slockDistanceSquaredTo(point) > thresholdSquared
    }
}

internal fun slockComposerImageStrokeHit(stroke: List<Offset>, point: Offset): Boolean {
    if (stroke.isEmpty()) return false
    val thresholdSquared = 22f * 22f
    return stroke.any { it.slockDistanceSquaredTo(point) <= thresholdSquared } ||
        stroke.windowed(2).any { segment ->
            slockDistanceToSegmentSquared(point = point, start = segment[0], end = segment[1]) <= thresholdSquared
        }
}

private fun Offset.slockDistanceSquaredTo(other: Offset): Float {
    val dx = x - other.x
    val dy = y - other.y
    return dx * dx + dy * dy
}

private fun slockDistanceToSegmentSquared(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return point.slockDistanceSquaredTo(start)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + t * dx, start.y + t * dy)
    return point.slockDistanceSquaredTo(projection)
}

internal data class FilePreviewLaunchPayloadTrace(
    val payload: SlockFilePreviewPayload,
    val responseBytes: Int,
    val durationMs: Long,
    val result: String
)

internal fun slockFilePreviewResolveLaunchPayload(
    source: String,
    loadPreviewPayload: (String) -> SlockFilePreviewPayload?
): FilePreviewLaunchPayloadTrace {
    val startedAt = TimeSource.Monotonic.markNow()
    val parsedLaunch =
        source
            .takeIf { it.isNotBlank() }
            ?.let {
                runCatching { SlockFilePreviewJson.decodeFromString(WireSlockFilePreviewLaunchPayload.serializer(), it) }
            }
    val previewId = parsedLaunch?.getOrNull()?.previewId.orEmpty()
    val resolvedPayload = previewId.takeIf { it.isNotBlank() }?.let(loadPreviewPayload)
    return FilePreviewLaunchPayloadTrace(
        payload =
            resolvedPayload ?: SlockFilePreviewPayload(
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                messageDesc = MR.strings.file_preview_error_load_payload.desc(),
                error = if (parsedLaunch?.isSuccess == true) "preview_not_found" else "payload_parse_failed"
            ),
        responseBytes = source.encodeToByteArray().size,
        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        result =
            when {
                resolvedPayload != null -> "success"
                parsedLaunch?.isSuccess == true -> "missing_preview"
                else -> "failure"
            }
    )
}

internal fun Pager.openSlockFilePreviewPage(payload: SlockFilePreviewPayload) {
    val previewId = SlockFilePreviewLaunchPayloadStore.put(payload)
    val pageData = slockFilePreviewLaunchPageData(previewId)
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SLOCK_FILE_PREVIEW_PAGE, pageData)
}

internal fun slockFilePreviewLaunchPageData(previewId: String): JSONObject =
    JSONObject().apply {
        put("pageName", SLOCK_FILE_PREVIEW_PAGE)
        put(
            "payload",
            SlockFilePreviewJson.encodeToString(
                WireSlockFilePreviewLaunchPayload.serializer(),
                WireSlockFilePreviewLaunchPayload(previewId = previewId)
            )
        )
    }

internal fun KuiklyThreadAttachmentItem.toThreadAttachmentLazyPreviewPayload(
    kind: String,
    imageGallery: List<KuiklyThreadAttachmentItem> = emptyList(),
    imageGalleryIndex: Int = 0,
    attachmentCommentsEnabled: Boolean = false
): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        requestedKind = kind,
        titleDesc = when (kind) {
            "image" -> MR.strings.file_preview_image.desc()
            "markdown" -> MR.strings.file_preview_markdown.desc()
            "text" -> MR.strings.file_preview_text.desc()
            "video" -> MR.strings.file_preview_video.desc()
            else -> MR.strings.file_preview_title.desc()
        },
        subtitle = name,
        name = name,
        path = name,
        meta = genericAttachmentMetaLine(),
        imageUrl =
            if (kind == "image") {
                thumbnailUrl?.takeIf { it.isNotBlank() }
            } else {
                null
            },
        messageDesc = MR.strings.common_loading.desc(),
        externalFileName = name.ifBlank { "attachment-${id.take(8)}" },
        externalMimeType = mimeType,
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = id,
        attachmentCommentsEnabled = attachmentCommentsEnabled,
        imageGallery = imageGallery.map { it.toSlockFilePreviewGalleryImage() },
        imageGalleryIndex = imageGalleryIndex.coerceAtLeast(0)
    )

internal fun KuiklyThreadAttachmentItem.toThreadAttachmentCommentsPreviewPayload(
    commentsEnabled: Boolean
): SlockFilePreviewPayload? {
    if (!commentsEnabled) return null
    if (isMp4VideoAttachment()) return null
    val kind =
        when {
            isImageAttachment() -> "image"
            isMarkdownAttachment() -> "markdown"
            isTextLikeAttachment() || isHtmlAttachment() -> "text"
            else -> "file"
        }
    return toThreadAttachmentLazyPreviewPayload(
        kind = kind,
        attachmentCommentsEnabled = true
    ).copy(commentsInitiallyOpen = true)
}

internal fun SlockFilePreviewPayload.supportsAttachmentComments(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        attachmentCommentsEnabled &&
        !slockFilePreviewIsMp4VideoAttachment(externalFileName, externalMimeType)

internal fun agentWorkspaceLazyPreviewPayload(agentId: String, path: String): SlockFilePreviewPayload =
    SlockFilePreviewPayload(
        kind = "loading",
        requestedKind = "workspace",
        titleDesc = MR.strings.file_preview_title.desc(),
        subtitle = path,
        name = path,
        path = path,
        messageDesc = MR.strings.common_loading.desc(),
        source = SlockFilePreviewSourceAgentWorkspace,
        agentId = agentId
    )

private suspend fun SlockFilePreviewPayload.loadThreadAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String
): SlockFilePreviewPayload? {
    if (source != SlockFilePreviewSourceThreadAttachment || attachmentId.isBlank() || kind != "loading") return null
    val wantsMarkdown =
        slockFilePreviewIsMarkdownAttachment(
            fileName = name.ifBlank { path.ifBlank { externalFileName } },
            mimeType = externalMimeType
        )
    val previewFileName = name.ifBlank { path.ifBlank { externalFileName } }
    val wantsImage =
        requestedKind == "image" ||
            slockFilePreviewIsImageAttachment(fileName = previewFileName, mimeType = externalMimeType)
    val wantsMp4Video =
        slockFilePreviewIsMp4VideoAttachment(
            fileName = previewFileName,
            mimeType = externalMimeType
        )
    val wantsMp3Audio =
        slockFilePreviewIsMp3AudioAttachment(
            fileName = previewFileName,
            mimeType = externalMimeType
        )
    val wantsHtml =
        slockFilePreviewIsHtmlAttachment(
            fileName = previewFileName,
            mimeType = externalMimeType
        )
    val wantsText =
        !wantsMarkdown &&
            !wantsHtml &&
            slockFilePreviewIsTextLikeAttachment(
                fileName = name.ifBlank { path.ifBlank { externalFileName } },
                mimeType = externalMimeType
            )
    return when {
        wantsMarkdown -> loadThreadMarkdownAttachmentPreview(threadApi, networkClient, truncatedPreviewHint)
        wantsImage -> loadThreadImageAttachmentPreview(threadApi)
        wantsMp4Video -> loadThreadVideoAttachmentPreview(threadApi)
        wantsMp3Audio -> loadThreadVideoAttachmentPreview(threadApi, title = MR.strings.file_preview_audio.desc(), audioOnly = true)
        wantsHtml -> loadThreadHtmlAttachmentPreview(threadApi)
        wantsText -> loadThreadTextAttachmentPreview(threadApi, networkClient, truncatedPreviewHint)
        else ->
            copy(
                kind = "unsupported",
                titleDesc = MR.strings.file_preview_title.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_no_preview.desc()
            )
    }
}

private suspend fun SlockFilePreviewPayload.loadThreadImageAttachmentPreview(
    threadApi: SlockThreadApiClient
): SlockFilePreviewPayload {
    val remoteImageUrl =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        }
    // Kuikly Compose image rendering already caches by source URL through
    // KuiklyImageCacheManager -> MemoryCacheModule and the native image adapter.
    val resolvedImageUrl = remoteImageUrl ?: imageUrl
    return copy(
        kind = "image",
        titleDesc = MR.strings.file_preview_image.desc(),
        imageUrl = resolvedImageUrl,
        message = "",
        messageDesc =
            if (resolvedImageUrl == null) {
                MR.strings.file_preview_error_image_unavailable.desc()
            } else {
                null
            },
        error = if (resolvedImageUrl == null) "image_preview_unavailable" else null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadHtmlAttachmentPreview(
    threadApi: SlockThreadApiClient
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentHtmlPreviewUrl(attachmentId)) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_html.desc(),
            error = "html_preview_url_failed"
        )
    return copy(
        kind = "html",
        titleDesc = MR.strings.file_preview_html.desc(),
        content = "",
        imageUrl = null,
        videoUrl = null,
        externalUrl = target,
        message = "",
        messageDesc = null,
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadVideoAttachmentPreview(
    threadApi: SlockThreadApiClient,
    title: StringDesc = MR.strings.file_preview_video.desc(),
    audioOnly: Boolean = false
): SlockFilePreviewPayload {
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_media.desc(),
            error = "video_preview_url_failed"
        )
    return copy(
        kind = "video",
        titleDesc = title,
        videoUrl = target,
        audioOnly = audioOnly,
        message = "",
        messageDesc = null,
        error = null
    )
}

/**
 * Server-side preview attempt per task #203: `GET attachments/:id/preview`
 * returns text/markdown bodies already truncated to a safe size with an
 * explicit `truncated` flag, so text-like previews stop downloading whole
 * files through signed URLs. Returns null when the endpoint is missing,
 * errors, reports unsupported, or answers with a kind the caller does not
 * render — callers then fall back to the legacy full-body fetch.
 */
private suspend fun fetchServerAttachmentPreview(
    networkClient: SlockNetworkClient,
    attachmentId: String
): AttachmentPreview? {
    val cleanId = attachmentId.trim().takeIf { it.isNotBlank() } ?: return null
    val response =
        try {
            networkClient.request(
                priority = DataRequestPriority.Visible,
                path = "attachments/$cleanId/preview"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return null
        }
    if (response.code !in 200..299) return null
    val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return null
    val preview = parseSharedAttachmentPreview(body) ?: return null
    return preview.takeIf { it.isOk }
}

private suspend fun SlockFilePreviewPayload.loadThreadTextAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String
): SlockFilePreviewPayload {
    val serverPreview = fetchServerAttachmentPreview(networkClient, attachmentId)
    val serverText = (serverPreview?.content as? AttachmentPreviewContent.Text)?.text
    if (serverPreview != null && serverText != null) {
        val previewBody =
            if (serverPreview.truncated) serverText + "\n\n$truncatedPreviewHint" else serverText
        return copy(
            kind = "text",
            titleDesc = MR.strings.file_preview_text.desc(),
            content = previewBody,
            markdown = false,
            message = "",
            messageDesc = null,
            error = null
        )
    }
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_text.desc(),
            error = "text_preview_url_failed"
        )
    val response =
        try {
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Visible,
                url = target
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_load_text.desc(),
                error = "text_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_text.desc(),
            error = "text_preview_fetch_failed"
        )
    }
    val body = response.bodyText.orEmpty()
    val previewBody =
        if (body.length > SlockFilePreviewTextMaxChars) {
            body.take(SlockFilePreviewTextMaxChars) + "\n\n$truncatedPreviewHint"
        } else {
            body
        }
    return copy(
        kind = "text",
        titleDesc = MR.strings.file_preview_text.desc(),
        content = previewBody,
        markdown = false,
        message = "",
        messageDesc = null,
        error = null
    )
}

private suspend fun SlockFilePreviewPayload.loadThreadMarkdownAttachmentPreview(
    threadApi: SlockThreadApiClient,
    networkClient: SlockNetworkClient,
    truncatedPreviewHint: String
): SlockFilePreviewPayload {
    val serverPreview = fetchServerAttachmentPreview(networkClient, attachmentId)
    val serverMarkdown = (serverPreview?.content as? AttachmentPreviewContent.Markdown)?.markdown
    if (serverPreview != null && serverMarkdown != null) {
        val previewBody =
            if (serverPreview.truncated) {
                serverMarkdown + "\n\n$truncatedPreviewHint"
            } else {
                serverMarkdown
            }
        return copy(
            kind = "markdown",
            titleDesc = MR.strings.file_preview_markdown.desc(),
            content = previewBody,
            markdown = true,
            message = "",
            messageDesc = null,
            error = null
        )
    }
    val target =
        when (val response = threadApi.attachmentUrl(attachmentId, disposition = "inline")) {
            is ThreadApiResult.Success -> response.data.url.takeIf { it.isNotBlank() }
            is ThreadApiResult.Failure -> null
        } ?: return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
            error = "markdown_preview_url_failed"
        )
    val response =
        try {
            networkClient.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Visible,
                url = target
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
                error = "markdown_preview_fetch_failed"
            )
        }
    if (response.code !in 200..299) {
        return copy(
            kind = "error",
            titleDesc = MR.strings.file_preview_unavailable.desc(),
            message = "",
            messageDesc = MR.strings.file_preview_error_load_markdown.desc(),
            error = "markdown_preview_fetch_failed"
        )
    }
    val body =
        response.bodyText?.takeIf { it.isNotBlank() }
            ?: return copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_markdown_empty.desc(),
                error = "markdown_preview_empty"
            )
    val previewBody =
        if (body.length > SlockFilePreviewMarkdownMaxChars) {
            body.take(SlockFilePreviewMarkdownMaxChars) + "\n\n$truncatedPreviewHint"
        } else {
            body
        }
    return copy(
        kind = "markdown",
        titleDesc = MR.strings.file_preview_markdown.desc(),
        content = previewBody,
        markdown = true,
        message = "",
        messageDesc = null,
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
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_file_unavailable.desc(),
                error = "empty_preview"
            )
        is DataResult.Error ->
            copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_load.format(listOf(result.error.code)),
                error = result.error.code
            )
        is DataResult.NoPermission ->
            copy(
                kind = "error",
                titleDesc = MR.strings.file_preview_hidden.desc(),
                message = "",
                messageDesc = MR.strings.file_preview_error_hidden.format(listOf(result.blocker.code)),
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

internal fun slockFilePreviewIsHtmlAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    val normalizedName = fileName.substringBefore('?').substringBefore('#')
    return normalizedMime.equals("text/html", ignoreCase = true) ||
        normalizedName.endsWith(".html", ignoreCase = true) ||
        normalizedName.endsWith(".htm", ignoreCase = true)
}

internal fun slockFilePreviewIsMp4VideoAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    if (normalizedMime.equals("video/mp4", ignoreCase = true)) return true
    return fileName.substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)
}

// task #556: mp3 attachments play through the same Media3-backed player as
// video (audio-only source, blank surface). Scoped to mp3 like video is
// scoped to mp4 — widen deliberately, per codec, when asked.
internal fun slockFilePreviewIsMp3AudioAttachment(fileName: String, mimeType: String): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim()
    if (normalizedMime.equals("audio/mpeg", ignoreCase = true)) return true
    if (normalizedMime.equals("audio/mp3", ignoreCase = true)) return true
    return fileName.substringBefore('?').substringBefore('#').endsWith(".mp3", ignoreCase = true)
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
                titleDesc =
                    if (markdown) {
                        MR.strings.file_preview_markdown.desc()
                    } else {
                        MR.strings.file_preview_text.desc()
                    },
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
                titleDesc = MR.strings.file_preview_image.desc(),
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                imageUrl = imageUrl,
                message = description.orEmpty(),
                messageDesc =
                    if (description.isNullOrBlank()) {
                        MR.strings.file_preview_error_image_unavailable.desc()
                    } else {
                        null
                    }
            )
        is SlockWorkspaceFilePreview.Unsupported ->
            SlockFilePreviewPayload(
                kind = "unsupported",
                titleDesc = MR.strings.file_preview_title.desc(),
                subtitle = path.ifBlank { this.path },
                name = path.ifBlank { this.path },
                path = this.path,
                meta = membersWorkspaceMetaLine(path),
                message = reason,
                messageDesc = null
            )
        is SlockWorkspaceFilePreview.Error ->
            SlockFilePreviewPayload(
                kind = "error",
                titleDesc = MR.strings.file_preview_unavailable.desc(),
                subtitle = path,
                name = path,
                path = path,
                message = reason,
                messageDesc = null,
                error = reason
            )
        is SlockWorkspaceFilePreview.Loading ->
            SlockFilePreviewPayload(
                kind = "loading",
                requestedKind = "workspace",
                titleDesc = MR.strings.file_preview_title.desc(),
                subtitle = path,
                name = path,
                path = path,
                message = detail,
                messageDesc = if (detail.isBlank()) MR.strings.common_loading.desc() else null
            )
    }

@Composable
internal fun SlockFilePreviewPayloadContent(
    payload: SlockFilePreviewPayload,
    onOpenUrl: (String) -> Unit,
    downloadStatusLabel: String? = null,
    downloadInProgress: Boolean = false,
    downloadPaused: Boolean = false,
    downloadProgressPercent: Int? = null,
    downloadCached: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onPauseDownload: (() -> Unit)? = null,
    onResumeDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    onImageGalleryIndexChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val onToggleDownload = if (downloadPaused) onResumeDownload else onPauseDownload
    val actionTerms = slockFilePreviewLocalizedActionTerms()
    val fallbackFile = stringResource(MR.strings.file_preview_fallback_file)
    if (payload.kind == "loading" && payload.shouldRenderImagePreview()) {
        SlockFilePreviewImageGallerySurface(
            payload = payload,
            onImageGalleryIndexChange = onImageGalleryIndexChange,
            modifier = modifier.fillMaxSize()
        ) {
            SlockFilePreviewImagePanel(
                source = payload.imageUrl,
                fallbackSource = payload.currentImageThumbnailUrl(),
                contentDescription = payload.name.ifBlank { payload.path },
                modifier = Modifier.fillMaxSize(),
                backgroundColor = SlockColor.SurfaceWhite,
                borderWidth = 0.dp,
                zoomable = true
            )
        }
        return
    }
    when (payload.kind) {
        "markdown" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewMarkdownPanel(
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    statusLabel = null,
                    status = "content",
                    body = payload.content,
                    error = null,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.fillMaxSize(),
                    performanceKey = "preview:${payload.path}",
                    textStyle = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    showChrome = false
                )
            }
        "text" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewTextPanel(
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    content = payload.content,
                    modifier = Modifier.fillMaxSize(),
                    showChrome = false
                )
            }
        "image" ->
            SlockFilePreviewImageGallerySurface(
                payload = payload,
                onImageGalleryIndexChange = onImageGalleryIndexChange,
                modifier = modifier.fillMaxSize()
            ) {
                if (payload.imageUrl != null) {
                    SlockFilePreviewDownloadStatusOverlay(
                        statusLabel = downloadStatusLabel,
                        showProgress = downloadInProgress,
                        progressPercent = downloadProgressPercent,
                        downloadPaused = downloadPaused,
                        onToggleDownload = onToggleDownload,
                        onStopDownload = onStopDownload,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        SlockFilePreviewImagePanel(
                            source = payload.imageUrl,
                            fallbackSource = payload.currentImageThumbnailUrl(),
                            contentDescription = payload.name.ifBlank { payload.path },
                            modifier = Modifier.fillMaxSize(),
                            backgroundColor = SlockColor.SurfaceWhite,
                            borderWidth = 0.dp,
                            zoomable = true
                        )
                    }
                } else {
                    SlockFilePreviewGenericCard(
                        iconName = SlockLucideIconName.Image,
                        name = payload.name.ifBlank { payload.path },
                        meta = payload.meta,
                        message = payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_error_image_unavailable) },
                        statusLabel = downloadStatusLabel,
                        showProgress = downloadInProgress,
                        progressPercent = downloadProgressPercent,
                        actionLabel =
                            onDownload
                                ?.takeIf { !downloadInProgress }
                                ?.let { slockFilePreviewPrimaryActionLabel(payload, downloadCached, actionTerms) },
                        actionIcon = if (downloadInProgress) null else slockFilePreviewPrimaryActionIcon(payload, downloadCached),
                        actionEnabled = true,
                        onAction = onDownload,
                        downloadPaused = downloadPaused,
                        onToggleDownload = onToggleDownload,
                        onStopDownload = onStopDownload,
                        modifier = Modifier.fillMaxSize().padding(SlockSpacing.Md)
                    )
                }
            }
        "html" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                val previewUrl = payload.externalUrl.orEmpty()
                if (previewUrl.isNotBlank()) {
                    SlockSandboxWebView(
                        url = previewUrl,
                        allowScripts = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SlockFilePreviewMessage(
                        iconName = SlockLucideIconName.TriangleAlert,
                        title = stringResource(MR.strings.file_preview_unavailable),
                        detail = payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_error_load_html) },
                        modifier = Modifier.fillMaxWidth().padding(SlockSpacing.Md)
                    )
                }
            }
        "video" ->
            SlockFilePreviewDownloadStatusOverlay(
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewVideoPanel(
                    source = payload.videoUrl.orEmpty(),
                    name = payload.name.ifBlank { payload.path },
                    meta = payload.meta,
                    fallbackMessage = payload.localizedMessage(),
                    audioOnly = payload.audioOnly,
                    modifier = Modifier.fillMaxSize()
                )
            }
        "loading" -> {
            SlockFilePreviewImageGallerySurface(
                payload = payload,
                onImageGalleryIndexChange = onImageGalleryIndexChange,
                modifier = modifier.fillMaxSize()
            ) {
                SlockFilePreviewLoadingIndicator(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        "error" ->
            SlockFilePreviewMessage(
                iconName = SlockLucideIconName.TriangleAlert,
                title = stringResource(MR.strings.file_preview_unavailable),
                detail = payload.error ?: payload.localizedMessage().ifBlank { stringResource(MR.strings.file_preview_error_load_generic) },
                modifier = modifier.fillMaxWidth().padding(SlockSpacing.Md)
            )
        else ->
            SlockFilePreviewGenericCard(
                iconName = slockFilePreviewFileIconName(payload),
                name = payload.name.ifBlank { payload.path.ifBlank { fallbackFile } },
                meta = payload.meta,
                message = slockFilePreviewUnsupportedFileMessage(payload, downloadable = onDownload != null).localized(),
                statusLabel = downloadStatusLabel,
                showProgress = downloadInProgress,
                progressPercent = downloadProgressPercent,
                actionLabel =
                    onDownload
                        ?.takeIf { !downloadInProgress }
                        ?.let { slockFilePreviewPrimaryActionLabel(payload, downloadCached, actionTerms) },
                actionIcon = if (downloadInProgress) null else slockFilePreviewPrimaryActionIcon(payload, downloadCached),
                actionEnabled = true,
                onAction = onDownload,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = modifier.fillMaxSize().padding(SlockSpacing.Md)
            )
    }
}

@Composable
private fun SlockFilePreviewImageGallerySurface(
    payload: SlockFilePreviewPayload,
    onImageGalleryIndexChange: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progressLabel = payload.imageGalleryProgressLabel()
    var dragOffsetPx by remember(payload.imageGalleryIndex, payload.imageGallery.size) { mutableStateOf(0f) }
    val swipeThresholdPx =
        with(LocalDensity.current) {
            56.dp.toPx()
        }
    val galleryModifier =
        if (progressLabel != null && onImageGalleryIndexChange != null) {
            Modifier.pointerInput(payload.imageGalleryIndex, payload.imageGallery.size) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragDistance = 0f
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        val nextIndex = payload.nextImageGalleryIndex(dragDistance, swipeThresholdPx)
                        dragDistance = 0f
                        dragOffsetPx = 0f
                        nextIndex?.let(onImageGalleryIndexChange)
                    },
                    onDragCancel = {
                        dragDistance = 0f
                        dragOffsetPx = 0f
                    }
                ) { _, dragAmount ->
                    dragDistance += dragAmount
                    dragOffsetPx = payload.imageGalleryDragOffsetWithResistance(dragDistance)
                }
            }
        } else {
            Modifier
        }
    Box(modifier = modifier.clipToBounds().then(galleryModifier)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
        ) {
            content()
        }
        if (progressLabel != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = progressLabel,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Kuikly's image cache is reference-counted: when the last painter for a source is forgotten,
 * the native memory-cache entry is cleared. The gallery renders one visible item at a time, so
 * keep tiny invisible painters for only the current item and its immediate neighbors.
 */
@Composable
private fun SlockFilePreviewImageGalleryCacheKeepAlive(
    sources: Set<String>,
    modifier: Modifier = Modifier
) {
    if (sources.isEmpty()) return
    Box(
        modifier =
            modifier
                .size(1.dp)
                .alpha(0f)
                .clipToBounds()
    ) {
        sources.sorted().forEach { source ->
            key(source) {
                SlockCachedImage(
                    source = source,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun SlockFilePreviewPayload.previewIconName(): SlockLucideIconName =
    when (kind) {
        "markdown", "text" -> SlockLucideIconName.FileText
        "image" -> SlockLucideIconName.Image
        "video" -> SlockLucideIconName.Film
        "loading" -> SlockLucideIconName.LoaderCircle
        "error" -> SlockLucideIconName.TriangleAlert
        else -> slockFilePreviewFileIconName(this)
    }

@Composable
private fun SlockFilePreviewVideoPanel(
    source: String,
    name: String,
    meta: String,
    fallbackMessage: String,
    audioOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    var playControl by remember(source) { mutableStateOf(VideoPlayControl.PREPLAY) }
    var playState by remember(source) { mutableStateOf(PlayState.NONE) }
    var firstFrameVisible by remember(source) { mutableStateOf(false) }
    var currentTimeMs by remember(source) { mutableStateOf(0) }
    var totalTimeMs by remember(source) { mutableStateOf(0) }
    var seekRequest by remember(source) { mutableStateOf<SlockFilePreviewVideoSeekRequest?>(null) }
    val canPlay = source.isNotBlank()
    val isPlaying = playState == PlayState.PLAYING
    val nativeController = slockPlatformUsesNativeVideoController()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .heightIn(max = 420.dp)
                    .background(Color(0xFF000000))
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (canPlay) {
                SlockFilePreviewKuiklyVideo(
                    source = source,
                    playControl = playControl,
                    seekRequest = seekRequest,
                    onPlayStateChanged = { playState = it },
                    onPlayTimeChanged = { current, total ->
                        currentTimeMs = current
                        totalTimeMs = total
                    },
                    onFirstFrame = {
                        firstFrameVisible = true
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!canPlay) {
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.TriangleAlert,
                    title = fallbackMessage.ifBlank { stringResource(MR.strings.file_preview_error_load_media) },
                    detail = name.ifBlank { meta }
                )
            } else if (audioOnly && !nativeController) {
                // Audio sources render no frames — keep a persistent music
                // visual over the surface instead of a black box (task #556).
                // With the native controller the card is dropped entirely
                // (task #15): its play/seek cluster owns the surface and the
                // page header already shows the file name and meta.
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.FileMusic,
                    title = name.ifBlank { stringResource(MR.strings.file_preview_audio) },
                    detail = meta
                )
            } else if (!nativeController && !firstFrameVisible && !isPlaying) {
                SlockFilePreviewVideoOverlayText(
                    iconName = SlockLucideIconName.Play,
                    title = name.ifBlank { stringResource(MR.strings.file_preview_video) },
                    detail = meta
                )
            }
            if (!nativeController) {
                SlockFilePreviewVideoControls(
                    isPlaying = isPlaying,
                    canPlay = canPlay,
                    currentTimeMs = currentTimeMs,
                    totalTimeMs = totalTimeMs,
                    playState = playState,
                    onTogglePlay = {
                        playControl = if (isPlaying) VideoPlayControl.PAUSE else VideoPlayControl.PLAY
                    },
                    onSeek = { seekTimeMs ->
                        val nextTime = seekTimeMs.coerceIn(0, totalTimeMs.coerceAtLeast(0))
                        currentTimeMs = nextTime
                        if (playState == PlayState.PLAY_END) {
                            playState = PlayState.PAUSED
                        }
                        seekRequest = SlockFilePreviewVideoSeekRequest(
                            id = (seekRequest?.id ?: 0) + 1,
                            timeMs = nextTime
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun SlockFilePreviewVideoControls(
    isPlaying: Boolean,
    canPlay: Boolean,
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Color(0xDD000000))
                .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
                .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockBrutalIconButton(
                iconName = if (isPlaying) SlockLucideIconName.Pause else SlockLucideIconName.Play,
                onClick = { if (canPlay) onTogglePlay() },
                enabled = canPlay,
                size = 30.dp,
                shadowOffset = 2.dp,
                iconSize = 16.dp,
                backgroundColor = SlockColor.SurfaceWhite
            )
            Text(
                text = slockFilePreviewLocalizedVideoTimeLabel(currentTimeMs, totalTimeMs, playState),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.SurfaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        SlockFilePreviewVideoProgressBar(
            progressPercent = slockFilePreviewVideoProgressPercent(currentTimeMs, totalTimeMs),
            totalTimeMs = totalTimeMs,
            onSeek = onSeek.takeIf { canPlay && totalTimeMs > 0 }
        )
    }
}

@Composable
private fun SlockFilePreviewVideoProgressBar(
    progressPercent: Int?,
    totalTimeMs: Int,
    onSeek: ((Int) -> Unit)? = null
) {
    val percent = progressPercent?.coerceIn(0, 100)
    var widthPx by remember { mutableStateOf(0f) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .onGloballyPositioned { coordinates ->
                    widthPx = coordinates.size.width.toFloat()
                }
                .then(
                    if (onSeek != null) {
                        Modifier.pointerInput(totalTimeMs, onSeek, widthPx) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val target = slockFilePreviewVideoSeekTimeFromTap(
                                        tapX = offset.x,
                                        widthPx = widthPx,
                                        totalTimeMs = totalTimeMs
                                    )
                                    if (target != null) {
                                        onSeek.invoke(target)
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(Color(0x66000000))
                    .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
        ) {
            when (percent) {
                null -> {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                    )
                }
                else -> {
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
        }
    }
}

@Composable
private fun SlockFilePreviewVideoOverlayText(
    iconName: SlockLucideIconName,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .background(Color(0xCC000000))
                .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(34.dp), color = "#FFFFFFFF")
        Text(
            text = title,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.SurfaceWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = SlockType.Caption,
                color = SlockColor.SurfaceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun SlockFilePreviewKuiklyVideo(
    source: String,
    playControl: VideoPlayControl,
    seekRequest: SlockFilePreviewVideoSeekRequest?,
    onPlayStateChanged: (PlayState) -> Unit,
    onPlayTimeChanged: (Int, Int) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    MakeKuiklyComposeNode(
        factory = { VideoView() },
        modifier = modifier,
        viewUpdate = { view ->
            view.getViewAttr().apply {
                src(source)
                resizeModeToContain()
                muted(false)
                rate(1f)
                playControl(playControl)
                seekRequest?.let { seekToTimeMs(it) }
            }
            view.getViewEvent().apply {
                playStateDidChanged { state, _ -> onPlayStateChanged(state) }
                playTimeDidChanged { current, total -> onPlayTimeChanged(current, total) }
                firstFrameDidDisplay { onFirstFrame() }
            }
        }
    )
}

private fun com.tencent.kuikly.core.views.VideoAttr.seekToTimeMs(request: SlockFilePreviewVideoSeekRequest) {
    setProp(SlockFilePreviewVideoSeekProp, "${request.id}:${request.timeMs}")
}

internal data class SlockFilePreviewVideoStateTerms(
    val playing: String,
    val buffering: String,
    val paused: String,
    val ended: String,
    val playbackFailed: String,
    val ready: String
)

internal fun slockFilePreviewVideoTimeLabel(
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState,
    terms: SlockFilePreviewVideoStateTerms
): String {
    val stateLabel =
        when (playState) {
            PlayState.PLAYING -> terms.playing
            PlayState.BUFFERING -> terms.buffering
            PlayState.PAUSED -> terms.paused
            PlayState.PLAY_END -> terms.ended
            PlayState.ERROR -> terms.playbackFailed
            PlayState.NONE -> terms.ready
        }
    val current = currentTimeMs.slockFilePreviewVideoDurationLabel()
    val total = totalTimeMs.takeIf { it > 0 }?.slockFilePreviewVideoDurationLabel()
    return if (total == null) {
        "$stateLabel · $current"
    } else {
        "$stateLabel · $current / $total"
    }
}

@Composable
private fun slockFilePreviewLocalizedVideoTimeLabel(
    currentTimeMs: Int,
    totalTimeMs: Int,
    playState: PlayState
): String =
    slockFilePreviewVideoTimeLabel(
        currentTimeMs = currentTimeMs,
        totalTimeMs = totalTimeMs,
        playState = playState,
        terms =
            SlockFilePreviewVideoStateTerms(
                playing = stringResource(MR.strings.file_preview_media_playing),
                buffering = stringResource(MR.strings.file_preview_media_buffering),
                paused = stringResource(MR.strings.file_preview_media_paused),
                ended = stringResource(MR.strings.file_preview_media_ended),
                playbackFailed = stringResource(MR.strings.file_preview_media_playback_failed),
                ready = stringResource(MR.strings.file_preview_media_ready)
            )
    )

internal fun slockFilePreviewVideoProgressPercent(currentTimeMs: Int, totalTimeMs: Int): Int? {
    if (totalTimeMs <= 0) return null
    return ((currentTimeMs.coerceAtLeast(0).toFloat() / totalTimeMs.toFloat()) * 100f)
        .roundToInt()
        .coerceIn(0, 100)
}

internal fun slockFilePreviewVideoSeekTimeFromTap(tapX: Float, widthPx: Float, totalTimeMs: Int): Int? {
    if (widthPx <= 0f || totalTimeMs <= 0) return null
    return ((tapX.coerceIn(0f, widthPx) / widthPx) * totalTimeMs.toFloat())
        .roundToInt()
        .coerceIn(0, totalTimeMs)
}

private fun Int.slockFilePreviewVideoDurationLabel(): String {
    val totalSeconds = (coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun SlockFilePreviewPayload.imageGalleryProgressLabel(): String? {
    val size = imageGallery.size
    if (size <= 1) return null
    val index = imageGalleryIndex.coerceIn(0, size - 1)
    return "${index + 1}/$size"
}

internal fun SlockFilePreviewPayload.canCompleteComposerImageSelection(): Boolean =
    if (imageGallery.isNotEmpty()) {
        imageGallery.all { image -> image.composerImageFileRef.isNotBlank() }
    } else {
        composerImageFileRef.isNotBlank()
    }

internal fun SlockFilePreviewPayload.withComposerImageEditResult(fileRef: String): SlockFilePreviewPayload {
    if (fileRef.isBlank() || imageGallery.isEmpty()) return this
    val currentIndex = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    val updatedGallery =
        imageGallery.mapIndexed { index, image ->
            if (index == currentIndex) {
                image.copy(
                    thumbnailUrl = fileRef,
                    composerImageFileRef = fileRef,
                    composerImagePreviewUri = fileRef
                )
            } else {
                image
            }
        }
    return copy(
        imageUrl = fileRef,
        composerImageFileRef = fileRef,
        composerImagePreviewUri = fileRef,
        imageGallery = updatedGallery
    )
}

internal data class SlockFilePreviewImageGalleryResolutionState(
    val resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap(),
    val inFlightAttachmentIds: List<String> = emptyList()
) {
    internal fun beginResolution(payload: SlockFilePreviewPayload): SlockFilePreviewImageGalleryResolutionStart {
        val attachmentId = payload.attachmentId
        if (
            !payload.isPendingThreadImageGalleryResolution() ||
            attachmentId in resolvedPayloadByAttachmentId
        ) {
            return SlockFilePreviewImageGalleryResolutionStart(
                state = this,
                requestPayload = null,
                cancelAttachmentId = null
            )
        }
        if (attachmentId in inFlightAttachmentIds) {
            return SlockFilePreviewImageGalleryResolutionStart(
                state = copy(inFlightAttachmentIds = (inFlightAttachmentIds - attachmentId) + attachmentId),
                requestPayload = null,
                cancelAttachmentId = null
            )
        }
        val cancelAttachmentId =
            inFlightAttachmentIds
                .takeIf { it.size >= SlockFilePreviewImageGalleryMaxInFlightRequests }
                ?.firstOrNull()
        val nextInFlightAttachmentIds =
            (cancelAttachmentId?.let(inFlightAttachmentIds::minus) ?: inFlightAttachmentIds) + attachmentId
        return SlockFilePreviewImageGalleryResolutionStart(
            state = copy(inFlightAttachmentIds = nextInFlightAttachmentIds),
            requestPayload = payload,
            cancelAttachmentId = cancelAttachmentId
        )
    }

    internal fun completeResolution(
        requestPayload: SlockFilePreviewPayload,
        resolvedPayload: SlockFilePreviewPayload
    ): SlockFilePreviewImageGalleryResolutionState {
        val attachmentId = requestPayload.attachmentId
        val nextResolvedPayloads =
            if (
                resolvedPayload.attachmentId == attachmentId &&
                resolvedPayload.isTerminalThreadImageGalleryPayload() &&
                resolvedPayload.hasResolvedRemoteImageComparedTo(requestPayload)
            ) {
                resolvedPayloadByAttachmentId + (attachmentId to resolvedPayload)
            } else {
                resolvedPayloadByAttachmentId
            }
        return copy(
            resolvedPayloadByAttachmentId = nextResolvedPayloads,
            inFlightAttachmentIds = inFlightAttachmentIds - attachmentId
        )
    }

    internal fun finishResolution(attachmentId: String): SlockFilePreviewImageGalleryResolutionState =
        copy(inFlightAttachmentIds = inFlightAttachmentIds - attachmentId)
}

internal data class SlockFilePreviewImageGalleryResolutionStart(
    val state: SlockFilePreviewImageGalleryResolutionState,
    val requestPayload: SlockFilePreviewPayload?,
    val cancelAttachmentId: String?
)

internal fun Map<String, Job>.ownsImageGalleryResolution(
    attachmentId: String,
    job: Job?
): Boolean =
    job != null && this[attachmentId] === job

private fun SlockFilePreviewPayload.isPendingThreadImageGalleryResolution(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        imageGallery.isNotEmpty() &&
        kind == "loading" &&
        requestedKind == "image"

private fun SlockFilePreviewPayload.isTerminalThreadImageGalleryPayload(): Boolean =
    source == SlockFilePreviewSourceThreadAttachment &&
        attachmentId.isNotBlank() &&
        imageGallery.isNotEmpty() &&
        kind != "loading" &&
        requestedKind == "image"

internal fun SlockFilePreviewPayload.withResolvedImageGalleryPayload(
    resolvedPayload: SlockFilePreviewPayload
): SlockFilePreviewPayload? {
    if (!isPendingThreadImageGalleryResolution()) return null
    if (attachmentId != resolvedPayload.attachmentId || !resolvedPayload.isTerminalThreadImageGalleryPayload()) return null
    return resolvedPayload.copy(
        imageGallery = imageGallery,
        imageGalleryIndex = imageGalleryIndex
    )
}

private fun SlockFilePreviewPayload.hasResolvedRemoteImageComparedTo(
    requestPayload: SlockFilePreviewPayload
): Boolean {
    val resolvedSource = imageUrl?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val initialSource = requestPayload.imageUrl?.trim()?.takeIf { it.isNotBlank() }
    return resolvedSource != initialSource
}

private fun SlockFilePreviewPayload.imageGalleryResolutionFailurePayload(): SlockFilePreviewPayload =
    copy(
        kind = "image",
        titleDesc = MR.strings.file_preview_image.desc(),
        message = "",
        messageDesc =
            if (imageUrl.isNullOrBlank()) {
                MR.strings.file_preview_error_image_unavailable.desc()
            } else {
                null
            },
        error = "image_preview_resolve_failed"
    )

internal fun SlockFilePreviewPayload.imageGalleryKeepAliveSources(
    resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap()
): Set<String> {
    if (imageGallery.isEmpty()) return emptySet()
    val currentIndex = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    val retainedIndices =
        (currentIndex - 1..currentIndex + 1)
            .filter(imageGallery.indices::contains)
    return buildSet {
        retainedIndices.forEach { index ->
            val galleryImage = imageGallery[index]
            galleryImage.thumbnailUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            resolvedPayloadByAttachmentId[galleryImage.attachmentId]
                ?.imageUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            if (index == currentIndex) {
                imageUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
                currentImageThumbnailUrl()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }
}

internal fun SlockFilePreviewPayload.withImageGalleryIndex(
    index: Int,
    resolvedPayloadByAttachmentId: Map<String, SlockFilePreviewPayload> = emptyMap()
): SlockFilePreviewPayload? {
    if (imageGallery.isEmpty()) return null
    val nextIndex = index.coerceIn(0, imageGallery.lastIndex)
    val nextImage = imageGallery.getOrNull(nextIndex) ?: return null
    resolvedPayloadByAttachmentId[nextImage.attachmentId]?.let { resolvedPayload ->
        return resolvedPayload.copy(
            imageGallery = imageGallery,
            imageGalleryIndex = nextIndex
        )
    }
    return nextImage.toPreviewPayload(previous = this, imageGalleryIndex = nextIndex)
}

internal fun SlockFilePreviewPayload.nextImageGalleryIndex(
    dragDistancePx: Float,
    thresholdPx: Float
): Int? {
    if (imageGallery.size <= 1) return null
    return when {
        dragDistancePx <= -thresholdPx && imageGalleryIndex < imageGallery.lastIndex -> imageGalleryIndex + 1
        dragDistancePx >= thresholdPx && imageGalleryIndex > 0 -> imageGalleryIndex - 1
        else -> null
    }
}

private fun SlockFilePreviewPayload.imageGalleryDragOffsetWithResistance(dragDistancePx: Float): Float {
    if (imageGallery.size <= 1) return 0f
    val index = imageGalleryIndex.coerceIn(0, imageGallery.lastIndex)
    return when {
        dragDistancePx > 0f && index == 0 -> dragDistancePx * 0.35f
        dragDistancePx < 0f && index == imageGallery.lastIndex -> dragDistancePx * 0.35f
        else -> dragDistancePx
    }
}

@Composable
private fun SlockFilePreviewPayload.previewHeaderTitle(): String {
    val fallbackFile = stringResource(MR.strings.file_preview_fallback_file)
    val source =
        name
            .ifBlank { path }
            .ifBlank { externalFileName }
            .ifBlank { title }
            .ifBlank { titleDesc.localized() }
            .ifBlank { fallbackFile }
    return source.slockFilePreviewFileName(fallbackFile)
}

@Composable
private fun SlockFilePreviewPayload.previewHeaderSubtitle(): String? {
    val headerTitle = previewHeaderTitle()
    return slockFilePreviewVisibleSubtitle(headerTitle)
}

internal fun SlockFilePreviewPayload.slockFilePreviewVisibleSubtitle(headerTitle: String): String? =
    listOf(meta.withoutUserVisibleMimeType(), path, subtitle.withoutUserVisibleMimeType())
        .asSequence()
        .map { it.trim() }
        .firstOrNull { candidate ->
            candidate.isNotBlank() &&
                candidate != headerTitle &&
                candidate.slockFilePreviewFileName(fallback = "") != headerTitle &&
                !candidate.endsWith("preview", ignoreCase = true)
        }

internal fun String.withoutUserVisibleMimeType(): String =
    split('·')
        .asSequence()
        .map { it.trim() }
        .filterNot { it.isMimeTypePresentationSegment() }
        .filter { it.isNotBlank() }
        .joinToString(" · ")

private fun String.isMimeTypePresentationSegment(): Boolean {
    val normalized = substringBefore(';').trim().lowercase()
    val topLevel = normalized.substringBefore('/', missingDelimiterValue = "")
    if (topLevel !in SlockPresentationMimeTopLevels) return false
    val subtype = normalized.substringAfter('/', missingDelimiterValue = "")
    return subtype.isNotBlank() && subtype.none { it.isWhitespace() }
}

@Composable
private fun SlockFilePreviewPayload.localizedMessage(): String =
    message.ifBlank { messageDesc?.localized().orEmpty() }

private fun String.slockFilePreviewFileName(fallback: String): String {
    val normalized = trim().replace('\\', '/')
    return normalized.substringAfterLast('/').ifBlank { normalized.ifBlank { fallback } }
}

private fun KuiklyThreadAttachmentItem.toSlockFilePreviewGalleryImage(): SlockFilePreviewGalleryImage =
    SlockFilePreviewGalleryImage(
        attachmentId = id,
        name = name,
        mimeType = mimeType,
        sizeLabel = sizeLabel,
        thumbnailUrl = thumbnailUrl
    )

private fun SlockFilePreviewGalleryImage.toPreviewPayload(
    previous: SlockFilePreviewPayload,
    imageGalleryIndex: Int
): SlockFilePreviewPayload {
    val title = name
    val externalFileName = name.ifBlank { "attachment-${attachmentId.take(8)}" }
    val meta = sizeLabel
    if (previous.composerImageActionId.isNotBlank() && previous.composerImageCanComplete) {
        return previous.copy(
            kind = "image",
            requestedKind = "image",
            title = "",
            titleDesc = MR.strings.file_preview_image.desc(),
            subtitle = title,
            name = title,
            path = title,
            meta = meta,
            imageUrl = composerImagePreviewUri.ifBlank { thumbnailUrl.orEmpty() }.takeIf { it.isNotBlank() },
            message = "",
            messageDesc = null,
            error = null,
            externalFileName = externalFileName,
            externalMimeType = mimeType,
            source = "",
            attachmentId = "",
            composerImageFileRef = composerImageFileRef,
            composerImageFileName = title,
            composerImageMimeType = mimeType,
            composerImageSizeBytes = composerImageSizeBytes,
            composerImagePreviewUri = composerImagePreviewUri.ifBlank { thumbnailUrl.orEmpty() },
            imageGalleryIndex = imageGalleryIndex
        )
    }
    return previous.copy(
        kind = "loading",
        requestedKind = "image",
        title = "",
        titleDesc = MR.strings.file_preview_image.desc(),
        subtitle = title,
        name = title,
        path = title,
        meta = meta,
        content = "",
        markdown = false,
        imageUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
        message = "",
        messageDesc = MR.strings.common_loading.desc(),
        error = null,
        externalUrl = null,
        videoUrl = null,
        externalFileName = externalFileName,
        externalMimeType = mimeType,
        source = SlockFilePreviewSourceThreadAttachment,
        attachmentId = attachmentId,
        imageGalleryIndex = imageGalleryIndex
    )
}

@Composable
internal fun SlockFilePreviewOverflowMenuButton(
    onOpenMenu: (SlockPopoverAnchor) -> Unit,
    modifier: Modifier = Modifier
) {
    val menuAnchor = remember { SlockLiveRootAnchorRef(identity = "file_preview_overflow") }
    SlockBrutalIconButton(
        iconName = SlockLucideIconName.Ellipsis,
        onClick = {
            menuAnchor.resolve(SlockPopoverLocalPoint.BottomEnd)?.let(onOpenMenu)
        },
        size = 30.dp,
        shadowOffset = 2.dp,
        modifier = modifier.slockCaptureLiveRootAnchor(menuAnchor)
    )
}

private fun SlockWorkspaceFilePreview.Text.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Image.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
        sizeBytes.slockFilePreviewSizeLabel(),
        modifiedAt?.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { path.ifBlank { fallbackPath } }

private fun SlockWorkspaceFilePreview.Unsupported.membersWorkspaceMetaLine(fallbackPath: String): String =
    listOfNotNull(
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

private val SlockPresentationMimeTopLevels =
    setOf("application", "audio", "chemical", "example", "font", "image", "message", "model", "multipart", "text", "video")

internal data class SlockFilePreviewDownloadStatusTerms(
    val downloading: String,
    val paused: String,
    val saving: String
)

internal fun slockFilePreviewDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    terms: SlockFilePreviewDownloadStatusTerms
): String {
    val percent = progress?.percent
    val speedLabel = progress?.bytesPerSecond.slockFilePreviewSpeedLabel()
    val downloadedLabel = progress?.downloadedBytes?.takeIf { it > 0L }.slockFilePreviewSizeLabel()
    val verb =
        when {
            progress?.status == "paused" -> terms.paused
            progress?.phase?.startsWith("export_") == true -> terms.saving
            else -> terms.downloading
        }
    val primary =
        when {
            percent != null -> "$verb $percent%"
            downloadedLabel != null -> "$verb $downloadedLabel"
            else -> "$verb..."
        }
    return listOfNotNull(primary, speedLabel).joinToString(" · ")
}

internal fun slockAttachmentDownloadControlAllowed(
    progress: BridgeAttachmentDownloadProgress?,
    action: String
): Boolean =
    progress?.phase?.startsWith("export_") != true || (action != "pause" && action != "resume")

internal fun slockFilePreviewActiveDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    status: String,
    terms: SlockFilePreviewDownloadStatusTerms
): String {
    val activeProgress =
        progress
            ?: SlockFilePreviewInitialDownloadProgress.copy(
                status = if (status == "paused") "paused" else "progress"
            )
    return slockFilePreviewDownloadStatusLabel(activeProgress, terms)
}

@Composable
internal fun slockFilePreviewLocalizedActiveDownloadStatusLabel(
    progress: BridgeAttachmentDownloadProgress?,
    status: String
): String =
    slockFilePreviewActiveDownloadStatusLabel(
        progress = progress,
        status = status,
        terms =
            SlockFilePreviewDownloadStatusTerms(
                downloading = stringResource(MR.strings.file_preview_download_downloading),
                paused = stringResource(MR.strings.file_preview_download_paused),
                saving = stringResource(MR.strings.file_preview_download_saving)
            )
    )

internal val SlockFilePreviewInitialDownloadProgress =
    BridgeAttachmentDownloadProgress(
        downloadId = "",
        sequence = 0L,
        phase = "preparing",
        status = "progress",
        percent = null,
        downloadedBytes = 0L,
        totalBytes = null,
        bytesPerSecond = null
    )

internal data class SlockFilePreviewActionTerms(
    val download: String,
    val install: String,
    val open: String,
    val openWith: String
)

@Composable
internal fun slockFilePreviewLocalizedActionTerms(): SlockFilePreviewActionTerms =
    SlockFilePreviewActionTerms(
        download = stringResource(MR.strings.file_preview_action_download),
        install = stringResource(MR.strings.file_preview_action_install),
        open = stringResource(MR.strings.file_preview_action_open),
        openWith = stringResource(MR.strings.file_preview_action_open_with)
    )

internal fun slockFilePreviewPrimaryActionLabel(
    payload: SlockFilePreviewPayload,
    cached: Boolean,
    terms: SlockFilePreviewActionTerms
): String =
    when {
        !cached -> terms.download
        payload.isAndroidPackageArchive() -> terms.install
        else -> terms.open
    }

internal enum class SlockFilePreviewPrimaryAction {
    Save,
    Open
}

internal fun slockFilePreviewPrimaryAction(cached: Boolean): SlockFilePreviewPrimaryAction =
    if (cached) SlockFilePreviewPrimaryAction.Open else SlockFilePreviewPrimaryAction.Save

internal fun slockFilePreviewUnsupportedFileMessage(payload: SlockFilePreviewPayload, downloadable: Boolean): StringDesc =
    when {
        downloadable -> MR.strings.file_preview_download_to_open.desc()
        payload.message.isNotBlank() -> payload.message.desc()
        else -> payload.messageDesc ?: MR.strings.file_preview_no_preview.desc()
    }

internal fun slockFilePreviewOverflowActionLabel(
    payload: SlockFilePreviewPayload,
    cached: Boolean,
    terms: SlockFilePreviewActionTerms
): String =
    when {
        cached && payload.isAndroidPackageArchive() -> terms.install
        else -> terms.openWith
    }

internal fun slockFilePreviewShouldTrackOpenProgress(cached: Boolean): Boolean = !cached

internal fun slockFilePreviewFileIconName(payload: SlockFilePreviewPayload): SlockLucideIconName {
    val mime = payload.externalMimeType.substringBefore(';').trim().lowercase()
    val fileName = payload.attachmentCacheFileName().substringBefore('?').substringBefore('#')
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        payload.isAndroidPackageArchive() -> SlockLucideIconName.Package
        slockFilePreviewIsImageAttachment(fileName = fileName, mimeType = mime) ->
            SlockLucideIconName.FileImage
        mime.startsWith("video/") || extension in SlockFilePreviewVideoExtensions ->
            SlockLucideIconName.FileVideoCamera
        mime.startsWith("audio/") || extension in SlockFilePreviewAudioExtensions ->
            SlockLucideIconName.FileMusic
        mime in SlockFilePreviewArchiveMimeTypes || extension in SlockFilePreviewArchiveExtensions ->
            SlockLucideIconName.FileArchive
        mime in SlockFilePreviewSpreadsheetMimeTypes || extension in SlockFilePreviewSpreadsheetExtensions ->
            SlockLucideIconName.FileSpreadsheet
        mime in SlockFilePreviewPresentationMimeTypes || extension in SlockFilePreviewPresentationExtensions ->
            SlockLucideIconName.Presentation
        mime in SlockFilePreviewJsonMimeTypes || extension in SlockFilePreviewJsonExtensions ->
            SlockLucideIconName.FileBraces
        mime in SlockFilePreviewTerminalMimeTypes || extension in SlockFilePreviewTerminalExtensions ->
            SlockLucideIconName.FileTerminal
        mime in SlockFilePreviewCodeMimeTypes || extension in SlockFilePreviewCodeExtensions ->
            SlockLucideIconName.FileCode
        extension in SlockFilePreviewTypedTextExtensions ->
            SlockLucideIconName.FileType
        mime.startsWith("text/") ||
            extension in SlockFilePreviewDocumentExtensions ->
            SlockLucideIconName.FileText
        else -> SlockLucideIconName.File
    }
}

internal fun slockFilePreviewIsImageAttachment(fileName: String, mimeType: String): Boolean {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val extension = fileName.substringBefore('?').substringBefore('#').substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mime.startsWith("image/") || extension in SlockFilePreviewImageExtensions
}

private val SlockFilePreviewImageExtensions =
    setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "svg", "webp")

internal fun slockComposerImagePickerIsStrictImage(fileName: String, mimeType: String): Boolean {
    val mime = mimeType.substringBefore(';').trim().lowercase()
    val extension =
        fileName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    return mime.startsWith("image/") && extension in SlockFilePreviewImageExtensions
}

private val SlockFilePreviewVideoExtensions =
    setOf("3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm", "wmv")

private val SlockFilePreviewAudioExtensions =
    setOf("aac", "aiff", "flac", "m4a", "mid", "midi", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma")

private val SlockFilePreviewArchiveExtensions =
    setOf("7z", "aar", "bz2", "dmg", "gz", "jar", "rar", "tar", "tgz", "war", "xz", "zip")

private val SlockFilePreviewSpreadsheetExtensions =
    setOf("csv", "numbers", "ods", "tsv", "xls", "xlsm", "xlsx")

private val SlockFilePreviewPresentationExtensions =
    setOf("key", "odp", "pps", "ppsx", "ppt", "pptx")

private val SlockFilePreviewJsonExtensions =
    setOf("geojson", "json", "json5")

private val SlockFilePreviewTerminalExtensions =
    setOf("bash", "bat", "cmd", "fish", "ps1", "sh", "zsh")

private val SlockFilePreviewCodeExtensions =
    setOf(
        "c", "cc", "cpp", "cs", "css", "dart", "go", "h", "hpp", "html", "java", "js", "jsx",
        "kt", "kts", "lua", "m", "mm", "php", "py", "rb", "rs", "sql", "swift", "toml", "ts",
        "tsx", "vue", "xml", "yaml", "yml"
    )

private val SlockFilePreviewTypedTextExtensions =
    setOf("log", "text", "txt")

private val SlockFilePreviewDocumentExtensions =
    setOf("doc", "docx", "md", "markdown", "odt", "pages", "pdf", "rtf")

private val SlockFilePreviewArchiveMimeTypes =
    setOf(
        "application/gzip",
        "application/java-archive",
        "application/vnd.rar",
        "application/x-7z-compressed",
        "application/x-bzip2",
        "application/x-gzip",
        "application/x-rar-compressed",
        "application/x-tar",
        "application/zip"
    )

private val SlockFilePreviewSpreadsheetMimeTypes =
    setOf(
        "application/vnd.ms-excel",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "text/csv",
        "text/tab-separated-values"
    )

private val SlockFilePreviewPresentationMimeTypes =
    setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

private val SlockFilePreviewJsonMimeTypes =
    setOf("application/geo+json", "application/json", "application/ld+json")

private val SlockFilePreviewTerminalMimeTypes =
    setOf("application/x-sh", "application/x-shellscript", "text/x-shellscript")

private val SlockFilePreviewCodeMimeTypes =
    setOf(
        "application/javascript",
        "application/typescript",
        "application/x-javascript",
        "application/xml",
        "text/css",
        "text/html",
        "text/javascript",
        "text/xml"
    )

private fun slockFilePreviewPrimaryActionIcon(payload: SlockFilePreviewPayload, cached: Boolean): SlockLucideIconName =
    when {
        !cached -> SlockLucideIconName.Download
        payload.isAndroidPackageArchive() -> SlockLucideIconName.Package
        else -> SlockLucideIconName.ExternalLink
    }

private fun canOpenPayloadWithExternalViewer(payload: SlockFilePreviewPayload): Boolean =
    payload.externalUrl?.isNotBlank() == true ||
        (payload.source == SlockFilePreviewSourceThreadAttachment && payload.attachmentId.isNotBlank())

private fun SlockFilePreviewPayload.attachmentCacheId(): String =
    attachmentId.ifBlank { path.ifBlank { name.ifBlank { externalFileName.ifBlank { "attachment" } } } }

private fun SlockFilePreviewPayload.attachmentCacheFileName(): String =
    externalFileName
        .ifBlank { name }
        .ifBlank { path }
        .ifBlank { "attachment" }

private fun SlockFilePreviewPayload.filePreviewDownloadId(generation: Long? = null): String =
    listOfNotNull(generation?.let { "attempt-$it" }, source, attachmentCacheId(), attachmentCacheFileName())
        .joinToString(":")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
        .take(160)
        .ifBlank { "file-preview-download" }

private fun SlockFilePreviewPayload.isAndroidPackageArchive(): Boolean {
    val mime = externalMimeType.substringBefore(';').trim()
    if (mime.equals("application/vnd.android.package-archive", ignoreCase = true)) return true
    val name = attachmentCacheFileName().substringBefore('?').substringBefore('#')
    return name.endsWith(".apk", ignoreCase = true)
}

private fun Long?.slockFilePreviewSpeedLabel(): String? {
    val bytesPerSecond = this?.takeIf { it > 0L } ?: return null
    return "${bytesPerSecond.slockFilePreviewSizeLabel()}/s"
}

@Composable
internal fun SlockFilePreviewPageScaffold(
    title: String,
    subtitle: String? = null,
    iconName: SlockLucideIconName,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Md,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    overflowMenuItems: List<SlockRaftUiMenuItem> = emptyList(),
    onOverflowMenuSelect: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
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
                    trailingContent
                        ?: if (overflowMenuItems.isNotEmpty()) {
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
        if (menuOpen && overflowMenuItems.isNotEmpty() && menuAnchor != null) {
            SlockRaftUiControlledMenu(
                expanded = menuOpen,
                anchor = menuAnchor!!,
                items = overflowMenuItems,
                onSelect = { id ->
                    menuOpen = false
                    onOverflowMenuSelect(id)
                },
                onDismiss = { menuOpen = false },
                width = 180.dp
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
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .slockSquareCorners()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(72.dp), color = "#FF000000")
        Box(modifier = Modifier.height(SlockSpacing.Md))
        Text(
            text = name.ifBlank { stringResource(MR.strings.file_preview_fallback_file) },
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
        if (showProgress) {
            Box(modifier = Modifier.height(SlockSpacing.Md))
            SlockFilePreviewDownloadStatusBar(
                statusLabel =
                    statusLabel
                        ?: slockFilePreviewLocalizedActiveDownloadStatusLabel(
                            progress = SlockFilePreviewInitialDownloadProgress.copy(percent = progressPercent ?: 0),
                            status = if (downloadPaused) "paused" else "downloading"
                        ),
                showProgress = true,
                progressPercent = progressPercent ?: 0,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload
            )
        } else if (statusLabel != null) {
            Box(modifier = Modifier.height(SlockSpacing.Sm))
            Text(
                text = statusLabel,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (!showProgress && actionLabel != null && onAction != null) {
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
private fun SlockFilePreviewDownloadStatusOverlay(
    statusLabel: String?,
    showProgress: Boolean,
    progressPercent: Int?,
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (statusLabel != null || showProgress) {
            SlockFilePreviewDownloadStatusBar(
                statusLabel =
                    statusLabel
                        ?: slockFilePreviewLocalizedActiveDownloadStatusLabel(
                            progress = null,
                            status = if (downloadPaused) "paused" else "downloading"
                        ),
                showProgress = showProgress,
                progressPercent = progressPercent,
                downloadPaused = downloadPaused,
                onToggleDownload = onToggleDownload,
                onStopDownload = onStopDownload,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun SlockFilePreviewDownloadStatusBar(
    statusLabel: String,
    showProgress: Boolean,
    progressPercent: Int?,
    downloadPaused: Boolean = false,
    onToggleDownload: (() -> Unit)? = null,
    onStopDownload: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = statusLabel,
            style = SlockType.Caption,
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showProgress) {
            SlockFilePreviewProgressBar(progressPercent = progressPercent)
        }
        if (showProgress && onToggleDownload != null && onStopDownload != null) {
            SlockFilePreviewDownloadControls(
                paused = downloadPaused,
                onToggle = onToggleDownload,
                onStop = onStopDownload
            )
        }
    }
}

@Composable
private fun SlockFilePreviewDownloadControls(
    paused: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockBrutalActionButton(
            label =
                stringResource(
                    if (paused) MR.strings.file_preview_download_resume else MR.strings.file_preview_download_pause
                ),
            onClick = onToggle,
            leadingIcon = if (paused) SlockLucideIconName.Play else SlockLucideIconName.Pause,
            minWidth = 92.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
        )
        SlockBrutalActionButton(
            label = stringResource(MR.strings.common_stop),
            onClick = onStop,
            leadingIcon = SlockLucideIconName.Square,
            minWidth = 82.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp,
            textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp)
        )
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
            name = name.ifBlank { stringResource(MR.strings.file_preview_fallback_markdown) },
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
                    title = stringResource(MR.strings.file_preview_markdown_unavailable),
                    detail = error.orEmpty().ifBlank { stringResource(MR.strings.file_preview_markdown_open_with_hint) },
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
        SlockFilePreviewPanelHeader(
            name = name.ifBlank { stringResource(MR.strings.file_preview_fallback_text_file) },
            meta = meta,
            statusLabel = null
        )
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
    fallbackSource: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderLabel: String? = null,
    backgroundColor: com.tencent.kuikly.compose.ui.graphics.Color = SlockColor.SurfaceWhite,
    borderWidth: com.tencent.kuikly.compose.ui.unit.Dp = SlockStroke.Default,
    zoomable: Boolean = true
) {
    SlockAspectFitImagePreviewSurface(
        source = source,
        fallbackSource = fallbackSource,
        contentDescription = contentDescription,
        placeholderLabel = placeholderLabel ?: stringResource(MR.strings.file_preview_image_placeholder),
        modifier = modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        borderWidth = borderWidth,
        zoomable = zoomable,
        showLoadingIndicator = true
    )
}

internal fun SlockFilePreviewPayload.shouldRenderImagePreview(): Boolean =
    kind == "image" || (kind == "loading" && requestedKind == "image" && !imageUrl.isNullOrBlank())

internal fun SlockFilePreviewPayload.currentImageThumbnailUrl(): String? =
    imageGallery
        .takeIf { it.isNotEmpty() }
        ?.let { gallery -> gallery[imageGalleryIndex.coerceIn(gallery.indices)] }
        ?.thumbnailUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: imageUrl?.trim()?.takeIf { kind == "loading" && requestedKind == "image" && it.isNotBlank() }

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
