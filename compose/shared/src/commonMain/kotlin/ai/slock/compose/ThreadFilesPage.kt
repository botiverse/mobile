package ai.slock.compose

import ai.slock.compose.base.Utils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Image
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
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val THREAD_FILES_PAGE = "compose_thread_files"

@Page(THREAD_FILES_PAGE, supportInLocal = true)
internal class ComposeThreadFilesPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadFilesScreen() }
    }
}

@Serializable
internal data class KuiklyThreadFilesPayload(
    val instanceId: String = "",
    val status: String = "loading",
    val items: List<KuiklyThreadFileItem> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
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

@Composable
private fun ThreadFilesScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload =
        rememberThreadFilesPayload(
            payload = pager.pageData.params.optString("payload"),
            instanceId = pager.pageData.params.optString("instanceId")
        )
    LaunchedEffect(payload) {
        bridge.log(
            "thread_files_compose_render instanceId=${payload.instanceId} status=${payload.status} items=${payload.items.size} hasMore=${payload.hasMore} loadingMore=${payload.loadingMore} error=${payload.errorCode.orEmpty()}"
        )
    }
    ThreadFilesContent(
        payload = payload,
        onEvent = { action, attachmentId ->
            bridge.log(
                "thread_files_compose_event instanceId=${payload.instanceId} action=$action attachmentId=${attachmentId.orEmpty()}"
            )
            bridge.filesEvent(
                instanceId = payload.instanceId,
                action = action,
                attachmentId = attachmentId
            )
        }
    )
}

@Composable
private fun rememberThreadFilesPayload(payload: String, instanceId: String): KuiklyThreadFilesPayload =
    androidx.compose.runtime.remember(payload, instanceId) {
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
    }

@Composable
internal fun ThreadFilesContent(
    payload: KuiklyThreadFilesPayload,
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
            else -> ThreadFilesList(payload, onEvent)
        }
    }
}

@Composable
private fun ThreadFilesList(
    payload: KuiklyThreadFilesPayload,
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
    onOpen: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.clickable(onClick = onOpen)) {
            ThreadFileThumbnail(item)
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.name,
                style = SlockType.Button.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(item.sizeLabel, item.createdAtLabel).filter { it.isNotBlank() }.joinToString("  "),
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.sourceLabel.isNotBlank()) {
                ThreadFileSourcePill(item.sourceLabel)
            }
        }
        ThreadFileDownloadButton(onClick = onDownload)
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
            Image(
                painter = rememberAsyncImagePainter(thumbnailUrl),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = if (isImage && thumbnailUrl != null) "" else if (isImage) "IMG" else "DOC",
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
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
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadFileDownloadButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .padding(end = 3.dp, bottom = 3.dp)
            .background(SlockColor.ShadowBlack)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 0.dp, top = 0.dp, end = 3.dp, bottom = 3.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "DL",
                style = SlockType.Button.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ThreadFilesLoadMore(loading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = !loading, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (loading) "Loading..." else "Load more",
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
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
            text = "Files failed to load",
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            text = errorCode,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        ThreadFilesLoadMore(loading = false, onClick = onRetry)
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
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
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
