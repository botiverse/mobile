package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.kuikly.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.distinctUntilChanged

private const val ThreadFilesVisualPreviewUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20128%20128%22%3E%3Crect%20width%3D%22128%22%20height%3D%22128%22%20fill%3D%22%23FDE047%22%2F%3E%3Ccircle%20cx%3D%2288%22%20cy%3D%2240%22%20r%3D%2220%22%20fill%3D%22%23F472B6%22%2F%3E%3Cpath%20d%3D%22M16%20104L48%2068l22%2024%2018-16%2024%2028H16z%22%20fill%3D%22%23000000%22%2F%3E%3C%2Fsvg%3E"

private const val ThreadFilesAutoLoadPrefetchRows = 4

@Composable
internal fun ThreadFilesVisualScreen() {
    Box(modifier = Modifier.width(390.dp).height(360.dp)) {
        ThreadFilesContent(
            payload = threadFilesVisualPayload(),
            attachmentOpenStates =
                mapOf(
                    "visual-file-pdf" to
                        ThreadAttachmentOpenUiState(
                            status = "downloading",
                            progress =
                                BridgeAttachmentDownloadProgress(
                                    downloadId = "visual-files-download",
                                    status = "downloading",
                                    percent = 44,
                                    downloadedBytes = 192_000,
                                    totalBytes = 438_272
                                )
                        )
                ),
            onEvent = { _, _ -> }
        )
    }
}

@Serializable
internal data class KuiklyThreadFilesPayload(
    val instanceId: String = "",
    val status: String = "loading",
    val items: List<KuiklyThreadFileItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    val errorCode: String? = null,
    val errorServerMessage: String? = null,
    val emptyTitle: String = "No files yet",
    val emptyDetail: String = "Files shared in this thread will show up here."
)

@Serializable
internal data class KuiklyThreadFileItem(
    val attachmentId: String,
    val messageId: String = "",
    val channelId: String = "",
    val name: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val createdAtLabel: String = "",
    val sourceLabel: String = "",
    val sourceType: String = "",
    val sourceChannelId: String = "",
    val sourceParentMessageId: String = "",
    val sourceParentMessageShortId: String = "",
    val mimeType: String = "",
    val thumbnailUrl: String? = null
)

internal fun threadFileCreatedAtValue(value: String?): String = value.orEmpty()

private fun threadFilesVisualPayload(): KuiklyThreadFilesPayload =
    KuiklyThreadFilesPayload(
        instanceId = "visual-thread-files-list",
        status = "ready",
        hasMore = true,
        nextCursor = "next-files-page",
        items =
            listOf(
                KuiklyThreadFileItem(
                    attachmentId = "visual-file-image",
                    messageId = "msg-files-image",
                    channelId = "visual-thread-composer",
                    name = "composer-preview.png",
                    sizeLabel = "128 KB",
                    createdAtLabel = "Jun 25, 10:24",
                    sourceLabel = "Channel",
                    sourceType = "channel",
                    sourceChannelId = "visual-thread-composer",
                    mimeType = "image/png",
                    thumbnailUrl = ThreadFilesVisualPreviewUri
                ),
                KuiklyThreadFileItem(
                    attachmentId = "visual-file-pdf",
                    messageId = "msg-files-pdf",
                    channelId = "visual-thread-composer",
                    name = "release-notes.pdf",
                    sizeLabel = "428 KB",
                    createdAtLabel = "Jun 25, 10:17",
                    sourceLabel = "Thread #f8e569cb",
                    sourceType = "thread",
                    sourceChannelId = "visual-thread-composer",
                    sourceParentMessageId = "parent-files",
                    sourceParentMessageShortId = "f8e569cb",
                    mimeType = "application/pdf"
                ),
                KuiklyThreadFileItem(
                    attachmentId = "visual-file-zip",
                    messageId = "msg-files-zip",
                    channelId = "visual-thread-composer",
                    name = "android-smoke-log.zip",
                    sizeLabel = "1.8 MB",
                    createdAtLabel = "Jun 25, 10:08",
                    sourceLabel = "Channel",
                    sourceType = "channel",
                    sourceChannelId = "visual-thread-composer",
                    mimeType = "application/zip"
                )
            )
    )

internal data class ThreadAttachmentOpenUiState(
    val status: String,
    val message: String = "",
    val downloadId: String = "",
    val progress: BridgeAttachmentDownloadProgress? = null
)

internal fun ThreadAttachmentOpenUiState?.threadAttachmentIsOpening(): Boolean =
    this?.status == "downloading" || this?.status == "paused"

internal fun ThreadAttachmentOpenUiState?.threadAttachmentTerminalStatusResource(): StringResource? =
    when (this?.status) {
        "cancelled" -> MR.strings.file_preview_download_stopped
        "missing" -> MR.strings.file_preview_error_download_attachment
        "no_app" -> MR.strings.file_preview_error_no_app
        "storage_failed" -> MR.strings.file_preview_error_storage_unavailable
        "download_failed" -> MR.strings.file_preview_download_failed
        "failed" -> MR.strings.file_preview_error_open_attachment
        else -> null
    }

@Composable
internal fun ThreadFilesContent(
    payload: KuiklyThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    autoLoadRouteListKey: String = payload.instanceId,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.Top
    ) {
        when (payload.status) {
            "loading" -> ThreadFilesStateMessage(stringResource(MR.strings.thread_files_loading), null)
            "error" -> ThreadFilesError(payload.errorCode ?: "unknown_error") { onEvent("retry", null) }
            "no_permission" ->
                if (payload.errorServerMessage.isEdgeBlockedMarker()) {
                    ThreadFilesStateMessage(
                        stringResource(MR.strings.thread_state_edge_blocked),
                        stringResource(MR.strings.error_edge_blocked)
                    )
                } else {
                    ThreadFilesStateMessage(
                        stringResource(MR.strings.thread_files_no_permission),
                        payload.errorCode ?: "missing capability"
                    )
                }
            "empty" -> ThreadFilesStateMessage(stringResource(MR.strings.thread_files_empty_title), stringResource(MR.strings.thread_files_empty_detail))
            else -> ThreadFilesList(payload, attachmentOpenStates, autoLoadRouteListKey, onEvent)
        }
    }
}

@Composable
private fun ThreadFilesList(
    payload: KuiklyThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    autoLoadRouteListKey: String,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    if (payload.items.isEmpty()) {
        ThreadFilesStateMessage(stringResource(MR.strings.thread_files_empty_title), stringResource(MR.strings.thread_files_empty_detail))
        return
    }
    val listState = rememberLazyListState()
    var autoLoadFence by remember { mutableStateOf(ThreadFilesAutoLoadFence()) }
    LaunchedEffect(
        listState,
        autoLoadRouteListKey,
        payload.items.size,
        payload.hasMore,
        payload.loadingMore,
        payload.nextCursor,
        payload.errorCode
    ) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.maxOfOrNull { item -> item.index } ?: -1
        }
            .distinctUntilChanged()
            .collect { lastVisibleItemIndex ->
                val transition =
                    threadFilesAutoLoadTransition(
                        fence = autoLoadFence,
                        routeListKey = autoLoadRouteListKey,
                        itemCount = payload.items.size,
                        lastVisibleItemIndex = lastVisibleItemIndex,
                        hasMore = payload.hasMore,
                        loadingMore = payload.loadingMore,
                        nextCursor = payload.nextCursor,
                        errorCode = payload.errorCode
                    )
                autoLoadFence = transition.fence
                if (transition.requestedCursor != null) {
                    onEvent("loadMore", null)
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(payload.items.size, key = { payload.items[it].attachmentId }) { index ->
            ThreadFileRow(
                item = payload.items[index],
                openState = attachmentOpenStates[payload.items[index].attachmentId],
                onOpen = { onEvent("open", payload.items[index].attachmentId) },
                onDownload = { onEvent("download", payload.items[index].attachmentId) },
                onOpenSource = { onEvent("source", payload.items[index].attachmentId) }
            )
        }
        if (payload.hasMore) {
            item {
                ThreadFilesLoadMore(
                    loading = payload.loadingMore,
                    onClick = { onEvent("loadMore", null) },
                    label =
                        if (payload.errorCode != null) {
                            stringResource(MR.strings.common_retry)
                        } else {
                            "Load more"
                        }
                )
            }
        }
    }
}

internal data class ThreadFilesAutoLoadFence(
    val routeListKey: String = "",
    val lastAutoRequestedCursor: String? = null
)

internal data class ThreadFilesAutoLoadTransition(
    val fence: ThreadFilesAutoLoadFence,
    val requestedCursor: String? = null
)

internal fun threadFilesAutoLoadTransition(
    fence: ThreadFilesAutoLoadFence,
    routeListKey: String,
    itemCount: Int,
    lastVisibleItemIndex: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    nextCursor: String?,
    errorCode: String?
): ThreadFilesAutoLoadTransition {
    val routeFence =
        if (fence.routeListKey == routeListKey) {
            fence
        } else {
            ThreadFilesAutoLoadFence(routeListKey = routeListKey)
        }
    val cursor = nextCursor?.takeIf { it.isNotBlank() }
    val shouldRequest =
        cursor != null &&
            cursor != routeFence.lastAutoRequestedCursor &&
            threadFilesShouldRequestLoadMore(
                itemCount = itemCount,
                lastVisibleItemIndex = lastVisibleItemIndex,
                hasMore = hasMore,
                loadingMore = loadingMore,
                nextCursor = cursor,
                errorCode = errorCode
            )
    return if (shouldRequest) {
        ThreadFilesAutoLoadTransition(
            fence = routeFence.copy(lastAutoRequestedCursor = cursor),
            requestedCursor = cursor
        )
    } else {
        ThreadFilesAutoLoadTransition(fence = routeFence)
    }
}

internal fun threadFilesShouldRequestLoadMore(
    itemCount: Int,
    lastVisibleItemIndex: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    nextCursor: String?,
    errorCode: String?,
    prefetchRows: Int = ThreadFilesAutoLoadPrefetchRows
): Boolean {
    if (!hasMore || loadingMore || errorCode != null || nextCursor.isNullOrBlank()) return false
    if (itemCount <= 0 || lastVisibleItemIndex < 0) return false
    val triggerIndex = (itemCount - 1 - prefetchRows.coerceAtLeast(0)).coerceAtLeast(0)
    return lastVisibleItemIndex >= triggerIndex
}

@Composable
private fun ThreadFileRow(
    item: KuiklyThreadFileItem,
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onOpenSource: () -> Unit
) {
    val isOpening = openState.threadAttachmentIsOpening()
    val canOpenSource = item.threadFileHasSourceTarget()
    val openIfIdle = if (isOpening) ({}) else onOpen
    val downloadIfIdle = if (isOpening) ({}) else onDownload
    val openSourceIfIdle = if (isOpening || !canOpenSource) ({}) else onOpenSource
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.clickable(onClick = openIfIdle)) {
            ThreadFileThumbnail(item)
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = openIfIdle),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = item.name,
                style = SlockType.Input.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = SlockColor.TextBlack
                ),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ThreadFileMetaLine(item)
            openState.localizedThreadAttachmentStatusLabel()?.let { statusLabel ->
                Text(
                    text = statusLabel,
                    style = SlockType.Input.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = if (isOpening) SlockColor.TextBlack else SlockColor.TextMutedGray
                    ),
                    color = if (isOpening) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThreadFileIconActionButton(
                iconName = SlockLucideIconName.ExternalLink,
                enabled = !isOpening && canOpenSource,
                onClick = openSourceIfIdle
            )
            ThreadFileIconActionButton(
                iconName = if (isOpening) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Download,
                enabled = !isOpening,
                onClick = downloadIfIdle
            )
        }
    }
}

@Composable
internal fun ThreadAttachmentOpenUiState?.localizedThreadAttachmentStatusLabel(): String? {
    val state = this
    return when (state?.status) {
        "downloading", "paused" ->
            slockFilePreviewLocalizedActiveDownloadStatusLabel(state.progress, state.status)
        else -> state.threadAttachmentTerminalStatusResource()?.let { resource -> stringResource(resource) }
    }
}

@Composable
private fun ThreadFileThumbnail(item: KuiklyThreadFileItem) {
    val thumbnailUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
    val isImage = slockFilePreviewIsImageAttachment(fileName = item.name, mimeType = item.mimeType)
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (isImage && thumbnailUrl != null) {
            SlockAspectFitImagePreviewSurface(
                source = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                borderWidth = 0.dp,
                contentScale = SlockImageThumbnailContentScale
            )
        }
        if (!(isImage && thumbnailUrl != null)) {
            SlockLucideIcon(
                name = item.threadFileTypeIconName().wireName,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ThreadFileMetaLine(item: KuiklyThreadFileItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item.displaySizeLabel().takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = SlockType.Input.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = SlockColor.TextMutedGray
                ),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.createdAtLabel.takeIf { it.isNotBlank() }?.let { label ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(
                    name = SlockLucideIconName.Clock3.wireName,
                    modifier = Modifier.size(12.dp),
                    color = SlockColor.TextMutedGrayArgb
                )
                SlockRelativeTimestampText(
                    value = label,
                    style = SlockType.Input.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = SlockColor.TextMutedGray
                    ),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun KuiklyThreadFileItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let(::formatAttachmentSizeLabel) ?: sizeLabel

internal fun KuiklyThreadFileItem.threadFileHasSourceTarget(): Boolean =
    threadFileSourceFocusMessageId() != null

internal fun KuiklyThreadFileItem.threadFileSourceFocusMessageId(): String? =
    messageId.takeIf { it.isNotBlank() }

@Composable
private fun ThreadFileIconActionButton(
    iconName: SlockLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(
        iconName = iconName,
        onClick = onClick,
        enabled = enabled,
        size = 28.dp,
        iconSize = 14.dp,
        iconSpinning = iconName == SlockLucideIconName.LoaderCircle
    )
}

private fun KuiklyThreadFileItem.threadFileTypeIconName(): SlockLucideIconName {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val lowerName = name.lowercase()
    return when {
        slockFilePreviewIsImageAttachment(fileName = name, mimeType = mimeType) -> SlockLucideIconName.Image
        normalizedMime.startsWith("video/") -> SlockLucideIconName.FileVideoCamera
        normalizedMime == "application/pdf" || lowerName.endsWith(".pdf") -> SlockLucideIconName.FileText
        normalizedMime.contains("zip") ||
            normalizedMime.contains("archive") ||
            lowerName.endsWith(".zip") ||
            lowerName.endsWith(".tar") ||
            lowerName.endsWith(".gz") -> SlockLucideIconName.Archive
        else -> SlockLucideIconName.File
    }
}

@Composable
private fun ThreadFilesLoadMore(
    loading: Boolean,
    onClick: () -> Unit,
    label: String = "Load more"
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        SlockBrutalActionButton(
            label = if (loading) stringResource(MR.strings.common_loading) else label,
            onClick = onClick,
            enabled = !loading,
            minWidth = 112.dp,
            minHeight = 36.dp
        )
    }
}

@Composable
private fun ThreadFilesError(errorCode: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(MR.strings.thread_files_error_load),
            style = SlockType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = errorCode,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        ThreadFilesLoadMore(loading = false, onClick = onRetry, label = stringResource(MR.strings.common_retry))
    }
}

@Composable
private fun ThreadFilesStateMessage(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = SlockType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
