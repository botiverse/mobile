package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.base.bridgeModule
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.coil3.rememberAsyncImagePainter
import com.tencent.kuikly.compose.foundation.Canvas
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
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val showReplyActions: Boolean = true,
    val messages: List<KuiklyThreadMessageItem> = emptyList(),
    val originMessage: KuiklyThreadMessageItem? = null,
    val originPreview: String? = null,
    val originParentMessageId: String? = null,
    val originStatus: String = "none",
    val originErrorCode: String? = null,
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
    val members: List<KuiklyThreadMemberItem> = emptyList(),
    val memberCandidates: List<KuiklyThreadMemberItem> = emptyList(),
    val canAddMembers: Boolean = true,
    val addMemberStatus: String = "idle",
    val addMemberErrorCode: String? = null,
    val settingsActions: List<KuiklyThreadSettingsAction> = emptyList(),
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

@Serializable
internal data class KuiklyThreadMemberItem(
    val id: String,
    val displayName: String,
    val handle: String = "",
    val roleLabel: String = "",
    val avatarUrl: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val kind: String = "human"
)

@Serializable
internal data class KuiklyThreadSettingsAction(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: String = "settings",
    val destructive: Boolean = false
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
    var activeModal by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
        ) {
            ThreadPageHeader(
                payload = payload,
                onBack = { onThreadEvent("back", null, null, null) },
                onShowMembers = {
                    activeModal = "members"
                    onThreadEvent("showMembers", null, null, null)
                },
                onShowSettings = {
                    activeModal = "settings"
                    onThreadEvent("showSettings", null, null, null)
                }
            )
            if (payload.showModeTabs) {
                ThreadPageTabs(
                    selectedTab = selectedTab,
                    onSelect = { tab ->
                        selectedTab = tab
                        onThreadEvent("selectTab", null, null, tab.wireValue)
                    }
                )
            }
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
                    modifier = Modifier.fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
                ) {
                    ThreadComposerContent(
                        payload = composerPayload,
                        onEvent = onComposerEvent
                    )
                }
            }
        }
        when (activeModal) {
            "members" ->
                ThreadMembersModal(
                    members = payload.members,
                    membersCount = payload.membersCount,
                    canAddMembers = payload.canAddMembers,
                    onDismiss = { activeModal = null },
                    onAddMember = {
                        activeModal = "addMember"
                    },
                    onManageMember = { memberId ->
                        activeModal = null
                        onThreadEvent("manageMember", memberId, null, null)
                    }
                )
            "addMember" ->
                ThreadAddMemberModal(
                    members = payload.members,
                    candidates = payload.memberCandidates,
                    canAddMembers = payload.canAddMembers,
                    addMemberStatus = payload.addMemberStatus,
                    addMemberErrorCode = payload.addMemberErrorCode,
                    onDismiss = { activeModal = "members" },
                    onSubmit = { member ->
                        onThreadEvent("submitAddMember", member.id, null, member.kind)
                    }
                )
            "settings" ->
                ThreadSettingsModal(
                    actions = payload.settingsActions,
                    onDismiss = { activeModal = null },
                    onOpenAction = { actionId ->
                        activeModal = null
                        onThreadEvent("settingsAction", actionId, null, null)
                    }
                )
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
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            ThreadHeaderIconButton(
                icon = if (payload.navigationLabel.equals("Close", ignoreCase = true)) "x" else "arrow-left",
                onClick = onBack
            )
            ThreadHashTile()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = payload.title,
                    style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                payload.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (payload.showSettingsAction) {
                ThreadHeaderIconButton(icon = "settings", onClick = onShowSettings)
            }
            if (payload.showMembersAction) {
                ThreadMembersHeaderButton(count = payload.membersCount, onClick = onShowMembers)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun ThreadHashTile() {
    Box(
        modifier = Modifier.size(40.dp)
            .background(SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "#",
            style = SlockType.PageTitle.copy(fontWeight = FontWeight.W700, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun ThreadHeaderIconButton(icon: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(SlockSize.IconButton)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(icon, modifier = Modifier.size(20.dp), color = "#FF000000")
    }
}

@Composable
private fun ThreadMembersHeaderButton(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.height(SlockSize.IconButton)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SlockLucideIcon("users", modifier = Modifier.size(18.dp), color = "#FF000000")
        Text(
            text = count.toString(),
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        KuiklyThreadTab.entries.forEach { tab ->
            Box(
                modifier = Modifier.height(40.dp).border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(if (selectedTab == tab) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val latestItemIndex = payload.latestMessageListItemIndex()
    LaunchedEffect(payload.instanceId, payload.messages.lastOrNull()?.id, latestItemIndex) {
        if (latestItemIndex >= 0) {
            repeat(ThreadInitialBottomAnchorAttempts) {
                if (listState.layoutInfo.totalItemsCount > latestItemIndex) {
                    listState.scrollToItem(latestItemIndex, 0)
                    return@LaunchedEffect
                }
                delay(16)
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(vertical = 2.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (payload.originStatus != "none") {
                item {
                    ThreadOriginCard(
                        payload = payload,
                        onOpenParentMessage = {
                            payload.originParentMessageId?.let {
                                onThreadEvent("openParentMessage", it, null, null)
                            }
                        }
                    )
                }
            }
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
                    showReplyAction = payload.showReplyActions,
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
        if (listState.shouldShowThreadBackToBottom(latestItemIndex)) {
            ThreadBackToBottomButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = SlockSpacing.Md, bottom = SlockSpacing.Md),
                onClick = {
                    coroutineScope.launch {
                        listState.scrollToItem(latestItemIndex, 0)
                    }
                }
            )
        }
    }
}

private fun KuiklyThreadScreenPayload.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    var index = 0
    if (originStatus != "none") index += 1
    if (canLoadMoreBefore) index += 1
    return index + messages.lastIndex
}

private fun LazyListState.shouldShowThreadBackToBottom(latestItemIndex: Int): Boolean {
    if (latestItemIndex <= 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val latestBottom = latestItem?.let { it.offset + it.size } ?: return true
    return latestBottom < layoutInfo.viewportEndOffset - ThreadBackToBottomTolerancePx
}

@Composable
private fun ThreadBackToBottomButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(modifier = modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            modifier = Modifier.matchParentSize().offset(x = 4f, y = 4f).background(SlockColor.ShadowBlack)
        )
        Row(
            modifier = Modifier
                .background(SlockColor.AccentCyan)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(onClick = onClick)
                .padding(horizontal = SlockSpacing.Sm, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = "Back to bottom",
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            SlockLucideIcon(
                "chevron-down",
                modifier = Modifier.size(14.dp),
                color = "#FF000000"
            )
        }
    }
}

private const val ThreadBackToBottomTolerancePx = 24
private const val ThreadInitialBottomAnchorAttempts = 10

@Composable
private fun ThreadInlineActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
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
    showReplyAction: Boolean = true,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenAttachment: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ThreadMessageAvatar(
                    avatarLabel = message.avatarLabel,
                    avatarWireValue = message.avatarWireValue,
                    gravatarHash = message.gravatarHash,
                    onClick = { onOpenMember(message.authorId) }
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                    ) {
                        Text(
                            text = message.authorName,
                            style = SlockType.ButtonBold.copy(fontSize = 15.sp, lineHeight = 19.sp, color = SlockColor.TextBlack),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable { onOpenMember(message.authorId) }
                        )
                        Text(
                            text = message.timestampLabel,
                            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (message.body.isNotBlank()) {
                        SlockMarkdownBody(
                            body = message.body,
                            textStyle = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = SlockColor.TextBlack)
                        )
                    }
                    if (message.attachments.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                            message.attachments.forEach { attachment ->
                                ThreadAttachmentChip(
                                    attachment = attachment,
                                    onOpen = { onOpenAttachment(attachment.id) }
                                )
                            }
                        }
                    }
                    if (showReplyAction && message.replyCount > 0) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            ThreadHeaderAction(
                                label = "${message.replyCount} replies",
                                onClick = { onOpenThread(message.id) }
                            )
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun ThreadOriginCard(
    payload: KuiklyThreadScreenPayload,
    onOpenParentMessage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = "Origin",
                style = SlockType.Caption.copy(fontWeight = FontWeight.W700, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.weight(1f)
            )
            if (!payload.originParentMessageId.isNullOrBlank()) {
                ThreadHeaderAction(
                    label = "Open",
                    onClick = onOpenParentMessage
                )
            }
        }
        when (payload.originStatus) {
            "loading" -> {
                Text(
                    text = "Refreshing original message...",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
            "missing" -> {
                Text(
                    text = payload.originPreview?.ifBlank { "Original message is unavailable." }
                        ?: "Original message is unavailable.",
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack
                )
                payload.originErrorCode?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Refresh failed: $it",
                        style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            else -> {
                payload.originMessage?.let { origin ->
                    ThreadMessageCard(
                        message = origin,
                        showReplyAction = false,
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenAttachment = {}
                    )
                } ?: payload.originPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
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
    val pixelSpec = remember(avatarWireValue) { avatarWireValue?.toThreadPixelAvatarSpec() }
    Box(
        modifier = Modifier.size(40.dp).border(SlockStroke.Default, SlockColor.BorderBlack)
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
        } else if (pixelSpec != null) {
            ThreadPixelAvatar(spec = pixelSpec)
        } else {
            Text(
                text = avatarLabel.ifBlank { "?" }.take(2).uppercase(),
                style = SlockType.ButtonBold.copy(fontSize = 16.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
    }
}

@Composable
private fun ThreadPixelAvatar(spec: ThreadPixelAvatarSpec) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(spec.background)
        val cellSize = size.width / ThreadPixelAvatarGridSize
        spec.grid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                val color = ThreadPixelAvatarPalette[key] ?: Color.Transparent
                if (color != Color.Transparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(columnIndex * cellSize, rowIndex * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

private data class ThreadPixelAvatarSpec(val background: Color, val grid: List<String>)

private const val ThreadPixelAvatarGridSize = 8f
private const val ThreadPixelAvatarProtocolPrefix = "pixel:"
private const val ThreadPixelAvatarRandomPrefix = "random:"
private const val ThreadPixelAvatarDefaultKey = "robot"

private val ThreadPixelAvatarPalette = mapOf(
    '_' to Color.Transparent,
    'K' to Color(0xFF141111),
    'W' to Color(0xFFFFFFFF),
    'P' to Color(0xFFFE7DA8),
    'O' to Color(0xFFF8A16F),
    'Y' to Color(0xFFFFD440),
    'L' to Color(0xFFA9D877),
    'C' to Color(0xFF27CCF3),
    'V' to Color(0xFFBBAFE6),
    'B' to Color(0xFF576574),
    'R' to Color(0xFFF97264),
    'G' to Color(0xFF9DCAAA)
)

private val ThreadPixelAvatarFixedSpecs = mapOf(
    "robot" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("_KKKKKK_", "KCKWWKCK", "KWKWWKWK", "KKKKKKKK", "_KWKKWK_", "_KKKKKK_", "_KK__KK_", "_KK__KK_")),
    "cat" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("K______K", "KK____KK", "KYKYYKYK", "KYYYYYYK", "KYKYYKYK", "_KYYYYK_", "__KYYK__", "___KK___")),
    "ghost" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("__KKKK__", "_KWWWWK_", "KWKWWKWK", "KWWWWWWK", "KWWWWWWK", "KWWKKWWK", "KWKWWKWK", "K_K__K_K")),
    "skull" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("_KKKKKK_", "KWWWWWWK", "KWKWWKWK", "KWWWWWWK", "_KWWWWK_", "_KWKWKK_", "__KKKK__", "___KK___")),
    "alien" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("_KKKKKK_", "KGGGGGGK", "KGKKKKGK", "KGGGGGGK", "_KGGGGK_", "__KGGK__", "__KGGK__", "_KK__KK_")),
    "heart" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("________", "_KK__KK_", "KRRKKRRK", "KRRRRRRK", "KRRRRRRK", "_KRRRRK_", "__KRRK__", "___KK___")),
    "star" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("___KK___", "___KK___", "KKKKKKKK", "_KYYYYK_", "__KYYK__", "_KYKKYK_", "KYK__KYK", "KK____KK")),
    "flame" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('O'), listOf("___K____", "__KRK___", "__KRKK__", "_KORRRK_", "_KOORRK_", "KYOOORK_", "KYYOOK__", "_KKKK___")),
    "diamond" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("___KK___", "__KCCK__", "_KCWCCK_", "KCWCCCCK", "KCCCCCCK", "_KCCCCK_", "__KCCK__", "___KK___")),
    "mushroom" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('P'), listOf("__KKKK__", "_KRWWRK_", "KRRWWRRK", "KRRRRRRK", "_KKKKKK_", "__KWWK__", "__KWWK__", "_KKKKKK_")),
    "eye" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('V'), listOf("________", "__KKKK__", "_KWWWWK_", "KWWKKWWK", "KWWKKWWK", "_KWWWWK_", "__KKKK__", "________")),
    "crown" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('Y'), listOf("________", "_K_KK_K_", "_KKKKKK_", "_KYYYYK_", "_KYYYYK_", "KKKKKKKK", "KYYYYYYK", "KKKKKKKK")),
    "cloud" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('C'), listOf("________", "__KK____", "_KWWKK__", "KWWWWWK_", "KWWWWWWK", "KWWWWWWK", "_KKKKKK_", "________")),
    "sun" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('R'), listOf("_K_KK_K_", "K_KYYK_K", "_KYYYYK_", "KYYYYYYK", "KYYYYYYK", "_KYYYYK_", "K_KYYK_K", "_K_KK_K_")),
    "bell" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('B'), listOf("___KK___", "__KYYK__", "_KYYYYK_", "_KYYYYK_", "_KYYYYK_", "KYYYYYYK", "KKKKKKKK", "___KK___")),
    "tree" to ThreadPixelAvatarSpec(ThreadPixelAvatarPalette.getValue('L'), listOf("___KK___", "__KGGK__", "_KGGGGK_", "KGGGGGGK", "KGGGGGGK", "_KKGGKK_", "__KOOK__", "___KK___"))
)

private val ThreadPixelAvatarRandomOptions = listOf(
    ThreadPixelAvatarPalette.getValue('C') to 'K',
    ThreadPixelAvatarPalette.getValue('Y') to 'K',
    ThreadPixelAvatarPalette.getValue('L') to 'K',
    ThreadPixelAvatarPalette.getValue('P') to 'W',
    ThreadPixelAvatarPalette.getValue('V') to 'K',
    Color(0xFF1E1E1C) to 'C',
    Color(0xFF1E1E1C) to 'G',
    Color(0xFF1E1E1C) to 'P',
    Color(0xFF1E1E1C) to 'Y',
    Color(0xFF1E1E1C) to 'V',
    ThreadPixelAvatarPalette.getValue('O') to 'K',
    ThreadPixelAvatarPalette.getValue('C') to 'W'
)

private fun String.toThreadPixelAvatarSpec(): ThreadPixelAvatarSpec? =
    removeThreadPrefixOrNull(ThreadPixelAvatarProtocolPrefix)?.let {
        ThreadPixelAvatarFixedSpecs[it] ?: ThreadPixelAvatarFixedSpecs.getValue(ThreadPixelAvatarDefaultKey)
    } ?: removeThreadPrefixOrNull(ThreadPixelAvatarRandomPrefix)?.let(::randomThreadPixelAvatar)

private fun String.removeThreadPrefixOrNull(prefix: String): String? =
    takeIf { startsWith(prefix) }?.removePrefix(prefix)?.ifBlank { null }

private fun randomThreadPixelAvatar(seed: String): ThreadPixelAvatarSpec {
    val random = ThreadPixelAvatarRandom(threadPixelHash(seed))
    val (background, fill) = ThreadPixelAvatarRandomOptions[(random.next() * ThreadPixelAvatarRandomOptions.size).toInt()]
    val grid = List(ThreadPixelAvatarGridSize.toInt()) {
        val left = List((ThreadPixelAvatarGridSize / 2).toInt()) {
            if (random.next() < 0.45) fill else '_'
        }
        (left + left.asReversed()).joinToString(separator = "")
    }
    return ThreadPixelAvatarSpec(background, grid)
}

private fun threadPixelHash(input: String): Int {
    var hash = 0
    input.forEach { character ->
        hash = (hash shl 5) - hash + character.code
    }
    return hash
}

private class ThreadPixelAvatarRandom(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 1831565813
        var mixed = (value xor (value ushr 15)) * (1 or value)
        mixed = (mixed + ((mixed xor (mixed ushr 7)) * (61 or mixed))) xor mixed
        val unsigned = (mixed xor (mixed ushr 14)).toLong() and 0xffffffffL
        return unsigned.toDouble() / 4294967296.0
    }
}

@Composable
private fun ThreadAttachmentChip(
    attachment: KuiklyThreadAttachmentItem,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onOpen)
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Box(
            modifier = Modifier.size(42.dp).border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceCream),
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
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(attachment.sizeLabel, attachment.mimeType).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
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
private fun ThreadMembersModal(
    members: List<KuiklyThreadMemberItem>,
    membersCount: Int,
    canAddMembers: Boolean,
    onDismiss: () -> Unit,
    onAddMember: () -> Unit,
    onManageMember: (String) -> Unit
) {
    SlockModalLayer(
        title = "Thread members",
        subtitle = "$membersCount people in this conversation",
        onDismiss = onDismiss
    ) {
        if (canAddMembers) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                ThreadHeaderAction(label = "Add member", onClick = onAddMember)
            }
        }
        if (members.isEmpty()) {
            ThreadModalEmptyState(
                title = "No member details loaded",
                detail = "Android can still open the native members panel while this KMP payload is filled."
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                members.forEach { member ->
                    ThreadMemberRow(member = member, onClick = { onManageMember(member.id) })
                }
            }
        }
    }
}

@Composable
private fun ThreadAddMemberModal(
    members: List<KuiklyThreadMemberItem>,
    candidates: List<KuiklyThreadMemberItem>,
    canAddMembers: Boolean,
    addMemberStatus: String,
    addMemberErrorCode: String?,
    onDismiss: () -> Unit,
    onSubmit: (KuiklyThreadMemberItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember(candidates) { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf(false) }
    val currentKeys = remember(members) { members.map { it.memberKey }.toSet() }
    val selected = candidates.firstOrNull { it.id == selectedId }
    val normalizedQuery = query.trim()
    val filteredCandidates =
        candidates
            .filter { member ->
                normalizedQuery.isBlank() ||
                    member.displayName.contains(normalizedQuery, ignoreCase = true) ||
                    member.handle.contains(normalizedQuery, ignoreCase = true) ||
                    member.roleLabel.contains(normalizedQuery, ignoreCase = true) ||
                    member.id.contains(normalizedQuery, ignoreCase = true)
            }
            .distinctBy { it.memberKey }
    val blocked = !canAddMembers || addMemberStatus == "no_permission"
    val adding = addMemberStatus == "adding"
    val errorText =
        when {
            blocked -> "You do not have permission to add members."
            localError -> "Select a member before adding."
            addMemberStatus == "failed" -> "Unable to add member: ${addMemberErrorCode ?: "member_add_failed"}"
            else -> null
        }
    SlockModalLayer(
        title = "Add member",
        subtitle = errorText ?: "Choose a user or agent to add",
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(if (blocked) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                TextField(
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    value = query,
                    placeholder = "Search users or agents",
                    autoFocus = false,
                    onValueChange = {
                        query = it
                        selectedId = null
                        localError = false
                    },
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                    placeholderColor = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filteredCandidates.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = if (candidates.isEmpty()) "No people loaded" else "No matching people",
                            detail = if (candidates.isEmpty()) "Refresh this conversation and try again." else "Try a different search."
                        )
                    }
                } else {
                    items(filteredCandidates.size, key = { filteredCandidates[it].memberKey }) { index ->
                        val member = filteredCandidates[index]
                        val alreadyJoined = member.memberKey in currentKeys
                        ThreadMemberCandidateRow(
                            member = member,
                            selected = member.id == selectedId,
                            alreadyJoined = alreadyJoined,
                            enabled = !blocked && !adding && !alreadyJoined,
                            onClick = {
                                selectedId = if (selectedId == member.id) null else member.id
                                localError = false
                            }
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThreadHeaderAction(label = "Back", onClick = onDismiss)
                Spacer(modifier = Modifier.weight(1f))
                ThreadHeaderAction(
                    label = if (adding) "Adding" else "Add selected",
                    onClick = {
                        if (adding || blocked) return@ThreadHeaderAction
                        val member = selected
                        if (member == null || member.memberKey in currentKeys) {
                            localError = true
                        } else {
                            onSubmit(member)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadMemberRow(
    member: KuiklyThreadMemberItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .background(if (member.kind == "agent") SlockColor.AccentLavender else SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            val avatarUrl = member.avatarUrl?.takeIf { it.isNotBlank() }
            if (avatarUrl != null) {
                Image(
                    painter = rememberAsyncImagePainter(avatarUrl),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = member.avatarLabel.ifBlank { member.displayName }.take(2).uppercase(),
                    style = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp),
                    color = SlockColor.TextBlack
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = member.displayName,
                style = SlockType.ButtonBold,
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta =
                listOf(
                    member.handle.takeIf { it.isNotBlank() }?.let { "@$it" },
                    member.roleLabel.takeIf { it.isNotBlank() }
                ).filterNotNull().joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SlockLucideIcon("chevron-right", modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ThreadMemberCandidateRow(
    member: KuiklyThreadMemberItem,
    selected: Boolean,
    alreadyJoined: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(
                when {
                    alreadyJoined -> SlockColor.SurfaceCream
                    selected -> SlockColor.AccentLime
                    else -> SlockColor.SurfaceWhite
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(34.dp)
                .background(if (member.kind == "agent") SlockColor.AccentLavender else SlockColor.BrandYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = member.avatarLabel.ifBlank { member.displayName }.take(2).uppercase(),
                style = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp),
                color = SlockColor.TextBlack
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = member.displayName,
                style = SlockType.ButtonBold,
                color = if (alreadyJoined) SlockColor.TextMutedGray else SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(member.handle.takeIf { it.isNotBlank() }?.let { "@$it" }, member.roleLabel)
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = when {
                alreadyJoined -> "Joined"
                selected -> "Selected"
                else -> "Add"
            },
            style = SlockType.Caption.copy(fontWeight = FontWeight.W600),
            color = SlockColor.TextBlack
        )
    }
}

private val KuiklyThreadMemberItem.memberKey: String
    get() = "${kind.lowercase()}:$id"

@Composable
private fun ThreadSettingsModal(
    actions: List<KuiklyThreadSettingsAction>,
    onDismiss: () -> Unit,
    onOpenAction: (String) -> Unit
) {
    val effectiveActions =
        actions.ifEmpty {
            listOf(
                KuiklyThreadSettingsAction(
                    id = "files",
                    title = "Files",
                    subtitle = "Open files shared in this conversation",
                    icon = "folder-open"
                ),
                KuiklyThreadSettingsAction(
                    id = "notifications",
                    title = "Notifications",
                    subtitle = "Configure thread notification state",
                    icon = "bell-ring"
                ),
                KuiklyThreadSettingsAction(
                    id = "leave",
                    title = "Leave thread",
                    subtitle = "Stop following this conversation",
                    icon = "log-out",
                    destructive = true
                )
            )
        }
    SlockModalLayer(
        title = "Thread settings",
        subtitle = "Conversation actions",
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            effectiveActions.forEach { action ->
                SlockActionSheetRow(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    destructive = action.destructive,
                    onClick = { onOpenAction(action.id) }
                )
            }
        }
    }
}

@Composable
private fun ThreadModalEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.W600, color = SlockColor.TextBlack),
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
