package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.base.bridgeModule
import ai.slock.compose.data.BridgeThreadApi
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KuiklyAuthSessionStore
import ai.slock.compose.data.KuiklyRelationalStore
import ai.slock.compose.data.RelationalComposeLocalDataStore
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.formatSlockTimestampLabel
import ai.slock.compose.model.toGravatarAvatarUrl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tencent.kuikly.compose.foundation.Canvas
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Density
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal const val THREAD_PAGE = "compose_thread"
private const val THREAD_MESSAGE_VISUAL_PAGE = "compose_thread_message_visual"
private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private const val ThreadMessagesPageSize = 50
private val threadPageJson = Json { ignoreUnknownKeys = true }

@Page(THREAD_PAGE, supportInLocal = true)
internal class ComposeThreadPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadPageScreen() }
    }
}

@Page(THREAD_MESSAGE_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadMessageVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}

@Serializable
internal data class KuiklyThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "Thread",
    val subtitle: String? = null,
    val status: String = "loading",
    val sourceThreadId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val showReplyActions: Boolean = true,
    val alignOwnMessagesRight: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
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
    val removeMemberStatus: String = "idle",
    val removingMemberId: String? = null,
    val removeMemberErrorCode: String? = null,
    val settingsActions: List<KuiklyThreadSettingsAction> = emptyList(),
    val showMembersAction: Boolean = true,
    val showSettingsAction: Boolean = true,
    val showOpenInChannelAction: Boolean = false,
    val showMessageSaveAction: Boolean = true,
    val showTaskOpenActions: Boolean = true,
    val showTaskStatusActions: Boolean = true,
    val joinLabel: String = "Join channel"
)

internal fun KuiklyThreadScreenPayload.withSharedShellEventCapabilities(): KuiklyThreadScreenPayload =
    copy(
        showOpenInChannelAction = showOpenInChannelAction || originParentMessageId?.isNotBlank() == true,
        canAddMembers = false,
        showSettingsAction = false
    )

@Serializable
internal data class KuiklyThreadMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorKind: String = "",
    val isOwn: Boolean = false,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null,
    val timestampLabel: String = "",
    val seq: Long? = null,
    val messageType: String = "Chat",
    val systemEventType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val body: String = "",
    val replyCount: Int = 0,
    val taskId: String? = null,
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskAssigneeName: String? = null,
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

private fun KuiklyThreadScreenPayload.findMarkdownMentionMember(tag: ThreadMarkdownTag): KuiklyThreadMemberItem? {
    val key = tag.label.removePrefix("@").trim().lowercase()
    if (key.isBlank()) return null
    return members.firstOrNull { member ->
        member.id.equals(key, ignoreCase = true) ||
            member.handle.removePrefix("@").equals(key, ignoreCase = true) ||
            member.displayName.equals(key, ignoreCase = true)
    }
}

private fun KuiklyThreadScreenPayload.findMarkdownTaskId(tag: ThreadMarkdownTag): String? {
    val key = tag.label.removePrefix("#").trim()
    val taskNumber = key.toIntOrNull()
    return tasks.firstOrNull { task ->
        task.id.equals(key, ignoreCase = true) ||
            task.taskNumber == taskNumber
    }?.id
        ?: messages.firstOrNull { message ->
            message.taskId?.equals(key, ignoreCase = true) == true ||
                message.taskNumber == taskNumber
        }?.taskId
        ?: originMessage?.takeIf { message ->
            message.taskId?.equals(key, ignoreCase = true) == true ||
                message.taskNumber == taskNumber
        }?.taskId
}

private enum class KuiklyThreadTab(val wireValue: String, val label: String) {
    Chat("chat", "Chat"),
    Tasks("tasks", "Tasks"),
    Files("files", "Files");

    companion object {
        fun fromWireValue(value: String?): KuiklyThreadTab =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: Chat
    }
}

private val KuiklyThreadTab.icon: String
    get() =
        when (this) {
            KuiklyThreadTab.Chat -> "message-square"
            KuiklyThreadTab.Tasks -> "list-todo"
            KuiklyThreadTab.Files -> "paperclip"
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
    val relationalStore = remember { KuiklyRelationalStore(bridge) }
    val authStore = remember { KuiklyAuthSessionStore(relationalStore) }
    val localWriteThrough =
        remember {
            ComposeLocalWriteThrough(
                RelationalComposeLocalDataStore(relationalStore),
                logger = { message -> bridge.log("shared_thread_cache $message") }
            )
        }
    val dataSource =
        remember {
            SharedThreadDataSource(
                networkApi = BridgeThreadApi(bridge, authStore),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough
            )
        }
    SharedThreadRouteContent(
        initialPayload = payload,
        bridge = bridge,
        dataSource = dataSource,
        onBack = {
            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
    )
}

@Composable
private fun ThreadMessageVisualScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val payloadText = params.optString("payload")
    val instanceId = params.optString("instanceId")
    val payload =
        remember(payloadText, instanceId) {
            parseThreadScreenPayload(
                payload = payloadText,
                fallbackPageData = params.toString(),
                instanceId = instanceId
            )
        }
    val message = payload.messages.firstOrNull()
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        if (message != null) {
            ThreadMessageCard(
                message = message,
                highlightedMentions = emptySet(),
                showReplyAction = payload.showReplyActions,
                taskOpenEnabled = payload.showTaskOpenActions,
                taskStatusActionsEnabled = payload.showTaskStatusActions,
                timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onTaskStatusChange = { _, _ -> },
                onOpenAttachment = {},
                onOpenMarkdownTag = {},
                onOpenMarkdownUrl = {}
            )
        }
    }
}

@Composable
internal fun SharedThreadRouteContent(
    initialPayload: KuiklyThreadScreenPayload,
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    onBack: () -> Unit,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit = {},
    onOpenParentThread: () -> Boolean = { false }
) {
    val threadViewModel: SharedThreadViewModel =
        viewModel(key = "shared-thread-${initialPayload.sourceThreadId ?: initialPayload.instanceId}") {
            SharedThreadViewModel(dataSource)
        }
    val threadState by threadViewModel.state.collectAsState()
    val payload = threadState.payload
    var composerDraftText by remember(initialPayload.instanceId) { mutableStateOf("") }
    var composerDraftAsTask by remember(initialPayload.instanceId) { mutableStateOf(false) }
    var composerSyncNonce by remember(initialPayload.instanceId) { mutableStateOf(0) }
    var composerSending by remember(initialPayload.instanceId) { mutableStateOf(false) }
    var composerFailureLabel by remember(initialPayload.instanceId) { mutableStateOf<String?>(null) }
    var removedAttachmentIds by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var uploadedAttachments by remember(initialPayload.instanceId) {
        mutableStateOf<List<SharedThreadComposerAttachment>>(emptyList())
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(initialPayload) {
        val composer = parseThreadComposerPayload(initialPayload.composerPayload, "{}", initialPayload.instanceId)
        composerDraftText = composer.seedText
        composerDraftAsTask = composer.seedAsTask
        composerSyncNonce = composer.syncNonce
        composerSending = false
        composerFailureLabel = null
        removedAttachmentIds = emptySet()
        uploadedAttachments = emptyList()
        threadViewModel.dispatch(SharedThreadIntent.Start(initialPayload))
    }

    val filesPayload = remember(payload.filesPayload, payload.instanceId) {
        parseThreadFilesPayload(payload.filesPayload, payload.instanceId)
    }
    val renderedComposerPayload = remember(payload.composerPayload, payload.instanceId) {
        parseThreadComposerPayload(payload.composerPayload, "{}", payload.instanceId)
    }
    val composerPayloadText =
        remember(
            renderedComposerPayload,
            composerDraftText,
            composerDraftAsTask,
            composerSyncNonce,
            composerSending,
            composerFailureLabel,
            removedAttachmentIds,
            uploadedAttachments,
            payload.canSend
        ) {
            val pendingAttachments =
                (renderedComposerPayload.pendingAttachments.map { attachment ->
                    SharedThreadComposerAttachment(
                        id = attachment.id,
                        fileName = attachment.fileName,
                        sizeLabel = attachment.sizeLabel,
                        mimeType = attachment.mimeType,
                        status = attachment.status
                    )
                } + uploadedAttachments)
                    .distinctBy { attachment -> attachment.id }
                    .filterNot { attachment -> attachment.id in removedAttachmentIds }
            payload.appShellComposerPayload(
                baseComposer =
                    renderedComposerPayload.copy(
                        pendingAttachments =
                            pendingAttachments.map { attachment ->
                                KuiklyPendingAttachmentItem(
                                    id = attachment.id,
                                    fileName = attachment.fileName,
                                    sizeLabel = attachment.sizeLabel,
                                    mimeType = attachment.mimeType,
                                    status = attachment.status
                                )
                            }
                    ),
                seedText = composerDraftText,
                seedAsTask = composerDraftAsTask,
                syncNonce = composerSyncNonce,
                composerMode =
                    when {
                        composerSending -> "sending"
                        composerFailureLabel != null -> "failed"
                        renderedComposerPayload.composerMode == "failed" -> "failed"
                        else -> renderedComposerPayload.composerMode
                    },
                statusLabel =
                    when {
                        composerSending -> "Sending..."
                        composerFailureLabel != null -> composerFailureLabel
                        renderedComposerPayload.composerMode == "failed" -> renderedComposerPayload.statusLabel
                        else -> renderedComposerPayload.statusLabel
                    },
                enabled = payload.canSend && !composerSending
            )
        }
    val composerPayload = remember(composerPayloadText, payload.instanceId) {
        parseThreadComposerPayload(
            payload = composerPayloadText,
            fallbackPageData = "{}",
            instanceId = payload.instanceId
        )
    }
    val renderedPayload = remember(payload) {
        payload.withSharedShellEventCapabilities()
    }
    LaunchedEffect(renderedPayload) {
        bridge.log(
            "thread_render instanceId=${renderedPayload.instanceId} status=${renderedPayload.status} tab=${renderedPayload.selectedTab} messages=${renderedPayload.messages.size} tasks=${renderedPayload.tasks.size}"
        )
    }
    LaunchedEffect(renderedPayload.sourceThreadId, renderedPayload.status, renderedPayload.messages.size) {
        if (renderedPayload.status == "content" && renderedPayload.messages.isNotEmpty()) {
            threadViewModel.dispatch(SharedThreadIntent.MarkVisibleReadAll)
        }
    }
    ThreadPageContent(
        payload = renderedPayload,
        filesPayload = filesPayload,
        composerPayload = composerPayload,
        highlightedMentions = threadState.highlightedMentions,
        onBack = onBack,
        onThreadEvent = { action, itemId, attachmentId, tab ->
            when {
                action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        openSharedThreadAttachment(
                            bridge = bridge,
                            dataSource = dataSource,
                            attachmentId = attachmentId
                        )
                    }
                }
                action == "copyMessage" && !itemId.isNullOrBlank() -> {
                    val body = renderedPayload.messages.firstOrNull { it.id == itemId }?.body.orEmpty()
                    if (body.isNotBlank()) {
                        bridge.copyToPasteboard(body)
                        bridge.toast("Copied")
                    }
                }
                action == "openMessageThread" && !itemId.isNullOrBlank() -> {
                    val parentMessage =
                        renderedPayload.messages.firstOrNull { it.id == itemId }
                            ?: renderedPayload.originMessage?.takeIf { it.id == itemId }
                    if (parentMessage == null) {
                        bridge.toast("Unable to open replies")
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        when (val result = dataSource.createOrOpenReplyThread(renderedPayload, parentMessage)) {
                            is DataResult.Content -> onOpenThreadPayload(result.data)
                            is DataResult.Empty -> bridge.toast("Unable to open replies")
                            is DataResult.Error -> bridge.toast("Unable to open replies")
                            is DataResult.NoPermission -> bridge.toast("Unable to open replies")
                        }
                    }
                }
                action == "openParentMessage" -> {
                    if (!onOpenParentThread()) {
                        onBack()
                    }
                }
                action == "saveMessage" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        when (dataSource.saveMessage(itemId)) {
                            is DataResult.Content -> bridge.toast("Saved")
                            is DataResult.Empty -> bridge.toast("Unable to save message")
                            is DataResult.Error -> bridge.toast("Unable to save message")
                            is DataResult.NoPermission -> bridge.toast("Unable to save message")
                        }
                    }
                }
                action == "openTask" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Task #${itemId.takeLast(6)}")
                }
                action == "openMarkdownUrl" && !itemId.isNullOrBlank() -> {
                    bridge.openUrl(itemId, null)
                }
                action == "openMarkdownTag" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open ${itemId.take(32)}")
                }
                action == "openMarkdownChannel" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open #${itemId.removePrefix("#").take(31)}")
                }
                action == "openMarkdownThreadRef" && !itemId.isNullOrBlank() -> {
                    bridge.toast("Unable to open ${itemId.take(32)}")
                }
                action == "setTaskStatus" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val status = tab.toSharedThreadTaskStatus()
                    if (status == null) {
                        bridge.toast("Unsupported task status")
                        return@ThreadPageContent
                    }
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, status)) {
                            is DataResult.Content -> {
                                val nextPayload =
                                    result.data.takeIf { it.instanceId.isNotBlank() }
                                        ?: renderedPayload.withTaskStatus(itemId, status)
                                threadViewModel.dispatch(SharedThreadIntent.SetPayload(nextPayload))
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty -> bridge.toast("Unable to update task")
                            is DataResult.Error -> bridge.toast("Unable to update task")
                            is DataResult.NoPermission -> bridge.toast("Unable to update task")
                        }
                    }
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Done)) {
                            is DataResult.Content -> {
                                val nextPayload =
                                    result.data.takeIf { it.instanceId.isNotBlank() }
                                        ?: renderedPayload.withTaskStatus(itemId, TaskStatus.Done)
                                threadViewModel.dispatch(SharedThreadIntent.SetPayload(nextPayload))
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty -> bridge.toast("Unable to update task")
                            is DataResult.Error -> bridge.toast("Unable to update task")
                            is DataResult.NoPermission -> bridge.toast("Unable to update task")
                        }
                    }
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Todo)) {
                            is DataResult.Content -> {
                                val nextPayload =
                                    result.data.takeIf { it.instanceId.isNotBlank() }
                                        ?: renderedPayload.withTaskStatus(itemId, TaskStatus.Todo)
                                threadViewModel.dispatch(SharedThreadIntent.SetPayload(nextPayload))
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty -> bridge.toast("Unable to update task")
                            is DataResult.Error -> bridge.toast("Unable to update task")
                            is DataResult.NoPermission -> bridge.toast("Unable to update task")
                        }
                    }
                }
                action == "selectTab" -> {
                    // The shared shell owns tab state locally. There is no fullscreen host event to forward.
                }
                action == "loadOlder" || action == "retryLoadOlder" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadOlder)
                }
                action == "loadNewer" || action == "retryLoadNewer" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadNewer)
                }
                else -> {
                    bridge.log(
                        "thread_event_unsupported instanceId=${payload.instanceId} action=$action itemId=${itemId.orEmpty()} attachmentId=${attachmentId.orEmpty()} tab=${tab.orEmpty()}"
                    )
                }
            }
        },
        onComposerEvent = { action, value, asTask, attachmentId ->
            when (action) {
                "change" -> {
                    value?.let { composerDraftText = it }
                    asTask?.let { composerDraftAsTask = it }
                    composerFailureLabel = null
                }
                "toggleAsTask" -> {
                    asTask?.let { composerDraftAsTask = it }
                    composerFailureLabel = null
                }
                "send" -> {
                    val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() }
                    val draftText = composerDraftText.trim()
                    val attachmentIds = composerPayload.pendingAttachments.map { it.id }.filter { it.isNotBlank() }
                    if (!payload.canSend || composerSending || threadId == null ||
                        (draftText.isBlank() && attachmentIds.isEmpty())
                    ) {
                        return@ThreadPageContent
                    }
                    val draftAsTask = composerDraftAsTask
                    composerSending = true
                    composerFailureLabel = null
                    composerDraftText = ""
                    composerDraftAsTask = false
                    composerSyncNonce += 1
                    coroutineScope.launch {
                        val sendResponse =
                            runCatching {
                                dataSource.sendMessage(
                                    channelId = threadId,
                                    body = draftText,
                                    asTask = draftAsTask,
                                    attachmentIds = attachmentIds
                                )
                            }.getOrElse {
                                composerSending = false
                                composerDraftText = draftText
                                composerDraftAsTask = draftAsTask
                                composerFailureLabel = "Send failed. Try again."
                                composerSyncNonce += 1
                                return@launch
                            }
                        val sentPage =
                            when (sendResponse) {
                                is ThreadApiResult.Success -> sendResponse.data
                                is ThreadApiResult.Failure -> {
                                    composerSending = false
                                    composerDraftText = draftText
                                    composerDraftAsTask = draftAsTask
                                    composerFailureLabel =
                                        sendResponse.contractCode
                                            ?: sendResponse.errorBodyText?.takeIf { it.isNotBlank() }
                                            ?: "Send failed. Try again."
                                    composerSyncNonce += 1
                                    return@launch
                                }
                            }
                        if (sentPage.rawBodyText.isBlank()) {
                            composerSending = false
                            composerDraftText = draftText
                            composerDraftAsTask = draftAsTask
                            composerFailureLabel = "Send failed. Try again."
                            composerSyncNonce += 1
                            return@launch
                        }
                        val refreshed =
                            runCatching {
                                dataSource.fetchThreadPayload(payload)
                            }.getOrNull()
                        if (refreshed is ai.slock.compose.data.DataResult.Content) {
                            threadViewModel.dispatch(SharedThreadIntent.SetPayload(refreshed.data))
                        } else if (sentPage.rawBodyText.isNotBlank()) {
                            val session = dataSource.readSession()
                            threadViewModel.dispatch(
                                SharedThreadIntent.SetPayload(
                                    appendSharedThreadSentMessageFromResponse(
                                        basePayload = payload,
                                        bodyText = sentPage.rawBodyText,
                                        session = session
                                    )
                                )
                            )
                        } else {
                            threadViewModel.dispatch(SharedThreadIntent.Refresh)
                        }
                        uploadedAttachments = emptyList()
                        removedAttachmentIds = emptySet()
                        composerSyncNonce += 1
                        composerFailureLabel = null
                        composerSending = false
                    }
                }
                "retryThreadLoad" -> {
                    threadViewModel.dispatch(SharedThreadIntent.Refresh)
                }
                "attachImage", "attachFile" -> {
                    coroutineScope.launch {
                        val channelId = renderedPayload.sourceThreadId ?: renderedPayload.instanceId
                        val response =
                            bridge.threadAttachmentUpload(
                                channelId = channelId,
                                imageOnly = action == "attachImage"
                            )
                        val uploaded = parseSharedThreadUploadedAttachments(response)
                        if (uploaded.isEmpty()) {
                            response?.optString("errorCode")?.takeIf { it.isNotBlank() }?.let { error ->
                                bridge.toast("Attachment upload failed: $error")
                            }
                        } else {
                            uploadedAttachments =
                                (uploadedAttachments + uploaded).distinctBy { attachment -> attachment.id }
                            removedAttachmentIds =
                                removedAttachmentIds - uploaded.map { attachment -> attachment.id }.toSet()
                            composerFailureLabel = null
                            composerSyncNonce += 1
                        }
                    }
                }
                "removeAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        removedAttachmentIds = removedAttachmentIds + id
                        composerSyncNonce += 1
                    }
                }
                "retryAttachments" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        removedAttachmentIds = removedAttachmentIds - id
                        composerSyncNonce += 1
                    }
                }
                else -> {
                    bridge.composerEvent(
                        instanceId = composerPayload.instanceId.ifBlank { payload.instanceId },
                        action = action,
                        value = value,
                        asTask = asTask,
                        attachmentId = attachmentId
                    )
                }
            }
        }
    )
}

private suspend fun openSharedThreadAttachment(
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachmentId: String
) {
    when (val response = dataSource.attachmentUrl(attachmentId)) {
        is ThreadApiResult.Success -> bridge.openUrl(response.data.url)
        is ThreadApiResult.Failure -> bridge.toast("Unable to open attachment")
    }
}

internal fun parseSharedThreadUploadedAttachments(response: JSONObject?): List<SharedThreadComposerAttachment> {
    val bodyText = response?.optString("bodyText").orEmpty()
    if (bodyText.isBlank()) return emptyList()
    return runCatching {
        threadPageJson.parseToJsonElement(bodyText)
            .collectThreadAttachmentObjects()
            .mapIndexedNotNull { index, item ->
                val id = item.threadString("id", "attachmentId", "attachment_id", "_id")
                    ?: return@mapIndexedNotNull null
                SharedThreadComposerAttachment(
                    id = id,
                    fileName = item.threadString("name", "filename", "fileName", "title") ?: "Attachment ${index + 1}",
                    sizeLabel = item.threadString("sizeLabel", "size_label", "size").orEmpty(),
                    mimeType = item.threadString("mimeType", "mime_type", "contentType", "content_type"),
                    status = "ready"
                )
            }
            .distinctBy { attachment -> attachment.id }
    }.getOrElse {
        emptyList()
    }
}

private fun JsonElement.collectThreadAttachmentObjects(): List<JsonObject> =
    when (this) {
        is JsonArray -> flatMap { it.collectThreadAttachmentObjects() }
        is JsonObject -> {
            val direct =
                if (threadString("id", "attachmentId", "attachment_id", "_id") != null) {
                    listOf(this)
                } else {
                    emptyList()
                }
            val nested =
                listOf("attachments", "attachment", "files", "file", "data", "result", "uploaded")
                    .flatMap { key -> this[key]?.collectThreadAttachmentObjects().orEmpty() }
            direct + nested
        }
        else -> emptyList()
    }

private fun KuiklyThreadScreenPayload.appShellComposerPayload(
    seedText: String,
    seedAsTask: Boolean,
    syncNonce: Int,
    composerMode: String,
    statusLabel: String?,
    enabled: Boolean,
    baseComposer: KuiklyThreadComposerPayload =
        parseThreadComposerPayload(composerPayload, "{}", instanceId)
): String =
    buildSharedThreadComposerPayloadJson(
        SharedThreadComposerPayloadInput(
            seedText = seedText,
            seedAsTask = seedAsTask,
            syncNonce = syncNonce,
            focusRequestNonce = baseComposer.focusRequestNonce,
            placeholder = baseComposer.placeholder,
            enabled = enabled,
            showAsTaskToggle = baseComposer.showAsTaskToggle,
            composerMode = composerMode,
            statusLabel = statusLabel,
            pendingAttachments =
                baseComposer.pendingAttachments.map { attachment ->
                    SharedThreadComposerAttachment(
                        id = attachment.id,
                        fileName = attachment.fileName,
                        sizeLabel = attachment.sizeLabel,
                        mimeType = attachment.mimeType,
                        status = attachment.status
                    )
                },
            memberSuggestions =
                baseComposer.memberSuggestions.map { member ->
                    SharedThreadComposerMemberSuggestion(
                        displayName = member.displayName,
                        handle = member.handle
                    )
                },
            channelSuggestions =
                baseComposer.channelSuggestions.map { channel ->
                    SharedThreadComposerChannelSuggestion(
                        name = channel.name,
                        subtitle = channel.subtitle
                    )
                }
        )
    )

private fun String.toSharedThreadTaskStatus(): TaskStatus? =
    when (trim().lowercase().replace("-", "").replace("_", "").replace(" ", "")) {
        "todo" -> TaskStatus.Todo
        "inprogress" -> TaskStatus.InProgress
        "inreview" -> TaskStatus.InReview
        "done", "complete", "completed" -> TaskStatus.Done
        "closed" -> TaskStatus.Closed
        else -> null
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

internal fun buildSharedThreadPayloadFromMessagesResponse(
    basePayload: KuiklyThreadScreenPayload,
    bodyText: String,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload =
    runCatching {
        val root = threadPageJson.parseToJsonElement(bodyText)
        val currentUserId = session?.user?.id
        val authorDirectory = root.collectThreadAuthorProfiles()
        val messages =
            root.collectThreadMessageObjects()
                .mapIndexedNotNull { index, item ->
                    item.toSharedThreadMessageItem(
                        index = index,
                        currentUserId = currentUserId,
                        authorDirectory = authorDirectory
                    )
                }
        basePayload.copy(
            status = if (messages.isEmpty()) "empty" else "content",
            messages = messages,
            canLoadMoreBefore =
                root.threadBoolean("canLoadMoreBefore", "can_load_more_before", "hasMoreBefore", "has_more_before")
                    ?: root.threadBoolean("hasMore", "has_more", "hasPreviousPage", "has_previous_page")
                    ?: (basePayload.canLoadMoreBefore || messages.size >= ThreadMessagesPageSize),
            canLoadMoreAfter =
                root.threadBoolean("canLoadMoreAfter", "can_load_more_after", "hasMoreAfter", "has_more_after")
                    ?: root.threadBoolean("hasNextPage", "has_next_page")
                    ?: basePayload.canLoadMoreAfter,
            errorCode = null
        )
    }.getOrElse {
        basePayload.copy(status = "error", errorCode = "thread_payload_parse_failed")
    }

internal fun appendSharedThreadSentMessageFromResponse(
    basePayload: KuiklyThreadScreenPayload,
    bodyText: String,
    session: StoredAuthSession?
): KuiklyThreadScreenPayload {
    val sentPayload =
        buildSharedThreadPayloadFromMessagesResponse(
            basePayload = basePayload.copy(messages = emptyList()),
            bodyText = bodyText,
            session = session
        )
    val existingIds = basePayload.messages.map { it.id }.toSet()
    val sentMessages = sentPayload.messages.filterNot { it.id in existingIds }
    return if (sentMessages.isEmpty()) {
        basePayload.copy(errorCode = null)
    } else {
        basePayload.copy(
            status = "content",
            messages = basePayload.messages + sentMessages,
            errorCode = null
        )
    }
}

private fun JsonElement.collectThreadMessageObjects(): List<JsonObject> =
    when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val selfIsMessage =
                threadString("id", "messageId", "message_id", "_id") != null &&
                    threadString("body", "content", "text", "markdown", "message") != null
            if (selfIsMessage) {
                listOf(this)
            } else {
                val direct =
                    listOf("messages", "items", "results")
                        .firstNotNullOfOrNull { this[it] as? JsonArray }
                        ?.collectThreadMessageObjects()
                direct ?: when (val data = this["data"]) {
                    is JsonArray -> data.collectThreadMessageObjects()
                    is JsonObject -> data.collectThreadMessageObjects()
                    else -> emptyList()
                }
            }
        }
        else -> emptyList()
    }

private data class ThreadAuthorProfile(
    val id: String,
    val displayName: String,
    val handle: String? = null,
    val kind: String? = null,
    val avatarWireValue: String? = null,
    val gravatarHash: String? = null
)

private fun JsonElement.collectThreadAuthorProfiles(): Map<String, ThreadAuthorProfile> {
    val profiles = mutableMapOf<String, ThreadAuthorProfile>()
    fun add(item: JsonObject, impliedKind: String? = null) {
        item.toThreadAuthorProfile(impliedKind)?.let { profile ->
            profiles[profile.id] = profile
        }
    }
    fun collect(element: JsonElement?, impliedKind: String? = null) {
        when (element) {
            is JsonArray -> element.forEach { collect(it, impliedKind) }
            is JsonObject -> {
                val directoryKeys =
                    listOf(
                        "authors" to null,
                        "members" to null,
                        "users" to "human",
                        "agents" to "agent",
                        "participants" to null,
                        "profiles" to null,
                        "memberProfiles" to null,
                        "member_profiles" to null
                    )
                val directDirectory = directoryKeys.any { (key, _) -> element[key] is JsonArray }
                if (directDirectory) {
                    directoryKeys.forEach { (key, kind) -> collect(element[key], kind) }
                } else {
                    add(element, impliedKind)
                }
                collect(element["data"], impliedKind)
            }
            else -> Unit
        }
    }
    collect(this)
    return profiles
}

private fun JsonObject.toThreadAuthorProfile(impliedKind: String? = null): ThreadAuthorProfile? {
    val nested = nestedThreadProfileObject()
    val nestedProfile = nested?.toThreadAuthorProfile(impliedKind)
    val id =
        threadString(
            "id",
            "_id",
            "memberId",
            "member_id",
            "userId",
            "user_id",
            "agentId",
            "agent_id",
            "authorId",
            "author_id",
            "senderId",
            "sender_id"
        )
            ?: nestedProfile?.id
            ?: return null
    val name =
        threadString(
            "displayName",
            "display_name",
            "name",
            "username",
            "title",
            "label",
            "handle",
            "email"
        )
            ?: nestedProfile?.displayName
            ?: id
    return ThreadAuthorProfile(
        id = id,
        displayName = name,
        handle = threadString("handle", "username", "email") ?: nestedProfile?.handle,
        kind = threadString("kind", "memberKind", "member_kind", "profileKind", "profile_kind") ?: nestedProfile?.kind ?: impliedKind,
        avatarWireValue = threadAvatarWireValue() ?: nestedProfile?.avatarWireValue,
        gravatarHash =
            threadString("gravatarHash", "gravatar_hash", "gravatarMd5", "gravatar_md5", "gravatar")
                ?: nestedProfile?.gravatarHash
    )
}

private fun JsonObject.toSharedThreadMessageItem(
    index: Int,
    currentUserId: String?,
    authorDirectory: Map<String, ThreadAuthorProfile>
): KuiklyThreadMessageItem? {
    val author =
        firstThreadObject(
            "author",
            "user",
            "sender",
            "member",
            "agent",
            "createdBy",
            "createdByUser",
            "senderUser",
            "authorAgent"
        )
    val id = threadString("id", "messageId", "message_id", "_id") ?: "message-$index"
    val flatActorId =
        threadString(
            "authorId",
            "author_id",
            "senderId",
            "sender_id",
            "createdById",
            "created_by_id",
            "createdByUserId",
            "created_by_user_id",
            "senderUserId",
            "sender_user_id"
        )
    val flatAgentId =
        threadString(
            "agentId",
            "agent_id",
            "authorAgentId",
            "author_agent_id",
            "agentUserId",
            "agent_user_id"
        )
    val flatUserId =
        threadString(
            "userId",
            "user_id",
            "memberId",
            "member_id"
        )
    val authorId =
        author?.threadString("id", "_id", "userId", "user_id", "memberId", "member_id", "agentId", "agent_id")
            ?: flatActorId
            ?: flatAgentId
            ?: flatUserId
            ?: "unknown"
    val directoryAuthor = authorDirectory[authorId]
    val actorName =
        threadString(
            "authorDisplayName",
            "author_display_name",
            "authorName",
            "author_name",
            "senderDisplayName",
            "sender_display_name",
            "senderName",
            "sender_name",
            "createdByName",
            "created_by_name",
            "createdByDisplayName",
            "created_by_display_name",
            "createdByUsername",
            "created_by_username",
            "createdByUserDisplayName",
            "created_by_user_display_name",
            "profileDisplayName",
            "profile_display_name",
            "profileName",
            "profile_name",
            "authorHandle",
            "author_handle",
            "senderHandle",
            "sender_handle"
        )
    val agentName =
        threadString(
            "agentName",
            "agent_name",
            "agentDisplayName",
            "agent_display_name",
            "agentUsername",
            "agent_username",
            "agentTitle",
            "agent_title",
            "authorAgentName",
            "author_agent_name",
            "authorAgentDisplayName",
            "author_agent_display_name",
            "senderAgentName",
            "sender_agent_name",
            "senderAgentDisplayName",
            "sender_agent_display_name"
        )
    val userName =
        threadString(
            "userDisplayName",
            "user_display_name",
            "userName",
            "user_name",
            "memberDisplayName",
            "member_display_name",
            "memberName",
            "member_name"
        )
    val flatAuthorName =
        when {
            flatActorId != null -> actorName ?: agentName ?: userName
            flatAgentId != null -> agentName ?: actorName
            flatUserId != null -> userName ?: actorName
            else -> actorName ?: agentName ?: userName
        }
    val authorName =
        author?.threadString("displayName", "display_name", "name", "username", "title", "label", "handle", "email")
            ?: directoryAuthor?.displayName
            ?: flatAuthorName
            ?: "Unknown"
    val authorKind =
        author?.threadString("kind", "memberKind", "member_kind", "profileKind", "profile_kind")
            ?: directoryAuthor?.kind
            ?: threadString("authorKind", "author_kind", "memberKind", "member_kind", "profileKind", "profile_kind")
            ?: when {
                flatAgentId != null || authorId.isLikelyThreadAgentId() -> "agent"
                flatUserId != null -> "human"
                else -> ""
            }
    val body = threadString("body", "content", "text", "markdown", "message").orEmpty()
    val messageType =
        threadString("messageType", "message_type", "type", "kind")
            ?.takeIf { it.contains("system", ignoreCase = true) }
            ?.let { "System" }
            ?: "Chat"
    val avatarWireValue =
        author?.threadAvatarWireValue()
            ?: directoryAuthor?.avatarWireValue
            ?: threadAvatarWireValue()
    val gravatarHash =
        author?.threadString("gravatarHash", "gravatar_hash", "gravatarMd5", "gravatar_md5", "gravatar")
            ?: directoryAuthor?.gravatarHash
            ?: threadString("gravatarHash", "gravatar_hash", "gravatarMd5", "gravatar_md5", "gravatar")
    val task = firstThreadObject("task", "taskInfo", "task_info", "taskSummary", "task_summary")
    return KuiklyThreadMessageItem(
        id = id,
        authorId = authorId,
        authorName = authorName,
        authorKind = authorKind,
        isOwn = currentUserId != null && authorId == currentUserId,
        avatarLabel = authorName.take(1).uppercase(),
        avatarWireValue = avatarWireValue,
        gravatarHash = gravatarHash,
        timestampLabel =
            threadString(
                "timestampLabel",
                "timestamp_label",
                "createdAt",
                "created_at",
                "sentAt",
                "sent_at",
                "timestamp",
                "updatedAt",
                "updated_at"
            ).orEmpty(),
        seq =
            threadLong(
                "seq",
                "sequence",
                "sequenceNumber",
                "sequence_number",
                "messageSeq",
                "message_seq",
                "serverSeq",
                "server_seq",
                "createdSeq",
                "created_seq",
                "logSeq",
                "log_seq"
            ),
        messageType = messageType,
        systemEventType = threadString("systemEventType", "system_event_type", "eventType", "event_type"),
        metadata = (this["metadata"] as? JsonObject).threadStringMap(),
        body = body,
        replyCount = threadReplyCount(),
        taskId =
            threadString("taskId", "task_id")
                ?: task?.threadString("id", "_id", "taskId", "task_id"),
        taskNumber =
            threadInt("taskNumber", "task_number")
                ?: task?.threadInt("taskNumber", "task_number", "number"),
        taskStatus =
            threadString("taskStatus", "task_status")
                ?: task?.threadString("status", "taskStatus", "task_status"),
        taskAssigneeName =
            threadString("taskAssigneeName", "task_assignee_name", "assigneeName", "assignee_name")
                ?: task?.threadTaskAssigneeName(),
        attachments = (this["attachments"] as? JsonArray)?.mapIndexedNotNull { attachmentIndex, item ->
            (item as? JsonObject)?.toSharedThreadAttachmentItem(attachmentIndex)
        }.orEmpty()
    )
}

internal fun StoredAuthSession?.threadSelfMentionNames(): Set<String> =
    this?.user?.let { user ->
        listOfNotNull(
            user.displayName,
            user.name,
            user.email.substringBefore("@").ifBlank { null },
            user.bestDisplayName
        )
            .map { it.removePrefix("@").trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }.orEmpty()

private fun JsonObject.toSharedThreadAttachmentItem(index: Int): KuiklyThreadAttachmentItem? {
    val id = threadString("id", "attachmentId", "attachment_id", "_id") ?: "attachment-$index"
    val name = threadString("name", "filename", "fileName", "title") ?: id
    return KuiklyThreadAttachmentItem(
        id = id,
        name = name,
        mimeType = threadString("mimeType", "mime_type", "contentType", "content_type").orEmpty(),
        sizeLabel = threadString("sizeLabel", "size_label", "size").orEmpty(),
        thumbnailUrl =
            threadString(
                "thumbnailUrl",
                "thumbnail_url",
                "previewUrl",
                "preview_url",
                "imageUrl",
                "image_url",
                "url"
            )
    )
}

private fun JsonObject?.threadStringMap(): Map<String, String> =
    this?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull?.let { key to it.sanitizeThreadSystemText() }
    }?.toMap().orEmpty()

private fun JsonObject.threadAvatarWireValue(): String? {
    threadString(
        "avatarWireValue",
        "avatarUrl",
        "avatar_url",
        "imageUrl",
        "image_url",
        "profileImageUrl",
        "profile_image_url",
        "pictureUrl",
        "picture_url"
    )?.let { return it }
    (this["avatar"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { value ->
        return if (value.startsWith("gravatar:", ignoreCase = true)) {
            value.substringAfter(":").toGravatarAvatarUrl()
        } else {
            value
        }
    }
    val avatar = this["avatar"] as? JsonObject ?: return null
    val type = avatar.threadString("type", "kind")?.lowercase()
    val hash = avatar.threadString("hash", "gravatarHash", "gravatar_hash", "emailHash", "email_hash")
    return when {
        type == "gravatar" && !hash.isNullOrBlank() -> hash.toGravatarAvatarUrl()
        type == "pixel" -> avatar.threadString("key", "seed")?.let { "pixel:$it" }
        type == "random" -> avatar.threadString("key", "seed")?.let { "random:$it" }
        else -> avatar.threadString("url", "imageUrl", "image_url", "avatarUrl", "avatar_url")
    }
}

private fun JsonObject.firstThreadObject(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { name -> this[name] as? JsonObject }

private fun JsonObject.nestedThreadProfileObject(): JsonObject? =
    firstThreadObject("profile", "member", "user", "agent", "author", "sender")

private fun JsonObject.threadReplyCount(): Int =
    threadInt(
        "replyCount",
        "reply_count",
        "repliesCount",
        "replies_count",
        "threadReplyCount",
        "thread_reply_count",
        "threadRepliesCount",
        "thread_replies_count",
        "replies"
    )
        ?: firstThreadObject("thread", "replyThread", "reply_thread", "repliesSummary", "replies_summary")
            ?.threadInt(
                "replyCount",
                "reply_count",
                "repliesCount",
                "replies_count",
                "count",
                "total"
            )
        ?: 0

private fun JsonObject.threadTaskAssigneeName(): String? =
    threadString(
        "taskAssigneeName",
        "task_assignee_name",
        "assigneeName",
        "assignee_name",
        "claimedByName",
        "claimed_by_name"
    )
        ?: firstThreadObject("assignee", "assignedTo", "assigned_to", "claimedBy", "claimed_by")
            ?.threadString(
                "displayName",
                "display_name",
                "name",
                "handle",
                "username",
                "email"
            )

private fun JsonObject.threadString(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

private fun JsonObject.threadInt(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.intOrNull }

private fun JsonObject.threadLong(vararg names: String): Long? =
    names.firstNotNullOfOrNull { name -> (this[name] as? JsonPrimitive)?.longOrNull }

private fun JsonElement.threadBoolean(vararg names: String): Boolean? =
    (this as? JsonObject)?.let { obj ->
        names.firstNotNullOfOrNull { name -> (obj[name] as? JsonPrimitive)?.booleanOrNull }
            ?: (obj["data"] as? JsonObject)?.let { data ->
                names.firstNotNullOfOrNull { name -> (data[name] as? JsonPrimitive)?.booleanOrNull }
            }
    }

@Composable
private fun ThreadPageContent(
    payload: KuiklyThreadScreenPayload,
    filesPayload: KuiklyThreadFilesPayload,
    composerPayload: KuiklyThreadComposerPayload,
    highlightedMentions: Set<String>,
    onBack: () -> Unit,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    var selectedTab by remember(payload.selectedTab) {
        mutableStateOf(KuiklyThreadTab.fromWireValue(payload.selectedTab))
    }
    var activeModal by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var activeMemberId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var composerKeyboardInsetDp by remember(payload.instanceId) { mutableStateOf(0f) }
    val openMarkdownTag: (ThreadMarkdownTag) -> Unit = { tag ->
        when (tag.kind) {
            ThreadMarkdownTagKind.UserMention -> {
                val member = payload.findMarkdownMentionMember(tag)
                if (member != null) {
                    activeMemberId = member.id
                    activeModal = "memberDetail"
                } else {
                    onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
                }
            }
            ThreadMarkdownTagKind.Task -> {
                val taskId = payload.findMarkdownTaskId(tag)
                if (!taskId.isNullOrBlank()) {
                    onThreadEvent("openTask", taskId, null, null)
                } else {
                    onThreadEvent("openMarkdownTag", tag.label, null, tag.kind.name)
                }
            }
            ThreadMarkdownTagKind.Channel -> {
                onThreadEvent("openMarkdownChannel", tag.channelName ?: tag.label, null, tag.kind.name)
            }
            ThreadMarkdownTagKind.ChannelThread -> {
                onThreadEvent("openMarkdownThreadRef", tag.label, null, tag.threadShortId)
            }
        }
    }
    val openMarkdownUrl: (String) -> Unit = { url ->
        onThreadEvent("openMarkdownUrl", url, null, null)
    }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Column(
            modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
        ) {
            ThreadPageHeader(
                payload = payload,
                onBack = onBack,
                onOpenInChannel = {
                    onThreadEvent("openParentMessage", payload.originParentMessageId, null, null)
                },
                onShowMembers = {
                    activeModal = "members"
                },
                onShowSettings = {
                    activeModal = "settings"
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
                            highlightedMentions = highlightedMentions,
                            onThreadEvent = onThreadEvent,
                            onOpenMarkdownTag = openMarkdownTag,
                            onOpenMarkdownUrl = openMarkdownUrl
                        )
                    KuiklyThreadTab.Tasks ->
                        ThreadTasksTab(
                            tasks = payload.tasks,
                            openTaskEnabled = payload.showTaskOpenActions,
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
                Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
                    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(
                                start = SlockSpacing.Md,
                                top = SlockSpacing.Sm,
                                end = SlockSpacing.Md,
                                bottom = SlockSpacing.Sm + composerKeyboardInsetDp.dp
                            )
                    ) {
                        ThreadComposerContent(
                            payload = composerPayload,
                            onEvent = onComposerEvent,
                            onKeyboardHeightChange = { nextInset ->
                                if (nextInset != composerKeyboardInsetDp) {
                                    composerKeyboardInsetDp = nextInset
                                }
                            }
                        )
                    }
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
                        activeMemberId = memberId
                        activeModal = "memberDetail"
                    }
                )
            "memberDetail" ->
                payload.members.firstOrNull { it.id == activeMemberId }?.let { member ->
                    ThreadMemberDetailModal(
                        member = member,
                        removeMemberStatus = payload.removeMemberStatus,
                        removingMemberId = payload.removingMemberId,
                        removeMemberErrorCode = payload.removeMemberErrorCode,
                        onDismiss = { activeModal = null },
                        onBack = { activeModal = "members" },
                        onOpenProfile = {
                            activeModal = null
                            onThreadEvent("openMember", member.id, null, null)
                        },
                        onRemoveMember = {
                            onThreadEvent("removeMember", member.id, null, null)
                        }
                    )
                } ?: SlockModalLayer(
                    title = "MEMBER",
                    subtitle = "Member details are no longer available.",
                    onDismiss = { activeModal = null }
                ) {
                    ThreadModalEmptyState(
                        title = "Member not found",
                        detail = "Go back to members and refresh the conversation."
                    )
                }
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
                    channelName = payload.title,
                    subtitle = payload.subtitle,
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
    onOpenInChannel: () -> Unit,
    onShowMembers: () -> Unit,
    onShowSettings: () -> Unit
) {
    val showCloseActionAtEnd = payload.navigationLabel.equals("Close", ignoreCase = true)
    val topSafeArea = slockTopSafeAreaPadding()
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(
                    start = SlockSpacing.Md,
                    top = topSafeArea + SlockSpacing.Sm,
                    end = SlockSpacing.Md,
                    bottom = SlockSpacing.Sm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            if (!showCloseActionAtEnd) {
                ThreadHeaderIconButton(icon = "arrow-left", onClick = onBack)
            }
            if (payload.showModeTabs) {
                ThreadHashTile()
            }
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
            if (payload.showOpenInChannelAction) {
                ThreadHeaderIconButton(icon = "external-link", onClick = onOpenInChannel)
            }
            if (showCloseActionAtEnd) {
                ThreadHeaderIconButton(icon = "x", onClick = onBack)
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
    SlockHeaderIconAction(icon = icon, contentDescription = icon, onClick = onClick)
}

@Composable
private fun ThreadMembersHeaderButton(count: Int, onClick: () -> Unit) {
    SlockHeaderIconAction(icon = "users", contentDescription = "Members", onClick = onClick, label = count.toString())
}

@Composable
private fun ThreadHeaderAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 8.dp, vertical = 6.dp)
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
    Column(modifier = Modifier.fillMaxWidth().height(ThreadTabHeight).background(SlockColor.SurfaceWhite)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            KuiklyThreadTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Row(
                    modifier = Modifier.width(ThreadTabWidth)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(tab.icon, modifier = Modifier.size(18.dp), color = "#FF000000")
                    Text(
                        text = tab.label.uppercase(),
                        style = SlockType.Button.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .height(ThreadTabHeight - SlockStroke.Default)
                        .width(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(SlockColor.SurfaceWhite))
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

private val ThreadTabWidth = 116.dp
private val ThreadTabHeight = 38.dp

@Composable
private fun ThreadChatTab(
    payload: KuiklyThreadScreenPayload,
    highlightedMentions: Set<String>,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
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
                highlightedMentions = highlightedMentions,
                onThreadEvent = onThreadEvent,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl
            )
    }
}

@Composable
private fun ThreadMessagesList(
    payload: KuiklyThreadScreenPayload,
    highlightedMentions: Set<String>,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    if (payload.messages.isEmpty()) {
        ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
        return
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var actionMessageId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var actionMenuAnchor by remember(payload.instanceId) { mutableStateOf(Offset(16f, 16f)) }
    var allowBottomAutoAnchor by remember(payload.instanceId) { mutableStateOf(true) }
    val latestItemIndex = payload.latestMessageListItemIndex()
    val latestMessageLayoutKey = payload.messages.joinToString("|") { "${it.id}:${it.body.length}:${it.attachments.size}:${it.taskId.orEmpty()}:${it.replyCount}" }
    LaunchedEffect(payload.instanceId, latestItemIndex) {
        snapshotFlow {
            listState.isScrollInProgress to listState.isLatestItemAtRenderedBottom(latestItemIndex)
        }.collect { (isScrollInProgress, isAtBottom) ->
            if (isScrollInProgress && !isAtBottom) {
                allowBottomAutoAnchor = false
            } else if (isAtBottom) {
                allowBottomAutoAnchor = true
            }
        }
    }
    LaunchedEffect(payload.instanceId, latestMessageLayoutKey, latestItemIndex, allowBottomAutoAnchor) {
        if (latestItemIndex >= 0) {
            if (!allowBottomAutoAnchor && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
                return@LaunchedEffect
            }
            repeat(ThreadInitialBottomAnchorAttempts) {
                if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
                    allowBottomAutoAnchor = false
                    return@LaunchedEffect
                }
                listState.scrollLatestItemToRenderedBottom(latestItemIndex)
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
                item(key = payload.originListItemKey()) {
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
                item(key = ThreadOlderMessagesListItemKey) {
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
            items(payload.messages.size, key = { payload.messages[it].messageListItemKey() }) { index ->
                val message = payload.messages[index]
                if (message.isSystemMessage()) {
                    ThreadSystemMessageRow(
                        message = message,
                        highlightedMentions = highlightedMentions,
                        showReplyAction = payload.showReplyActions,
                        timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                        taskOpenEnabled = payload.showTaskOpenActions,
                        taskStatusActionsEnabled = payload.showTaskStatusActions,
                        onOpenThread = { onThreadEvent("openMessageThread", message.id, null, null) },
                        onOpenTask = { onThreadEvent("openTask", it, null, null) },
                        onTaskStatusChange = { taskId, status ->
                            onThreadEvent("setTaskStatus", taskId, null, status.key)
                        },
                        onOpenMarkdownTag = onOpenMarkdownTag,
                        onOpenMarkdownUrl = onOpenMarkdownUrl
                    )
                } else {
                    ThreadMessageCard(
                        message = message,
                        highlightedMentions = highlightedMentions,
                        alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                        showReplyAction = payload.showReplyActions,
                        taskOpenEnabled = payload.showTaskOpenActions,
                        taskStatusActionsEnabled = payload.showTaskStatusActions,
                        onShowActions = { anchor ->
                            actionMenuAnchor = anchor
                            actionMessageId = message.id
                        },
                        onOpenMember = { onThreadEvent("openMember", it, null, null) },
                        onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                        onOpenTask = { onThreadEvent("openTask", it, null, null) },
                        onTaskStatusChange = { taskId, status ->
                            onThreadEvent("setTaskStatus", taskId, null, status.key)
                        },
                        timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                        onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                        onOpenMarkdownTag = onOpenMarkdownTag,
                        onOpenMarkdownUrl = onOpenMarkdownUrl
                    )
                }
            }
            if (payload.canLoadMoreAfter) {
                item(key = ThreadNewerMessagesListItemKey) {
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
                        allowBottomAutoAnchor = true
                        repeat(ThreadBackToBottomAnchorAttempts) { attempt ->
                            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
                            if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                                listState.isLatestItemAtRenderedBottom(latestItemIndex)
                            ) {
                                return@launch
                            }
                            delay(16)
                        }
                    }
                }
            )
        }
        actionMessageId?.let { messageId ->
            payload.messages.firstOrNull { it.id == messageId }?.let { message ->
                ThreadMessageActionMenu(
                    message = message,
                    anchor = actionMenuAnchor,
                    showReplyAction = payload.showReplyActions,
                    showSaveAction = payload.showMessageSaveAction,
                    showTaskStatusAction = payload.showTaskStatusActions,
                    onDismiss = { actionMessageId = null },
                    onReply = {
                        actionMessageId = null
                        onThreadEvent("openMessageThread", message.id, null, null)
                    },
                    onCopy = {
                        actionMessageId = null
                        onThreadEvent("copyMessage", message.id, null, null)
                    },
                    onSave = {
                        actionMessageId = null
                        onThreadEvent("saveMessage", message.id, null, null)
                    },
                    onTaskStatusChange = { action ->
                        actionMessageId = null
                        onThreadEvent(action, message.taskId, null, null)
                    }
                )
            }
        }
    }
}

private const val ThreadOlderMessagesListItemKey = "thread-page:older-messages"
private const val ThreadNewerMessagesListItemKey = "thread-page:newer-messages"

private fun KuiklyThreadScreenPayload.originListItemKey(): String =
    "thread-page:origin:${originParentMessageId ?: originMessage?.id ?: instanceId}"

private fun KuiklyThreadMessageItem.messageListItemKey(): String =
    "thread-page:message:$id"

private fun KuiklyThreadScreenPayload.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    var index = 0
    if (originStatus != "none") index += 1
    if (canLoadMoreBefore) index += 1
    return index + messages.lastIndex
}

internal fun KuiklyThreadScreenPayload.formatTimestampLabel(value: String): String =
    todayEpochDay?.let { today ->
        formatSlockTimestampLabel(value, localOffsetMinutes, today)
    } ?: value

private suspend fun LazyListState.scrollLatestItemToRenderedBottom(latestItemIndex: Int) {
    if (latestItemIndex < 0) return
    if (layoutInfo.totalItemsCount <= latestItemIndex) {
        return
    }
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val offset =
        latestItem
            ?.let { it.size - viewportHeight }
            ?: 0
    scrollToItem(latestItemIndex, offset)
}

private fun LazyListState.shouldShowThreadBackToBottom(latestItemIndex: Int): Boolean {
    if (latestItemIndex <= 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val latestBottom = latestItem?.let { it.offset + it.size } ?: return true
    return latestBottom < layoutInfo.viewportEndOffset - ThreadBackToBottomTolerancePx
}

private fun LazyListState.isLatestItemAtRenderedBottom(latestItemIndex: Int): Boolean {
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex } ?: return false
    val latestBottom = latestItem.offset + latestItem.size
    return kotlin.math.abs(latestBottom - layoutInfo.viewportEndOffset) <= ThreadBackToBottomTolerancePx
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
        Button(
            onClick = onClick,
            modifier = Modifier
                .background(SlockColor.AccentCyan)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = SlockSpacing.Sm, vertical = 5.dp)
        ) {
            Row(
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
}

private const val ThreadBackToBottomTolerancePx = 24
private const val ThreadBottomAnchorMinimumAttempts = 2
private const val ThreadInitialBottomAnchorAttempts = 90
private const val ThreadBackToBottomAnchorAttempts = 12

@Composable
private fun ThreadSystemMessageRow(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String>,
    showReplyAction: Boolean,
    timestampLabel: String,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onTaskStatusChange: (String, ThreadTaskStatusOption) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .clickable { expanded = !expanded }
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = message.collapsedSystemSummary(timestampLabel),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Medium, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (expanded) {
            ThreadSystemMessageExpandedContent(
                message = message,
                highlightedMentions = highlightedMentions,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl
            )
        }
        ThreadMessageFooterMeta(
            message = message,
            showReplyAction = showReplyAction,
            taskOpenEnabled = taskOpenEnabled,
            taskStatusActionsEnabled = taskStatusActionsEnabled,
            alignEnd = false,
            onOpenThread = onOpenThread,
            onOpenTask = onOpenTask,
            onTaskStatusChange = onTaskStatusChange
        )
    }
}

@Composable
private fun ThreadSystemMessageExpandedContent(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String>,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = "Body",
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SlockMarkdownBody(
            body = message.body.sanitizeThreadSystemText(),
            textStyle = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = SlockColor.TextBlack),
            highlightedMentions = highlightedMentions,
            onOpenTag = onOpenMarkdownTag,
            onOpenUrl = onOpenMarkdownUrl
        )
        val details = message.expandedSystemDetails()
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun ThreadInlineActionRow(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
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
    highlightedMentions: Set<String> = emptySet(),
    alignOwnMessageRight: Boolean = false,
    showReplyAction: Boolean = true,
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    timestampLabel: String = message.timestampLabel,
    onShowActions: (Offset) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onTaskStatusChange: (String, ThreadTaskStatusOption) -> Unit,
    onOpenAttachment: (String) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(SlockSpacing.Md, Alignment.End) else Arrangement.spacedBy(SlockSpacing.Md)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    val messageMaxWidthFraction = if (alignOwnMessageRight) 0.82f else 1f
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = horizontalArrangement
            ) {
                if (!alignOwnMessageRight) {
                    ThreadMessageAvatar(
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = message.resolvedThreadAvatarWireValue(),
                        gravatarHash = message.gravatarHash,
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    horizontalAlignment = contentAlignment
                ) {
                    Button(
                        modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)
                            .background(Color.Transparent),
                        onClick2 = onShowActions
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                if (alignOwnMessageRight) {
                                    Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End)
                                } else {
                                    Arrangement.spacedBy(SlockSpacing.Sm)
                                }
                        ) {
                            Text(
                                text = message.authorName,
                                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                                color = SlockColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp).clickable { onOpenMember(message.authorId) }
                            )
                            Text(
                                text = timestampLabel,
                                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                                color = SlockColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (message.body.isNotBlank()) {
                        Button(
                            modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)
                                .background(Color.Transparent),
                            onClick2 = onShowActions
                        ) {
                            SlockMarkdownBody(
                                body = message.body,
                                textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                highlightedMentions = highlightedMentions,
                                onOpenTag = onOpenMarkdownTag,
                                onOpenUrl = onOpenMarkdownUrl
                            )
                        }
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                        ThreadMessageAttachments(
                            attachments = message.attachments,
                            alignEnd = alignOwnMessageRight,
                            onOpenAttachment = onOpenAttachment
                        )
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                        ThreadMessageFooterMeta(
                            message = message,
                            showReplyAction = showReplyAction,
                            taskOpenEnabled = taskOpenEnabled,
                            taskStatusActionsEnabled = taskStatusActionsEnabled,
                            alignEnd = alignOwnMessageRight,
                            onOpenThread = { onOpenThread(message.id) },
                            onOpenTask = onOpenTask,
                            onTaskStatusChange = onTaskStatusChange
                        )
                    }
                }
                if (alignOwnMessageRight) {
                    ThreadMessageAvatar(
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = message.resolvedThreadAvatarWireValue(),
                        gravatarHash = message.gravatarHash,
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
            }
        }
    }
}

private fun Modifier.threadMessageContentWidth(maxWidthFraction: Float): Modifier =
    widthIn(max = 680.dp).fillMaxWidth(maxWidthFraction)

internal fun KuiklyThreadMessageItem.resolvedThreadAvatarWireValue(): String? =
    avatarWireValue ?: if (authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()) {
        "random:${authorId.ifBlank { authorName }}"
    } else {
        null
    }

private fun String.isLikelyThreadAgentId(): Boolean =
    startsWith("agent", ignoreCase = true) ||
        startsWith("ai-", ignoreCase = true) ||
        contains("_agent", ignoreCase = true) ||
        contains("-agent", ignoreCase = true)

@Composable
private fun ThreadMessageFooterMeta(
    message: KuiklyThreadMessageItem,
    showReplyAction: Boolean,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    alignEnd: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onTaskStatusChange: (String, ThreadTaskStatusOption) -> Unit
) {
    val hasTask =
        !message.taskId.isNullOrBlank() ||
            message.taskNumber != null ||
            !message.taskStatus.isNullOrBlank() ||
            !message.taskAssigneeName.isNullOrBlank()
    val hasReplies = showReplyAction && message.replyCount > 0
    if (!hasTask && !hasReplies) return
    var statusSheetOpen by remember(message.id, message.taskStatus) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) Arrangement.spacedBy(SlockSpacing.Sm, Alignment.End) else Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        if (hasTask) {
            val label =
                buildString {
                    append(message.taskNumber?.let { "#$it" } ?: "Task")
                    message.taskAssigneeName?.takeIf { it.isNotBlank() }?.let {
                        append(" @")
                        append(it.removePrefix("@"))
                    }
                }
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            ThreadMetaChip(
                label = label,
                icon = selectedStatus.icon,
                color = selectedStatus.color,
                onClick = if (taskOpenEnabled || taskStatusActionsEnabled) {
                    { statusSheetOpen = true }
                } else {
                    null
                },
                modifier = Modifier.weight(1f, fill = false)
            )
            if (statusSheetOpen && (taskOpenEnabled || taskStatusActionsEnabled)) {
                ThreadTaskStatusSheet(
                    message = message,
                    selectedStatus = selectedStatus,
                    onDismiss = { statusSheetOpen = false },
                    onOpenTask = {
                        statusSheetOpen = false
                        if (taskOpenEnabled) {
                            message.taskId?.let(onOpenTask)
                        }
                    },
                    onStatusSelected = { status ->
                        statusSheetOpen = false
                        if (taskStatusActionsEnabled) {
                            message.taskId?.let { taskId -> onTaskStatusChange(taskId, status) }
                        }
                    }
                )
            }
        }
        if (hasReplies) {
            ThreadMetaChip(
                label = "${message.replyCount} replies",
                icon = "message-square",
                color = SlockColor.AppBgLightBlue,
                onClick = onOpenThread,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

private data class ThreadTaskStatusOption(
    val key: String,
    val label: String,
    val icon: String,
    val color: Color
)

private val threadTaskStatusOptions =
    listOf(
        ThreadTaskStatusOption("Todo", "Todo", "circle", SlockColor.AccentOrange),
        ThreadTaskStatusOption("InProgress", "In progress", "play", SlockColor.AccentCyan),
        ThreadTaskStatusOption("InReview", "In review", "eye", SlockColor.AccentLavender),
        ThreadTaskStatusOption("Done", "Done", "circle-check-big", SlockColor.AccentLime),
        ThreadTaskStatusOption("Closed", "Closed", "ban", SlockColor.AccentRed)
    )

private fun String?.threadTaskStatusOption(): ThreadTaskStatusOption {
    val normalized =
        this?.trim()?.lowercase().orEmpty().replace("-", "").replace("_", "").replace(" ", "")
    return threadTaskStatusOptions.firstOrNull { option ->
        option.key.lowercase() == normalized ||
            option.label.lowercase().replace(" ", "") == normalized
    } ?: threadTaskStatusOptions.first()
}

internal fun KuiklyThreadMessageItem.isSystemMessage(): Boolean =
    messageType.equals("System", ignoreCase = true) || messageType.equals("system", ignoreCase = true)

internal fun KuiklyThreadMessageItem.collapsedSystemSummary(timestamp: String): String =
    listOf(timestamp, body.sanitizeThreadSystemText())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

internal fun KuiklyThreadMessageItem.expandedSystemDetails(): String =
    buildList {
        systemEventType?.takeIf { it.isNotBlank() }?.let { add("Type: ${it.sanitizeThreadSystemText()}") }
        metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                add("${key.sanitizeThreadSystemText()}: ${value.sanitizeThreadSystemText()}")
            }
        }
    }.joinToString("\n")

internal fun String.sanitizeThreadSystemText(): String =
    replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</p\s*>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .decodeBasicHtmlEntities()
        .collapseThreadInlineWhitespace()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

private fun String.collapseThreadInlineWhitespace(): String =
    buildString(length) {
        var pendingSpace = false
        for (char in this@collapseThreadInlineWhitespace) {
            if (char.isThreadInlineWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && isNotEmpty()) {
                    append(' ')
                }
                append(char)
                pendingSpace = false
            }
        }
    }

private fun Char.isThreadInlineWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C' || this == '\r'

private fun String.decodeBasicHtmlEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

@Composable
private fun ThreadTaskStatusSheet(
    message: KuiklyThreadMessageItem,
    selectedStatus: ThreadTaskStatusOption,
    onDismiss: () -> Unit,
    onOpenTask: () -> Unit,
    onStatusSelected: (ThreadTaskStatusOption) -> Unit
) {
    SlockModalLayer(
        title = "Task status",
        subtitle =
            listOfNotNull(
                message.taskNumber?.let { "#$it" },
                message.taskAssigneeName?.takeIf { it.isNotBlank() }?.let {
                    "@${it.removePrefix("@")}"
                }
            ).joinToString(" "),
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            threadTaskStatusOptions.forEach { status ->
                ThreadTaskStatusSheetRow(
                    status = status,
                    selected = status.key == selectedStatus.key,
                    onClick = { onStatusSelected(status) }
                )
            }
            SlockActionSheetRow(
                title = "Open task",
                subtitle = message.taskNumber?.let { "#$it" }.orEmpty(),
                icon = "external-link",
                onClick = onOpenTask
            )
        }
    }
}

@Composable
private fun ThreadTaskStatusSheetRow(
    status: ThreadTaskStatusOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier =
            Modifier.fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier =
                    Modifier.size(34.dp)
                        .background(status.color)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(status.icon, modifier = Modifier.size(17.dp))
            }
            Text(
                text = status.label,
                style = SlockType.ButtonBold,
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (selected) {
                SlockLucideIcon("check", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ThreadMetaChip(
    label: String,
    icon: String,
    color: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val clickableModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = modifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(color)
            .then(clickableModifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            SlockLucideIcon(icon, modifier = Modifier.size(12.dp), color = "#FF000000")
            Text(
                text = label,
                style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class ThreadMessageMenuAction(
    val label: String,
    val icon: String,
    val group: String,
    val onClick: () -> Unit
)

private val ThreadMessageMenuWidth = 208.dp
private const val ThreadMessageMenuFallbackHeight = 210f
private const val ThreadMessageMenuAnchorGap = 8f
private const val ThreadMessageMenuHorizontalNudge = 16f

@Composable
private fun ThreadMessageActionMenu(
    message: KuiklyThreadMessageItem,
    anchor: Offset,
    showReplyAction: Boolean,
    showSaveAction: Boolean,
    showTaskStatusAction: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onTaskStatusChange: (String) -> Unit
) {
    val actions =
        buildList {
            if (showReplyAction) {
                add(ThreadMessageMenuAction("Reply", "message-square", "message", onReply))
            }
            add(ThreadMessageMenuAction("Copy markdown", "copy", "copy", onCopy))
            if (showSaveAction) {
                add(ThreadMessageMenuAction("Save message", "bookmark", "message", onSave))
            }
            message.taskId?.takeIf { showTaskStatusAction && it.isNotBlank() }?.let {
                val isDone = message.taskStatus?.trim()?.lowercase() in setOf("done", "closed")
                add(
                    ThreadMessageMenuAction(
                        label = if (isDone) "Reopen task" else "Mark done",
                        icon = if (isDone) "refresh-cw" else "circle-check-big",
                        group = "task",
                        onClick = { onTaskStatusChange(if (isDone) "reopenTask" else "markTaskDone") }
                    )
                )
            }
        }
    val density = LocalDensity.current
    var overlaySize by remember { mutableStateOf(Size.Zero) }
    var menuSize by remember { mutableStateOf(Size.Zero) }
    val menuWidth = menuSize.width.takeIf { it > 0f } ?: ThreadMessageMenuWidth.value
    val menuHeight = menuSize.height.takeIf { it > 0f } ?: ThreadMessageMenuFallbackHeight
    val menuX =
        if (overlaySize.width > 0f) {
            coerceThreadMessageMenuOffset(
                value = anchor.x - ThreadMessageMenuHorizontalNudge,
                min = ThreadMessageMenuAnchorGap,
                max = overlaySize.width - menuWidth - ThreadMessageMenuAnchorGap
            )
        } else {
            anchor.x.coerceAtLeast(ThreadMessageMenuAnchorGap)
        }
    val preferredBelowY = anchor.y + ThreadMessageMenuAnchorGap
    val preferredAboveY = anchor.y - menuHeight - ThreadMessageMenuAnchorGap
    val maxY =
        if (overlaySize.height > 0f) {
            overlaySize.height - menuHeight - ThreadMessageMenuAnchorGap
        } else {
            preferredBelowY
        }
    val menuY =
        if (overlaySize.height > 0f && preferredBelowY + menuHeight > overlaySize.height - ThreadMessageMenuAnchorGap) {
            if (preferredAboveY >= ThreadMessageMenuAnchorGap) {
                preferredAboveY
            } else {
                maxY
            }
        } else {
            coerceThreadMessageMenuOffset(
                value = preferredBelowY,
                min = ThreadMessageMenuAnchorGap,
                max = maxY
            )
        }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
            .onGloballyPositioned { coordinates ->
                overlaySize = coordinates.size.toDpSize(density)
            },
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = menuX, y = menuY)
                .padding(end = 4.dp, bottom = 4.dp)
                .width(ThreadMessageMenuWidth)
                .onGloballyPositioned { coordinates ->
                    menuSize = coordinates.size.toDpSize(density)
                }
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = 4f, y = 4f)
                    .background(SlockColor.ShadowBlack)
            )
            Column(
                modifier = Modifier
                    .width(ThreadMessageMenuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
            ) {
                ThreadMessageReactionRow()
                ThreadMessageMenuDivider()
                actions.forEachIndexed { index, action ->
                    val previousGroup = actions.getOrNull(index - 1)?.group
                    if (index > 0 && previousGroup != action.group) {
                        ThreadMessageMenuDivider()
                    }
                    ThreadMessageMenuItem(action)
                }
            }
        }
    }
}

private fun IntSize.toDpSize(density: Density): Size =
    with(density) {
        Size(width.toDp().value, height.toDp().value)
    }

internal fun coerceThreadMessageMenuOffset(value: Float, min: Float, max: Float): Float {
    if (max < min) return min
    return value.coerceIn(min, max)
}

@Composable
private fun ThreadMessageReactionRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        listOf("👍", "❤", "🎉", "👀", "🔥", "🖼").forEach { reaction ->
            Text(
                text = reaction,
                style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack
            )
        }
    }
}

@Composable
private fun ThreadMessageMenuItem(action: ThreadMessageMenuAction) {
    Button(
        onClick = action.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .padding(horizontal = SlockSpacing.Sm, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockLucideIcon(action.icon, modifier = Modifier.size(16.dp), color = "#FF000000")
            Text(
                text = action.label,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ThreadMessageMenuDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
                        highlightedMentions = emptySet(),
                        showReplyAction = false,
                        onShowActions = {},
                        onOpenMember = {},
                        onOpenThread = {},
                        onOpenTask = {},
                        onTaskStatusChange = { _, _ -> },
                        timestampLabel = payload.formatTimestampLabel(origin.timestampLabel),
                        onOpenAttachment = {},
                        onOpenMarkdownTag = {},
                        onOpenMarkdownUrl = {}
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
        modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatar(
            avatarWireValue = imageUrl ?: avatarWireValue,
            label = avatarLabel,
            avatarSize = 40.dp
        )
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

private val SharedThreadAttachmentImageSize = 92.dp

private fun KuiklyThreadAttachmentItem.isImageAttachment(): Boolean =
    mimeType.startsWith("image/", ignoreCase = true)

@Composable
private fun ThreadMessageAttachments(
    attachments: List<KuiklyThreadAttachmentItem>,
    alignEnd: Boolean,
    onOpenAttachment: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    val imageAttachments = attachments.filter { it.isImageAttachment() }
    val fileAttachments = attachments.filterNot { it.isImageAttachment() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        if (imageAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        SlockSpacing.Sm,
                        if (alignEnd) Alignment.End else Alignment.Start
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(imageAttachments.size, key = { imageAttachments[it].id }) { index ->
                    ThreadImageAttachmentTile(
                        attachment = imageAttachments[index],
                        onOpen = { onOpenAttachment(imageAttachments[index].id) }
                    )
                }
            }
        }
        fileAttachments.forEach { attachment ->
            ThreadAttachmentChip(
                attachment = attachment,
                onOpen = { onOpenAttachment(attachment.id) }
            )
        }
    }
}

@Composable
private fun ThreadImageAttachmentTile(
    attachment: KuiklyThreadAttachmentItem,
    onOpen: () -> Unit
) {
    Box(
        modifier = Modifier.size(SharedThreadAttachmentImageSize)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceCream)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center
    ) {
        val thumbnailUrl = attachment.thumbnailUrl?.takeIf { it.isNotBlank() }
        if (thumbnailUrl != null) {
            SlockCachedImage(
                source = thumbnailUrl,
                contentDescription = attachment.name,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "IMG",
                style = SlockType.Button.copy(fontSize = 11.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
        Text(
            text = attachment.name,
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.92f))
                .padding(horizontal = SlockSpacing.Xs, vertical = 3.dp)
        )
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
                SlockCachedImage(
                    source = attachment.thumbnailUrl,
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
private fun ThreadTasksTab(
    tasks: List<KuiklyThreadTaskItem>,
    openTaskEnabled: Boolean,
    onOpenTask: (String) -> Unit
) {
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
            val clickableModifier =
                if (openTaskEnabled) {
                    Modifier.clickable { onOpenTask(task.id) }
                } else {
                    Modifier
                }
            Row(
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .then(clickableModifier)
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
    val agents = members.filter { it.kind.equals("agent", ignoreCase = true) }
    val humans = members.filter { !it.kind.equals("agent", ignoreCase = true) }
    SlockModalLayer(
        title = "MEMBERS (${membersCount.takeIf { it > 0 } ?: members.size})",
        subtitle = if (canAddMembers) "Manage people in this conversation" else "You do not have permission to add members.",
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(10.dp)
            ) {
                if (members.isEmpty()) {
                    ThreadModalEmptyState(
                        title = "No member details loaded",
                        detail = "Refresh this conversation and try again."
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (agents.isNotEmpty()) {
                            item { ThreadMemberSectionHeader("AGENTS", agents.size) }
                            items(agents.size, key = { agents[it].memberKey }) { index ->
                                val member = agents[index]
                                ThreadMemberRow(member = member, onClick = { onManageMember(member.id) })
                            }
                        }
                        if (humans.isNotEmpty()) {
                            item { ThreadMemberSectionHeader("HUMANS", humans.size) }
                            items(humans.size, key = { humans[it].memberKey }) { index ->
                                val member = humans[index]
                                ThreadMemberRow(member = member, onClick = { onManageMember(member.id) })
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { if (canAddMembers) onAddMember() },
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(if (canAddMembers) SlockColor.AccentPink else SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockLucideIcon("user-plus", modifier = Modifier.size(17.dp))
                    Text(
                        text = "+ Add Member",
                        style = SlockType.ButtonBold,
                        color = if (canAddMembers) SlockColor.TextBlack else SlockColor.TextMutedGray
                    )
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
    val agentCandidates = filteredCandidates.filter { it.kind.equals("agent", ignoreCase = true) }
    val humanCandidates = filteredCandidates.filter { !it.kind.equals("agent", ignoreCase = true) }
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
        title = "ADD MEMBER",
        subtitle = errorText ?: "Choose a user or agent to add",
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    if (agentCandidates.isNotEmpty()) {
                        item { ThreadMemberSectionHeader("AGENTS", agentCandidates.size) }
                        items(agentCandidates.size, key = { agentCandidates[it].memberKey }) { index ->
                            val member = agentCandidates[index]
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
                    if (humanCandidates.isNotEmpty()) {
                        item { ThreadMemberSectionHeader("HUMANS", humanCandidates.size) }
                        items(humanCandidates.size, key = { humanCandidates[it].memberKey }) { index ->
                            val member = humanCandidates[index]
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
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThreadModalActionButton(label = "Back", icon = "arrow-left", onClick = onDismiss)
                Spacer(modifier = Modifier.weight(1f))
                ThreadModalActionButton(
                    label = if (adding) "Adding" else "Add selected",
                    icon = "user-plus",
                    color = SlockColor.AccentPink,
                    onClick = {
                        if (adding || blocked) return@ThreadModalActionButton
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
private fun ThreadMemberSectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.AppBgLightBlue)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
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
        ThreadMemberAvatar(member = member, sizeDp = 36)
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
private fun ThreadMemberDetailModal(
    member: KuiklyThreadMemberItem,
    removeMemberStatus: String,
    removingMemberId: String?,
    removeMemberErrorCode: String?,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    onRemoveMember: () -> Unit
) {
    val isRemoving = removeMemberStatus == "removing" && removingMemberId == member.id
    val errorText =
        when {
            removeMemberStatus == "failed" && removingMemberId == member.id ->
                "Unable to remove member: ${removeMemberErrorCode ?: "member_remove_failed"}"
            removeMemberStatus == "no_permission" && removingMemberId == member.id ->
                "Unable to remove member: no permission"
            else -> null
        }
    SlockModalLayer(
        title = member.displayName.uppercase(),
        subtitle = errorText ?: member.handle.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ThreadMemberAvatar(member = member, sizeDp = 46)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = member.displayName,
                        style = SlockType.PageTitle,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = member.handle.takeIf { it.isNotBlank() }?.let { "@$it" } ?: member.id,
                        style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = member.roleLabel.ifBlank { if (member.kind.equals("agent", ignoreCase = true)) "Agent" else "Member" },
                        style = SlockType.Body,
                        color = SlockColor.TextBlack,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThreadModalActionButton(label = "Back", icon = "arrow-left", onClick = onBack)
                ThreadModalActionButton(label = "Open profile", icon = "user", onClick = onOpenProfile)
                if (member.kind.equals("agent", ignoreCase = true)) {
                    ThreadModalActionButton(
                        label = if (isRemoving) "Removing" else "Remove Agent",
                        icon = "user-minus",
                        color = SlockColor.AccentRed,
                        onClick = { if (!isRemoving) onRemoveMember() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMemberAvatar(member: KuiklyThreadMemberItem, sizeDp: Int) {
    Box(
        modifier = Modifier.size(sizeDp.dp)
            .background(if (member.kind.equals("agent", ignoreCase = true)) SlockColor.AccentLavender else SlockColor.BrandYellow)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        val avatarUrl = member.avatarUrl?.takeIf { it.isNotBlank() }
        if (avatarUrl != null) {
            SlockCachedImage(
                source = avatarUrl,
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
        ThreadMemberAvatar(member = member, sizeDp = 34)
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

private enum class ThreadSettingsDangerAction(
    val id: String,
    val title: String,
    val icon: String,
    val buttonLabel: String,
    val color: Color
) {
    Leave("leave", "LEAVE CHANNEL", "log-out", "Leave Channel", SlockColor.AccentOrange),
    Archive("archive", "ARCHIVE CHANNEL", "archive", "Archive Channel", SlockColor.AccentOrange),
    Delete("delete", "DELETE CHANNEL", "trash", "Delete Channel", SlockColor.AccentRed)
}

@Composable
private fun ThreadSettingsModal(
    channelName: String,
    subtitle: String?,
    actions: List<KuiklyThreadSettingsAction>,
    onDismiss: () -> Unit,
    onOpenAction: (String) -> Unit
) {
    var name by remember(channelName) { mutableStateOf(channelName) }
    var description by remember(subtitle) { mutableStateOf(subtitle.orEmpty()) }
    var dangerAction by remember { mutableStateOf<ThreadSettingsDangerAction?>(null) }
    var confirmationName by remember(dangerAction) { mutableStateOf("") }
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
    val dangerActions = effectiveActions.mapNotNull { action ->
        when (action.id) {
            "leave" -> ThreadSettingsDangerAction.Leave
            "archive" -> ThreadSettingsDangerAction.Archive
            "delete" -> ThreadSettingsDangerAction.Delete
            else -> null
        }?.let { it to action }
    }
    val nonDangerActions = effectiveActions.filter { action ->
        action.id != "leave" && action.id != "archive" && action.id != "delete"
    }
    val activeDanger = dangerAction
    val confirmationMatches = confirmationName.trim() == channelName.trim()
    SlockModalLayer(
        title = activeDanger?.title ?: "EDIT CHANNEL",
        subtitle = if (activeDanger == null) "#$channelName" else "Confirm the channel action",
        onDismiss = onDismiss
    ) {
        if (activeDanger == null) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                nonDangerActions.forEach { action ->
                    SlockActionSheetRow(
                        title = action.title,
                        subtitle = action.subtitle,
                        icon = action.icon,
                        destructive = false,
                        onClick = { onOpenAction(action.id) }
                    )
                }
                ThreadSettingsTextField(
                    label = "Name",
                    value = name,
                    placeholder = "channel-name",
                    onValueChange = { name = it },
                    required = true
                )
                ThreadSettingsTextField(
                    label = "Description",
                    value = description,
                    placeholder = "What is this channel for?",
                    onValueChange = { description = it },
                    required = false
                )
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dangerActions.forEach { (danger, action) ->
                        SlockActionSheetRow(
                            title = action.title,
                            subtitle = action.subtitle,
                            icon = action.icon.ifBlank { danger.icon },
                            destructive = true,
                            onClick = { dangerAction = danger }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThreadModalActionButton(label = "Cancel", icon = "x", onClick = onDismiss)
                    Spacer(modifier = Modifier.weight(1f))
                    ThreadModalActionButton(
                        label = "Save Changes",
                        icon = "check",
                        color = SlockColor.AccentPink,
                        onClick = onDismiss
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (activeDanger) {
                    ThreadSettingsDangerAction.Leave -> {
                        Text(
                            text = "Leave #$channelName? You will be removed from this channel, but the channel itself will remain.",
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = "You can rejoin later if your permissions still allow it.",
                            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextMutedGray
                        )
                    }
                    ThreadSettingsDangerAction.Archive,
                    ThreadSettingsDangerAction.Delete -> {
                        Text(
                            text =
                                if (activeDanger == ThreadSettingsDangerAction.Archive) {
                                    "Archive #$channelName? Members keep read access and history, but writes will be frozen and the channel will be hidden from sidebars."
                                } else {
                                    "Delete #$channelName? This permanently removes the channel and its messages."
                                },
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = "Type $channelName to confirm.",
                            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold),
                            color = SlockColor.TextMutedGray
                        )
                        ThreadSettingsTextField(
                            label = "Channel name",
                            value = confirmationName,
                            placeholder = channelName,
                            onValueChange = { confirmationName = it },
                            required = true,
                            isError = confirmationName.isNotBlank() && !confirmationMatches
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThreadModalActionButton(label = "Back", icon = "arrow-left", onClick = { dangerAction = null })
                    Spacer(modifier = Modifier.weight(1f))
                    val enabled = activeDanger == ThreadSettingsDangerAction.Leave || confirmationMatches
                    ThreadModalActionButton(
                        label = activeDanger.buttonLabel,
                        icon = activeDanger.icon,
                        color = if (enabled) activeDanger.color else SlockColor.SurfaceCream,
                        onClick = {
                            if (enabled) {
                                onOpenAction(activeDanger.id)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadSettingsTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    required: Boolean,
    isError: Boolean = required && value.isBlank()
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = SlockType.ButtonBold, color = SlockColor.TextBlack)
            if (required) {
                Text(text = "*", style = SlockType.ButtonBold, color = SlockColor.AccentRed)
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth()
                .border(SlockStroke.Default, if (isError) SlockColor.AccentRed else SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            TextField(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                value = value,
                placeholder = placeholder,
                autoFocus = false,
                onValueChange = onValueChange,
                textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                placeholderColor = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        if (isError) {
            Text(
                text = if (value.isBlank()) "$label is required." else "Channel name does not match.",
                style = SlockType.Caption.copy(color = SlockColor.AccentRed, fontWeight = FontWeight.Bold),
                color = SlockColor.AccentRed
            )
        }
    }
}

@Composable
private fun ThreadModalActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    color: Color = SlockColor.SurfaceWhite
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 38.dp)
            .widthIn(min = 78.dp)
            .background(color)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SlockLucideIcon(icon, modifier = Modifier.size(16.dp))
            Text(text = label, style = SlockType.ButtonBold, color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
