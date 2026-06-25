package ai.slock.compose

import ai.slock.compose.base.BridgeAttachmentDownloadProgress
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import androidx.compose.runtime.Composable
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
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class KuiklyThreadFilesPayload(
    val instanceId: String = "",
    val status: String = "loading",
    val items: List<KuiklyThreadFileItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    val errorCode: String? = null,
    val emptyTitle: String = "No files yet",
    val emptyDetail: String = "Files shared in this thread will show up here."
)

@Serializable
internal data class KuiklyThreadFileItem(
    val attachmentId: String,
    val name: String,
    val sizeLabel: String = "",
    val createdAtLabel: String = "",
    val sourceLabel: String = "",
    val mimeType: String = "",
    val thumbnailUrl: String? = null
)

internal data class ThreadAttachmentOpenUiState(
    val status: String,
    val message: String = "",
    val downloadId: String = "",
    val progress: BridgeAttachmentDownloadProgress? = null
)

internal fun ThreadAttachmentOpenUiState?.threadAttachmentIsOpening(): Boolean =
    this?.status == "downloading" || this?.status == "paused"

internal fun ThreadAttachmentOpenUiState?.threadAttachmentStatusLabel(): String? =
    when (this?.status) {
        "downloading", "paused" -> slockFilePreviewActiveDownloadStatusLabel(progress, this.status)
        "cancelled" -> message.ifBlank { "Download stopped" }
        "no_app" -> "No app can open this file"
        "storage_failed" -> "Storage unavailable"
        "failed" -> "Download failed"
        else -> null
    }

internal fun parseThreadFilesPayload(
    payload: String,
    instanceId: String
): KuiklyThreadFilesPayload =
    runCatching {
        Json {
            ignoreUnknownKeys = true
        }.decodeFromString(KuiklyThreadFilesPayload.serializer(), payload)
            .let { decoded ->
                if (decoded.instanceId.isBlank() && instanceId.isNotBlank()) {
                    decoded.copy(instanceId = instanceId)
                } else {
                    decoded
                }
            }
    }.getOrElse {
        KuiklyThreadFilesPayload(
            instanceId = instanceId,
            status = "error",
            errorCode = "payload_parse_failed"
        )
    }

@Composable
internal fun ThreadFilesContent(
    payload: KuiklyThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.Top
    ) {
        when (payload.status) {
            "loading" -> ThreadFilesStateMessage("Loading files...", null)
            "error" -> ThreadFilesError(payload.errorCode ?: "unknown_error") { onEvent("retry", null) }
            "no_permission" -> ThreadFilesStateMessage("No permission", payload.errorCode ?: "missing capability")
            "empty" -> ThreadFilesStateMessage(payload.emptyTitle, payload.emptyDetail)
            else -> ThreadFilesList(payload, attachmentOpenStates, onEvent)
        }
    }
}

@Composable
private fun ThreadFilesList(
    payload: KuiklyThreadFilesPayload,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState>,
    onEvent: (action: String, attachmentId: String?) -> Unit
) {
    if (payload.items.isEmpty()) {
        ThreadFilesStateMessage(payload.emptyTitle, payload.emptyDetail)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        items(payload.items.size, key = { payload.items[it].attachmentId }) { index ->
            ThreadFileRow(
                item = payload.items[index],
                openState = attachmentOpenStates[payload.items[index].attachmentId],
                onOpen = { onEvent("open", payload.items[index].attachmentId) },
                onDownload = { onEvent("download", payload.items[index].attachmentId) }
            )
        }
        if (payload.hasMore) {
            item {
                ThreadFilesLoadMore(
                    loading = payload.loadingMore,
                    onClick = { onEvent("loadMore", null) }
                )
            }
        }
    }
}

@Composable
private fun ThreadFileRow(
    item: KuiklyThreadFileItem,
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit,
    onDownload: () -> Unit
) {
    val isOpening = openState.threadAttachmentIsOpening()
    val openIfIdle = if (isOpening) ({}) else onOpen
    val downloadIfIdle = if (isOpening) ({}) else onDownload
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.clickable(onClick = openIfIdle)) {
            ThreadFileThumbnail(item)
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = openIfIdle),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
            Text(
                text = listOf(item.sizeLabel, item.createdAtLabel).filter { it.isNotBlank() }.joinToString("  "),
                style = SlockType.Input.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = SlockColor.TextMutedGray
                ),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.sourceLabel.isNotBlank()) {
                ThreadFileSourcePill(item.sourceLabel)
            }
            openState.threadAttachmentStatusLabel()?.let { statusLabel ->
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
        ThreadFileDownloadButton(isOpening = isOpening, onClick = downloadIfIdle)
    }
}

@Composable
private fun ThreadFileThumbnail(item: KuiklyThreadFileItem) {
    val thumbnailUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() }
    val isImage = item.mimeType.startsWith("image/")
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (isImage && thumbnailUrl != null) {
            SlockAspectFitImagePreviewSurface(
                source = thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                borderWidth = 0.dp
            )
        }
        if (!(isImage && thumbnailUrl != null)) {
            SlockLucideIcon(
                name = if (isImage) "image" else "file",
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ThreadFileSourcePill(text: String) {
    Box(
        modifier = Modifier
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = SlockType.Input.copy(
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = SlockColor.TextBlack
            ),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadFileDownloadButton(isOpening: Boolean, onClick: () -> Unit) {
    SlockBrutalIconButton(
        iconName = if (isOpening) SlockLucideIconName.Loader else SlockLucideIconName.Download,
        onClick = onClick,
        enabled = !isOpening,
        size = 36.dp,
        iconSize = 18.dp
    )
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
            label = if (loading) "Loading..." else label,
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
            text = "Files failed to load",
            style = SlockType.Input.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = errorCode,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        ThreadFilesLoadMore(loading = false, onClick = onRetry, label = "Retry")
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
