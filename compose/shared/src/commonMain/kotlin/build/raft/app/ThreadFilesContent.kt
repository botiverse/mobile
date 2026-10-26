package build.raft.app

import build.raft.app.resources.*
import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.format.RaftFormat
import build.raft.app.data.isEdgeBlockedMarker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import build.raft.app.components.RaftStorePullToRefreshBox
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.distinctUntilChanged

// The web fixture's SVG art (yellow ground, pink sun, black mountain), base64-encoded: Coil's
// data-URI fetcher only reads base64 payloads, so the url-encoded `;utf8,` form drew nothing.
private const val ThreadFilesVisualPreviewUri =
    "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMjggMTI4Ij48cmVjdCB3aWR0aD0iMTI4IiBoZWlnaHQ9IjEyOCIgZmlsbD0iI0ZERTA0NyIvPjxjaXJjbGUgY3g9Ijg4IiBjeT0iNDAiIHI9IjIwIiBmaWxsPSIjRjQ3MkI2Ii8+PHBhdGggZD0iTTE2IDEwNEw0OCA2OGwyMiAyNCAxOC0xNiAyNCAyOEgxNnoiIGZpbGw9IiMwMDAwMDAiLz48L3N2Zz4="

private const val ThreadFilesAutoLoadPrefetchRows = 4

// components.thread.files.list: the web case renders ChannelFilesPanel in a 390x360 box with the
// three fixture files at rest (no download in flight), times in the owner's Asia/Shanghai zone.
@Composable
internal fun ThreadFilesVisualScreen() {
    Box(modifier = Modifier.width(390.dp).height(360.dp)) {
        ThreadFilesContent(
            payload = threadFilesVisualPayload(),
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
                    createdAtLabel = "Jun 25, 18:24",
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
                    createdAtLabel = "Jun 25, 18:17",
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
                    createdAtLabel = "Jun 25, 18:08",
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
        "cancelled" -> Res.string.file_preview_download_stopped
        "missing" -> Res.string.file_preview_error_download_attachment
        "no_app" -> Res.string.file_preview_error_no_app
        "storage_failed" -> Res.string.file_preview_error_storage_unavailable
        "download_failed" -> Res.string.file_preview_download_failed
        "failed" -> Res.string.file_preview_error_open_attachment
        else -> null
    }

@Composable
internal fun ThreadFilesContent(
    payload: RaftThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    autoLoadRouteListKey: String = payload.instanceId,
    /** The store's pull-to-refresh is reloading the list (see `SharedThreadFilesIntent.PullRefresh`). */
    refreshing: Boolean = false,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
        verticalArrangement = Arrangement.Top
    ) {
        when (payload.status) {
            "loading" -> ThreadFilesStateMessage(stringResource(Res.string.thread_files_loading), null)
            "error" -> ThreadFilesError(payload.errorCode ?: "unknown_error") { onEvent("retry", null) }
            "no_permission" ->
                if (payload.errorServerMessage.isEdgeBlockedMarker()) {
                    ThreadFilesStateMessage(
                        stringResource(Res.string.thread_state_edge_blocked),
                        stringResource(Res.string.error_edge_blocked)
                    )
                } else {
                    ThreadFilesStateMessage(
                        stringResource(Res.string.thread_files_no_permission),
                        payload.errorCode ?: "missing capability"
                    )
                }
            "empty" -> ThreadFilesStateMessage(stringResource(Res.string.thread_files_empty_title), stringResource(Res.string.thread_files_empty_detail))
            else -> ThreadFilesList(payload, attachmentOpenStates, autoLoadRouteListKey, refreshing, onEvent)
        }
    }
}

@Composable
private fun ThreadFilesList(
    payload: RaftThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    autoLoadRouteListKey: String,
    refreshing: Boolean,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    if (payload.items.isEmpty()) {
        ThreadFilesStateMessage(stringResource(Res.string.thread_files_empty_title), stringResource(Res.string.thread_files_empty_detail))
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
    RaftStorePullToRefreshBox(
        refreshing = refreshing,
        onRefresh = { onEvent("pullRefresh", null) },
        modifier = Modifier.fillMaxSize(),
    ) {
    // Web `FilesList`: `gap-2`, `py-3` and the phone's `px-4`.
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
                            stringResource(Res.string.common_retry)
                        } else {
                            stringResource(Res.string.thread_files_load_more)
                        }
                )
            }
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

// raft-ui Files row as the web Files panel draws it on a phone (ChannelFilesPanelFileRow +
// AvatarListRow's card row): a `Card` (layer-card, 1dp line-hairline, container radius) with
// `px-4 py-3`; the 56dp type visual and the name/meta column top-aligned with `gap-3`; the
// actions centred at the end. Under the web's 560dp container breakpoint the two inline
// actions collapse into one ghost `icon-sm` overflow button (EllipsisVertical).
@Composable
private fun ThreadFileRow(
    item: RaftThreadFileItem,
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onOpenSource: () -> Unit
) {
    val isOpening = openState.threadAttachmentIsOpening()
    val canOpenSource = item.threadFileHasSourceTarget()
    val openIfIdle = if (isOpening) ({}) else onOpen
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RaftColor.Card, shape)
            // The hairline flattened onto the card, so its edge rounds like the web's (an 8-bit
            // alpha blend at draw time lands one level darker).
            .border(RaftAppStroke.Thin, RaftColor.BorderHairline.compositeOver(RaftColor.Card), shape)
            // CSS `border` + `px-4 py-3`: the 1dp edge takes layout space on the web.
            .padding(horizontal = 16.dp + RaftAppStroke.Thin, vertical = 12.dp + RaftAppStroke.Thin),
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
                // `text-body font-semibold text-foreground`, one truncated line.
                Text(
                    text = item.name,
                    style = RaftType.ListItem.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ThreadFileMetaLine(item)
                openState.localizedThreadAttachmentStatusLabel()?.let { statusLabel ->
                    Text(
                        text = statusLabel,
                        style = ThreadFileMetaStyle.copy(
                            color = if (isOpening) RaftColor.TextBlack else RaftColor.ForegroundHint
                        ),
                        color = if (isOpening) RaftColor.TextBlack else RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        ThreadFileOverflowMenu(
            fileName = item.name,
            busy = isOpening,
            canOpenSource = canOpenSource,
            onOpenSource = onOpenSource,
            onDownload = onDownload,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

// `font-mono text-mono-meta text-foreground-hint` (Space Mono 12/16).
private val ThreadFileMetaStyle: TextStyle
    get() = RaftType.Caption.copy(
        fontFamily = RaftMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RaftColor.ForegroundHint
    )

@Composable
internal fun ThreadAttachmentOpenUiState?.localizedThreadAttachmentStatusLabel(): String? {
    val state = this
    return when (state?.status) {
        "downloading", "paused" ->
            raftFilePreviewLocalizedActiveDownloadStatusLabel(state.progress, state.status)
        else -> state.threadAttachmentTerminalStatusResource()?.let { resource -> stringResource(resource) }
    }
}

// Web `ChannelFileVisual`: `size-14 rounded-control border border-line-hairline bg-fill-muted
// text-foreground-muted`, clipped; an image thumbnail fills it (object-cover), any other type
// shows its 18dp type icon. The file-type chip sits in the bottom-left corner.
@Composable
private fun ThreadFileThumbnail(item: RaftThreadFileItem) {
    val thumbnailUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
    val isImage = raftFilePreviewIsImageAttachment(fileName = item.name, mimeType = item.mimeType)
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(RaftColor.FillMuted, shape)
            .border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)
            // CSS `border`: the image, the icon and the corner chip sit inside the 1dp edge.
            .padding(RaftAppStroke.Thin),
        contentAlignment = Alignment.Center
    ) {
        if (isImage && thumbnailUrl != null) {
            RaftAspectFitImagePreviewSurface(
                source = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                backgroundColor = RaftColor.FillMuted,
                borderWidth = 0.dp,
                contentScale = RaftImageThumbnailContentScale
            )
        } else {
            RaftLucideIcon(
                name = item.threadFileTypeIconName().wireName,
                modifier = Modifier.size(18.dp),
                color = raftArgbHex(RaftColor.TextMuted)
            )
        }
        threadFileTypeBadgeLabel(item.name)?.let { label ->
            ThreadFileTypeBadge(label = label, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

// raft-ui `FilePreviewBadge`: a 28x14dp chip, sticker radius, `bg-fill-muted` with a 1dp
// `line-hairline` ring, Space Mono 10sp bold `foreground-muted`. [inline] is the chip before a
// name (the message attachment card), with the ring drawn all round outside it; otherwise it
// tags a file icon's corner.
@Composable
internal fun ThreadFileTypeBadge(label: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    if (inline) {
        val ring = RaftColor.BorderHairline
        Box(
            modifier = modifier
                .size(width = 28.dp, height = 14.dp)
                .drawBehind {
                    // `ring-1` is a box-shadow: outside the chip, taking no layout space.
                    val stroke = RaftAppStroke.Thin.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(-stroke, -stroke),
                        size = Size(size.width + stroke * 2, size.height + stroke * 2),
                        cornerRadius = CornerRadius(RaftAppRadius.Sticker.toPx() + stroke)
                    )
                }
                .background(RaftColor.FillMuted, shape),
            contentAlignment = Alignment.Center
        ) {
            ThreadFileTypeBadgeLabel(label)
        }
        return
    }
    val ringShape = RoundedCornerShape(RaftAppRadius.Sticker + RaftAppStroke.Thin)
    // `ring-1` is drawn outside the chip; in the corner of the clipped visual only its top and
    // end edges show, so the ring is a 1dp-larger plate behind the chip's top-end.
    Box(
        modifier = modifier
            .size(width = 28.dp + RaftAppStroke.Thin, height = 14.dp + RaftAppStroke.Thin)
            .background(RaftColor.BorderHairline, ringShape),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 14.dp)
                .background(RaftColor.FillMuted, shape),
            contentAlignment = Alignment.Center
        ) {
            ThreadFileTypeBadgeLabel(label)
        }
    }
}

@Composable
private fun ThreadFileTypeBadgeLabel(label: String) {
    Text(
        text = label,
        style = RaftType.Caption.copy(
            fontFamily = raftMonoFamilyForWeight(FontWeight.Bold),
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            color = RaftColor.TextMuted
        ),
        color = RaftColor.TextMuted,
        maxLines = 1,
        softWrap = false
    )
}

private val ThreadFileBadgedExtensions =
    setOf(
        "pdf", "doc", "docx", "txt", "md", "markdown", "csv", "json", "zip", "html",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "mp4", "mov"
    )

// Web `attachmentBadgeLabel`: the upper-cased extension for the kinds that get a chip.
internal fun threadFileTypeBadgeLabel(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension.takeIf { it in ThreadFileBadgedExtensions }?.uppercase()
}

@Composable
private fun ThreadFileMetaLine(item: RaftThreadFileItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item.displaySizeLabel().takeIf { it.isNotBlank() }?.let { label ->
            Text(
                text = label,
                style = ThreadFileMetaStyle,
                color = RaftColor.ForegroundHint,
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
                    color = raftArgbHex(RaftColor.ForegroundHint)
                )
                RaftRelativeTimestampText(
                    value = label,
                    style = ThreadFileMetaStyle,
                    color = RaftColor.ForegroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun RaftThreadFileItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let { RaftFormat.bytes(it) } ?: sizeLabel

internal fun RaftThreadFileItem.threadFileHasSourceTarget(): Boolean =
    threadFileSourceFocusMessageId() != null

internal fun RaftThreadFileItem.threadFileSourceFocusMessageId(): String? =
    messageId.takeIf { it.isNotBlank() }

// Web `ChannelFileOverflowMenu`: a ghost `icon-sm` button (EllipsisVertical) opening a menu
// with Jump to Original Message and Download File. While the file is downloading the button
// shows the spinner and the menu stays shut.
@Composable
private fun ThreadFileOverflowMenu(
    fileName: String,
    busy: Boolean,
    canOpenSource: Boolean,
    onOpenSource: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        RaftDesignSystemTheme {
            RaftIconButton(
                onClick = { if (!busy) expanded = true },
                tooltip = stringResource(Res.string.thread_files_file_actions, fileName),
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                loading = busy
            ) {
                RaftLucideIcon(
                    name = RaftLucideIconName.EllipsisVertical,
                    // `size="icon-sm"` draws every glyph at 18dp, overriding the 14 the web passes.
                    modifier = Modifier.size(18.dp),
                    color = raftArgbHex(RaftColor.TextBlack)
                )
            }
            RaftDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                RaftMenuItem(
                    text = stringResource(Res.string.thread_files_jump_to_message),
                    enabled = canOpenSource,
                    leadingIcon = {
                        RaftLucideIcon(RaftLucideIconName.MapPin, Modifier.size(16.dp), color = raftArgbHex(RaftColor.TextBlack))
                    },
                    onClick = {
                        expanded = false
                        onOpenSource()
                    }
                )
                RaftMenuItem(
                    text = stringResource(Res.string.thread_files_download_file),
                    leadingIcon = {
                        RaftLucideIcon(RaftLucideIconName.Download, Modifier.size(16.dp), color = raftArgbHex(RaftColor.TextBlack))
                    },
                    onClick = {
                        expanded = false
                        onDownload()
                    }
                )
            }
        }
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

// Web: `flex justify-center p-4` holding an outline `size="sm"` Button (32dp, ink edge), after
// the list's 12dp bottom padding (the list's 8dp item gap already precedes this item).
@Composable
private fun ThreadFilesLoadMore(
    loading: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        RaftDesignSystemTheme {
            RaftButton(
                text = if (loading) stringResource(Res.string.common_loading) else label,
                onClick = onClick,
                enabled = !loading,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md
            )
        }
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
            text = stringResource(Res.string.thread_files_error_load),
            style = RaftType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text = errorCode,
            style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
        ThreadFilesLoadMore(loading = false, onClick = onRetry, label = stringResource(Res.string.common_retry))
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
