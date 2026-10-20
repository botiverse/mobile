package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource
import androidx.compose.ui.unit.sp
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
internal data class RaftThreadFilesPayload(
    val instanceId: String = "",
    val status: String = "loading",
    val items: List<RaftThreadFileItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    val errorCode: String? = null,
    val errorServerMessage: String? = null,
    val emptyTitle: String = "No files yet",
    val emptyDetail: String = "Files shared in this thread will show up here."
)

@Serializable
internal data class RaftThreadFileItem(
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

private fun threadFilesVisualPayload(): RaftThreadFilesPayload =
    RaftThreadFilesPayload(
        instanceId = "visual-thread-files-list",
        status = "ready",
        hasMore = true,
        nextCursor = "next-files-page",
        items =
            listOf(
                RaftThreadFileItem(
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
                RaftThreadFileItem(
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
                RaftThreadFileItem(
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
    payload: RaftThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    autoLoadRouteListKey: String = payload.instanceId,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite),
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
    payload: RaftThreadFilesPayload,
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
        // Web ChannelFilesPanel `FilesList`: `p-3 gap-2`.
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            stringResource(MR.strings.thread_files_load_more)
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

/**
 * One file of the Files tab, as the web `ChannelFilesPanelFileRow` (`AvatarListRow` in a
 * `SurfaceListItem`): a raft-ui Card (container radius, 1dp `line-hairline` edge, `layer-card`)
 * with `px-4 py-3`, the 56dp thumbnail top-aligned, the file name in semibold body over Space
 * Mono meta, and the ghost `icon-sm` jump / download buttons centred on the right.
 */
@Composable
private fun ThreadFileRow(
    item: RaftThreadFileItem,
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onOpenSource: () -> Unit
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val isOpening = openState.threadAttachmentIsOpening()
    val canOpenSource = item.threadFileHasSourceTarget()
    val openIfIdle = if (isOpening) ({}) else onOpen
    val downloadIfIdle = if (isOpening) ({}) else onDownload
    val openSourceIfIdle = if (isOpening || !canOpenSource) ({}) else onOpenSource
    // Inset: the card draws no padding of its own, so the row keeps the web's 16 / 12dp.
    RaftCard(modifier = Modifier.fillMaxWidth(), variant = RaftCardVariant.Inset) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable(onClick = openIfIdle),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThreadFileThumbnail(item)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RaftText(
                        text = item.name,
                        style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ThreadFileMetaLine(item)
                    openState.localizedThreadAttachmentStatusLabel()?.let { statusLabel ->
                        RaftText(
                            text = statusLabel,
                            style = typography.caption,
                            color = if (isOpening) colors.foreground else colors.foregroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThreadFileIconActionButton(
                    iconName = RaftLucideIconName.MapPin,
                    tooltip = stringResource(MR.strings.thread_files_jump_to_message),
                    enabled = !isOpening && canOpenSource,
                    onClick = openSourceIfIdle
                )
                ThreadFileIconActionButton(
                    iconName = RaftLucideIconName.Download,
                    tooltip = stringResource(MR.strings.thread_files_download_file),
                    enabled = true,
                    loading = isOpening,
                    onClick = downloadIfIdle
                )
            }
        }
    }
}

@Composable
internal fun ThreadAttachmentOpenUiState?.localizedThreadAttachmentStatusLabel(): String? {
    val state = this
    return when (state?.status) {
        "downloading", "paused" ->
            raftFilePreviewLocalizedActiveDownloadStatusLabel(state.progress, state.status)
        else -> state.threadAttachmentTerminalStatusResource()?.let { resource -> stringResource(resource) }
    }
}

@Composable
private fun ThreadFileThumbnail(item: RaftThreadFileItem) {
    val thumbnailUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
    val isImage = raftFilePreviewIsImageAttachment(fileName = item.name, mimeType = item.mimeType)
    // Web ChannelFileVisual: `size-14 rounded-control border border-line-muted bg-fill-muted
    // text-foreground-icon`, the image clipped to the radius.
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(colors.fillMuted, shape)
            .border(RaftStroke.Hairline, colors.lineMuted, shape),
        contentAlignment = Alignment.Center
    ) {
        if (isImage && thumbnailUrl != null) {
            RaftAspectFitImagePreviewSurface(
                source = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                borderWidth = 0.dp,
                contentScale = RaftImageThumbnailContentScale
            )
        }
        if (!(isImage && thumbnailUrl != null)) {
            RaftLucideIcon(
                name = item.threadFileTypeIconName().wireName,
                modifier = Modifier.size(18.dp),
                color = raftArgbHex(colors.foregroundIcon)
            )
        }
    }
}

@Composable
private fun ThreadFileMetaLine(item: RaftThreadFileItem) {
    // Web AvatarListRow subtitle: Space Mono `text-mono-meta` in `foreground-hint`, `gap-x-2`.
    val colors = RaftTheme.colors
    val metaStyle = RaftTheme.typography.monoMeta
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item.displaySizeLabel().takeIf { it.isNotBlank() }?.let { label ->
            RaftText(
                text = label,
                style = metaStyle,
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        item.createdAtLabel.takeIf { it.isNotBlank() }?.let { label ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftLucideIcon(
                    name = RaftLucideIconName.Clock3.wireName,
                    modifier = Modifier.size(12.dp),
                    color = raftArgbHex(colors.foregroundHint)
                )
                RaftRelativeTimestampText(
                    value = label,
                    style = metaStyle.copy(color = colors.foregroundHint),
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun RaftThreadFileItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let(::formatAttachmentSizeLabel) ?: sizeLabel

internal fun RaftThreadFileItem.threadFileHasSourceTarget(): Boolean =
    threadFileSourceFocusMessageId() != null

internal fun RaftThreadFileItem.threadFileSourceFocusMessageId(): String? =
    messageId.takeIf { it.isNotBlank() }

/** Web `Button size="icon-sm" variant="ghost"` with a 14px lucide glyph. */
@Composable
private fun ThreadFileIconActionButton(
    iconName: RaftLucideIconName,
    tooltip: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false
) {
    RaftIconButton(
        onClick = onClick,
        tooltip = tooltip,
        variant = RaftButtonVariant.Ghost,
        enabled = enabled,
        loading = loading,
        loadingLabel = tooltip
    ) {
        RaftContentLucideIcon(iconName, size = 14.dp)
    }
}

private fun RaftThreadFileItem.threadFileTypeIconName(): RaftLucideIconName {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    val lowerName = name.lowercase()
    return when {
        raftFilePreviewIsImageAttachment(fileName = name, mimeType = mimeType) -> RaftLucideIconName.Image
        normalizedMime.startsWith("video/") -> RaftLucideIconName.FileVideoCamera
        normalizedMime == "application/pdf" || lowerName.endsWith(".pdf") -> RaftLucideIconName.FileText
        normalizedMime.contains("zip") ||
            normalizedMime.contains("archive") ||
            lowerName.endsWith(".zip") ||
            lowerName.endsWith(".tar") ||
            lowerName.endsWith(".gz") -> RaftLucideIconName.Archive
        else -> RaftLucideIconName.File
    }
}

@Composable
private fun ThreadFilesLoadMore(
    loading: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        RaftButton(
            text = label,
            onClick = onClick,
            loading = loading,
            loadingLabel = stringResource(MR.strings.common_loading)
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
            style = RaftType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text = errorCode,
            style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
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
            style = RaftType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
