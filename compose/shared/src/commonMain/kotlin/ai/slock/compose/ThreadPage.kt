package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.base.bridgeModule
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.tencent.kuikly.compose.foundation.layout.heightIn
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
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val THREAD_PAGE = "compose_thread"
private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private val threadPageJson = Json { ignoreUnknownKeys = true }

@Page(THREAD_PAGE, supportInLocal = true)
internal class ComposeThreadPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadPageScreen() }
    }
}

@Serializable
internal data class KuiklyThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "Thread",
    val subtitle: String? = null,
    val status: String = "loading",
    val selectedTab: String = "chat",
    val messages: List<KuiklyThreadMessageItem> = emptyList(),
    val tasks: List<KuiklyThreadTaskItem> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val olderMessagesStatus: String = "idle",
    val newerMessagesStatus: String = "idle",
    val errorCode: String? = null,
    val emptyTitle: String = "No messages yet",
    val emptyDetail: String = "This conversation has no messages yet.",
    val filesPayload: String = "{}",
    val composerPayload: String = "{}",
    val showComposer: Boolean = true,
    val joined: Boolean = true,
    val canSend: Boolean = true,
    val membersCount: Int = 0,
    val showMembersAction: Boolean = true,
    val showSettingsAction: Boolean = true,
    val joinLabel: String = "Join channel"
)

@Serializable
internal data class KuiklyThreadMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null,
    val timestampLabel: String = "",
    val body: String = "",
    val replyCount: Int = 0,
    val attachments: List<KuiklyThreadAttachmentItem> = emptyList()
)

@Serializable
internal data class KuiklyThreadAttachmentItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val sizeLabel: String = "",
    val thumbnailUrl: String? = null
)

@Serializable
internal data class KuiklyThreadTaskItem(
    val id: String,
    val title: String,
    val status: String,
    val taskNumber: Int? = null,
    val assigneeName: String? = null,
    val updatedAtLabel: String = ""
)

private enum class KuiklyThreadTab(val wireValue: String, val label: String) {
    Chat("chat", "Chat"),
    Tasks("tasks", "Tasks"),
    Files("files", "Files");

    companion object {
        fun fromWireValue(value: String?): KuiklyThreadTab =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Chat
    }
}

@Composable
private fun ThreadPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    var payloadText by remember { mutableStateOf(params.optString("payload")) }
    var instanceId by remember { mutableStateOf(params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let { payloadText = it }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let { instanceId = it }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload = remember(payloadText, instanceId) {
        parseThreadScreenPayload(
            payload = payloadText,
            fallbackPageData = params.toString(),
            instanceId = instanceId
        )
    }
    val filesPayload = remember(payload.filesPayload, payload.instanceId) {
        parseThreadFilesPayload(payload.filesPayload, payload.instanceId)
    }
    val composerPayload = remember(payload.composerPayload, payload.instanceId) {
        parseThreadComposerPayload(
            payload = payload.composerPayload,
            fallbackPageData = "{}",
            instanceId = payload.instanceId
        )
    }
    LaunchedEffect(payload) {
        bridge.log(
            "thread_render instanceId=${payload.instanceId} status=${payload.status} tab=${payload.selectedTab} messages=${payload.messages.size} tasks=${payload.tasks.size}"
        )
    }
    ThreadPageContent(
        payload = payload,
        filesPayload = filesPayload,
        composerPayload = composerPayload,
        onThreadEvent = { action, itemId, attachmentId, tab ->
            bridge.threadEvent(
                instanceId = payload.instanceId,
                action = action,
                itemId = itemId,
                attachmentId = attachmentId,
                tab = tab
            )
        },
        onComposerEvent = { action, value, asTask, attachmentId ->
            bridge.composerEvent(
                instanceId = composerPayload.instanceId.ifBlank { payload.instanceId },
                action = action,
                value = value,
                asTask = asTask,
                attachmentId = attachmentId
            )
        }
    )
}

internal fun parseThreadScreenPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklyThreadScreenPayload {
    val primaryPayload = payload.trim()
    val source =
        when {
            primaryPayload.isNotEmpty() -> primaryPayload
            fallbackPageData.trim().isNotEmpty() -> fallbackPageData.trim()
            else -> "{}"
        }
    return runCatching {
        threadPageJson.decodeFromString(KuiklyThreadScreenPayload.serializer(), source)
            .let { decoded ->
                if (decoded.instanceId.isBlank() && instanceId.isNotBlank()) {
                    decoded.copy(instanceId = instanceId)
                } else {
                    decoded
                }
            }
    }.getOrElse {
        KuiklyThreadScreenPayload(
            instanceId = instanceId,
            status = "error",
            errorCode = "payload_parse_failed"
        )
    }
}

@Composable
private fun ThreadPageContent(
    payload: KuiklyThreadScreenPayload,
    filesPayload: KuiklyThreadFilesPayload,
    composerPayload: KuiklyThreadComposerPayload,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    var selectedTab by remember(payload.selectedTab) {
        mutableStateOf(KuiklyThreadTab.fromWireValue(payload.selectedTab))
    }
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) {
        ThreadPageHeader(
            payload = payload,
            onBack = { onThreadEvent("back", null, null, null) },
            onShowMembers = { onThreadEvent("showMembers", null, null, null) },
            onShowSettings = { onThreadEvent("showSettings", null, null, null) }
        )
        ThreadPageTabs(
            selectedTab = selectedTab,
            onSelect = { tab ->
                selectedTab = tab
                onThreadEvent("selectTab", null, null, tab.wireValue)
            }
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)
        ) {
            when (selectedTab) {
                KuiklyThreadTab.Chat ->
                    ThreadChatTab(
                        payload = payload,
                        onThreadEvent = onThreadEvent
                    )
                KuiklyThreadTab.Tasks ->
                    ThreadTasksTab(
                        tasks = payload.tasks,
                        onOpenTask = { onThreadEvent("openTask", it, null, null) }
                    )
                KuiklyThreadTab.Files ->
                    ThreadFilesContent(payload = filesPayload) { action, attachmentId ->
                        when (action) {
                            "open", "download" ->
                                onThreadEvent("openAttachment", null, attachmentId, null)
                            "loadMore" -> onThreadEvent("loadMoreFiles", null, null, null)
                            "retry" -> onThreadEvent("retryFiles", null, null, null)
                        }
                    }
            }
        }
        if (!payload.joined) {
            ThreadJoinFooter(
                label = payload.joinLabel,
                onJoin = { onThreadEvent("joinChannel", null, null, null) }
            )
        } else if (payload.showComposer) {
            Box(
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                ThreadComposerContent(
                    payload = composerPayload,
                    onEvent = onComposerEvent
                )
            }
        }
    }
}

@Composable
private fun ThreadPageHeader(
    payload: KuiklyThreadScreenPayload,
    onBack: () -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreadHeaderAction(label = "Back", onClick = onBack)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = payload.title,
                style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            payload.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (payload.showMembersAction) {
            ThreadHeaderAction(label = "Members ${payload.membersCount}", onClick = onShowMembers)
        }
        if (payload.showSettingsAction) {
            ThreadHeaderAction(label = "Settings", onClick = onShowSettings)
        }
    }
}

@Composable
private fun ThreadHeaderAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun ThreadPageTabs(
    selectedTab: KuiklyThreadTab,
    onSelect: (KuiklyThreadTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KuiklyThreadTab.entries.forEach { tab ->
            Box(
                modifier = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(if (selectedTab == tab) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
            }
        }
    }
}

@Composable
private fun ThreadChatTab(
    payload: KuiklyThreadScreenPayload,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit
) {
    when (payload.status) {
        "loading" -> ThreadStateMessage("Loading thread…", null)
        "error" ->
            ThreadStateActionMessage(
                title = "Failed to load thread",
                detail = payload.errorCode ?: "unknown_error",
                actionLabel = "Retry",
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) }
            )
        "no_permission" -> ThreadStateMessage("No permission", payload.errorCode ?: "missing capability")
        "empty" -> ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
        else ->
            ThreadMessagesList(
                payload = payload,
                onThreadEvent = onThreadEvent
            )
    }
}

@Composable
private fun ThreadMessagesList(
    payload: KuiklyThreadScreenPayload,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit
) {
    if (payload.messages.isEmpty()) {
        ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (payload.canLoadMoreBefore) {
            item {
                ThreadInlineActionRow(
                    label = when (payload.olderMessagesStatus) {
                        "loading" -> "Loading older messages…"
                        "error" -> "Retry older messages"
                        else -> "Load older messages"
                    },
                    onClick = {
                        onThreadEvent(
                            if (payload.olderMessagesStatus == "error") "retryLoadOlder" else "loadOlder",
                            null,
                            null,
                            null
                        )
                    }
                )
            }
        }
        items(payload.messages.size, key = { payload.messages[it].id }) { index ->
            ThreadMessageCard(
                message = payload.messages[index],
                onOpenMember = { onThreadEvent("openMember", it, null, null) },
                onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) }
            )
        }
        if (payload.canLoadMoreAfter) {
            item {
                ThreadInlineActionRow(
                    label = when (payload.newerMessagesStatus) {
                        "loading" -> "Loading new messages…"
                        "error" -> "Retry new messages"
                        else -> "Load new messages"
                    },
                    onClick = {
                        onThreadEvent(
                            if (payload.newerMessagesStatus == "error") "retryLoadNewer" else "loadNewer",
                            null,
                            null,
                            null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadInlineActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun ThreadMessageCard(
    message: KuiklyThreadMessageItem,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenAttachment: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThreadMessageAvatar(
                avatarLabel = message.avatarLabel,
                avatarWireValue = message.avatarWireValue,
                gravatarHash = message.gravatarHash,
                onClick = { onOpenMember(message.authorId) }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = message.authorName,
                    style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenMember(message.authorId) }
                )
                Text(
                    text = message.timestampLabel,
                    style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (message.replyCount > 0) {
                ThreadHeaderAction(
                    label = "${message.replyCount} replies",
                    onClick = { onOpenThread(message.id) }
                )
            }
        }
        if (message.body.isNotBlank()) {
            Text(
                text = message.body,
                style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
        if (message.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.attachments.forEach { attachment ->
                    ThreadAttachmentChip(
                        attachment = attachment,
                        onOpen = { onOpenAttachment(attachment.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    gravatarHash: String?,
    onClick: () -> Unit
) {
    val imageUrl =
        remember(avatarWireValue, gravatarHash) {
            avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: gravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarUrl()
        }
    Box(
        modifier = Modifier.size(34.dp).border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.BrandYellow)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl != null) {
            Image(
                painter = rememberAsyncImagePainter(imageUrl),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = avatarLabel.ifBlank { "?" }.take(2).uppercase(),
                style = SlockType.Body.copy(fontWeight = FontWeight.W700, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
    }
}

@Composable
private fun ThreadAttachmentChip(
    attachment: KuiklyThreadAttachmentItem,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceCream)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(30.dp).border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite),
            contentAlignment = Alignment.Center
        ) {
            if (attachment.mimeType.startsWith("image/") && attachment.thumbnailUrl != null) {
                Image(
                    painter = rememberAsyncImagePainter(attachment.thumbnailUrl),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = if (attachment.mimeType.startsWith("image/")) "IMG" else "FILE",
                    style = SlockType.Button.copy(fontSize = 9.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = attachment.name,
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(attachment.sizeLabel, attachment.mimeType).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadTasksTab(tasks: List<KuiklyThreadTaskItem>, onOpenTask: (String) -> Unit) {
    if (tasks.isEmpty()) {
        ThreadStateMessage("No tasks yet", "Tasks created from this conversation will show up here.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tasks.size, key = { tasks[it].id }) { index ->
            val task = tasks[index]
            Row(
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .clickable { onOpenTask(task.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = buildString {
                            task.taskNumber?.let {
                                append("#")
                                append(it)
                                append(" ")
                            }
                            append(task.title)
                        },
                        style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta =
                        listOfNotNull(
                            task.status.takeIf { it.isNotBlank() },
                            task.assigneeName?.takeIf { it.isNotBlank() },
                            task.updatedAtLabel.takeIf { it.isNotBlank() }
                        )
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta.joinToString(" · "),
                            style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                ThreadHeaderAction(label = "Open", onClick = { onOpenTask(task.id) })
            }
        }
    }
}

@Composable
private fun ThreadJoinFooter(label: String, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "You need to join before sending.",
                style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            Text(
                text = "This thread is visible, but sending is currently disabled.",
                style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        ThreadHeaderAction(label = label, onClick = onJoin)
    }
}

@Composable
private fun ThreadStateMessage(title: String, detail: String?) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
    }
}

@Composable
private fun ThreadStateActionMessage(
    title: String,
    detail: String?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
            ThreadHeaderAction(label = actionLabel, onClick = onClick)
        }
    }
}
