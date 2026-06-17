package ai.slock.compose

import ai.slock.compose.components.SlockAnchoredMetaChip
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.base.Utils
import ai.slock.compose.base.bridgeModule
import ai.slock.compose.data.appendSharedThreadSentMessageFromPage
import ai.slock.compose.data.parseSharedThreadPickedAttachmentFiles
import ai.slock.compose.data.ComposeLocalWriteThrough
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.NetworkSlockTasksApi
import ai.slock.compose.data.NetworkSlockThreadApi
import ai.slock.compose.data.RealChannelRepository
import ai.slock.compose.data.RealConversationRepository
import ai.slock.compose.data.RealMemberRepository
import ai.slock.compose.data.RealSavedRepository
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.ThreadApiResult
import ai.slock.compose.data.SlockAgentsApiClient
import ai.slock.compose.data.SlockAgentsNetworkApi
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.data.SlockIntegrationsActionsPushApiClient
import ai.slock.compose.data.SlockIntegrationsActionsPushNetworkApi
import ai.slock.compose.data.ThreadActionCard
import ai.slock.compose.data.SharedTasksDataSource
import ai.slock.compose.data.SharedTasksRouteIntent
import ai.slock.compose.data.SharedTasksScope
import ai.slock.compose.data.SharedTasksViewModel
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.TaskSnapshot
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.data.isAuthSessionBlocker
import ai.slock.compose.data.isAuthSessionErrorCode
import ai.slock.compose.data.actionCardFromMessageMetadata
import ai.slock.compose.data.isSupportedCommitAction
import ai.slock.compose.data.runCatchingPreservingCancellation
import ai.slock.compose.data.summaryCounts
import ai.slock.compose.network.SlockNetworkModule
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.markdown.SlockMarkdownBody
import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.markdown.ThreadMarkdownTagKind
import ai.slock.compose.markdown.threadMarkdownTagDisplayLabel
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import ai.slock.compose.model.formatSlockTimestampLabel
import ai.slock.compose.model.SlockTimeFormat24Hour
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.ServerId
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
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.gestures.detectTapGestures
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
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.extension.bouncesEnable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.Path
import com.tencent.kuikly.compose.ui.graphics.drawscope.Fill
import com.tencent.kuikly.compose.ui.graphics.drawscope.Stroke
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.layout.ContentScale
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.manager.PagerManager
import com.tencent.kuikly.core.module.PerformanceModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal const val THREAD_PAGE = "compose_thread"
private const val THREAD_MESSAGE_VISUAL_PAGE = "compose_thread_message_visual"
private const val THREAD_MESSAGE_ROW_VISUAL_PAGE = "compose_thread_message_row_visual"
private const val CHANNEL_MEMBERS_PANEL_VISUAL_PAGE = "compose_channel_members_panel_visual"
private const val CHANNEL_SETTINGS_PANEL_VISUAL_PAGE = "compose_channel_settings_panel_visual"
private const val CHANNEL_ADD_MEMBER_PANEL_VISUAL_PAGE = "compose_channel_add_member_panel_visual"
private const val THREAD_PAGE_DATA_EVENT = "slockPageData"
private const val ThreadMessagesMaxBeyondBoundsItemCount = 8
internal const val ThreadReplyPageTitle = "Threads"
private val threadPageJson = Json { ignoreUnknownKeys = true }
private val threadHeaderUuidRegex =
    Regex("^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$")
private val threadHeaderShortIdRegex = Regex("^[A-Fa-f0-9]{8}$")

internal fun threadChannelHeaderSubtitle(channelName: String?): String? {
    val raw = channelName?.trim().orEmpty()
    if (raw.isBlank() || raw.startsWith("Origin:", ignoreCase = true)) return null
    val name = raw.removePrefix("#").substringBefore(":").trim()
    if (name.isBlank()) return null
    if (name.equals(ThreadReplyPageTitle, ignoreCase = true)) return null
    if (name.equals("Thread", ignoreCase = true) || name.equals("Channel", ignoreCase = true)) return null
    if (threadHeaderUuidRegex.matches(name) || threadHeaderShortIdRegex.matches(name)) return null
    return "#$name"
}

internal fun existingThreadChannelHeaderSubtitle(subtitle: String?): String? =
    subtitle?.trim()?.takeIf { it.startsWith("#") }?.let(::threadChannelHeaderSubtitle)

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

@Page(THREAD_MESSAGE_ROW_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadMessageRowVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}

@Page(CHANNEL_MEMBERS_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelMembersPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelMembersPanelVisualScreen() }
    }
}

@Page(CHANNEL_SETTINGS_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelSettingsPanelVisualScreen() }
    }
}

@Page(CHANNEL_ADD_MEMBER_PANEL_VISUAL_PAGE, supportInLocal = true)
internal class ComposeChannelAddMemberPanelVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelAddMemberPanelVisualScreen() }
    }
}

@Serializable
internal data class KuiklyThreadScreenPayload(
    val instanceId: String = "",
    val title: String = "Thread",
    val subtitle: String? = null,
    val status: String = "loading",
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val sourceThreadId: String? = null,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val selectedTab: String = "chat",
    val navigationLabel: String = "Back",
    val showModeTabs: Boolean = true,
    val showReplyActions: Boolean = true,
    val alignOwnMessagesRight: Boolean = false,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
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
    val composer: KuiklyThreadComposerPayload = KuiklyThreadComposerPayload(),
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
        subtitle =
            if (showModeTabs) {
                subtitle
            } else {
                existingThreadChannelHeaderSubtitle(subtitle)
            },
        showOpenInChannelAction = showOpenInChannelAction || originParentMessageId?.isNotBlank() == true,
        showSettingsAction = showSettingsAction && showModeTabs,
        showMembersAction = showMembersAction && showModeTabs
    )

internal fun KuiklyThreadScreenPayload.replyThreadHeaderSubtitle(): String? =
    threadChannelHeaderSubtitle(title)

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
    val threadChannelId: String? = null,
    val replyCount: Int = 0,
    val unreadReplyCount: Int = 0,
    val firstUnreadReplyMessageId: String? = null,
    val isSaved: Boolean = false,
    val taskId: String? = null,
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskAssigneeId: String? = null,
    val taskAssigneeName: String? = null,
    val reactions: List<KuiklyThreadReactionItem> = emptyList(),
    val attachments: List<KuiklyThreadAttachmentItem> = emptyList()
)

@Serializable
internal data class KuiklyThreadReactionItem(
    val emoji: String,
    val count: Int = 0,
    val selected: Boolean = false
)

@Serializable
internal data class KuiklyThreadAttachmentItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val sizeLabel: String = "",
    val thumbnailUrl: String? = null
)

private data class ThreadImagePreviewUrl(
    val url: String,
    val expiresAt: String?
)

private data class ThreadImagePreviewState(
    val images: List<KuiklyThreadAttachmentItem>,
    val currentIndex: Int,
    val loadingAttachmentIds: Set<String> = emptySet(),
    val failedAttachmentIds: Set<String> = emptySet()
) {
    val current: KuiklyThreadAttachmentItem? get() = images.getOrNull(currentIndex)
}

private data class ThreadCreateAgentActionDialogState(
    val messageId: String,
    val card: ThreadActionCard,
    val submitting: Boolean = false,
    val errorCode: String? = null
)

private data class ThreadImageAttachmentGroup(
    val images: List<KuiklyThreadAttachmentItem>,
    val currentIndex: Int
)

private data class ThreadAttachmentOpenResult(
    val status: String,
    val message: String = ""
)

private fun <T> DataResult<T>.threadActionErrorCode(fallback: String): String =
    when (this) {
        is DataResult.Content -> fallback
        is DataResult.Empty -> reason.name.lowercase()
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }

private fun KuiklyThreadScreenPayload.taskStatusLabel(taskId: String): String? =
    messages.firstOrNull { it.taskId == taskId }?.taskStatus
        ?: originMessage?.takeIf { it.taskId == taskId }?.taskStatus
        ?: tasks.firstOrNull { it.id == taskId }?.status

internal data class SlockThreadWebLinkTarget(
    val channelId: String,
    val messageId: String? = null
)

@Serializable
internal data class KuiklyThreadTaskItem(
    val id: String,
    val title: String,
    val status: String,
    val taskNumber: Int? = null,
    val messageId: String? = null,
    val assigneeId: String? = null,
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

internal fun KuiklyThreadScreenPayload.resolveMarkdownTagDisplayLabel(tag: ThreadMarkdownTag): String =
    when (tag.kind) {
        ThreadMarkdownTagKind.UserMention ->
            findMarkdownMentionMember(tag)
                ?.displayName
                ?.trim()
                ?.removePrefix("@")
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }
                ?: threadMarkdownTagDisplayLabel(tag.label)
        else -> threadMarkdownTagDisplayLabel(tag.label)
    }

internal fun KuiklyThreadScreenPayload.resolveTaskAssigneeDisplayName(
    value: String?,
    id: String? = null
): String? {
    val normalizedId = id?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val normalizedValue = value?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val member =
        listOfNotNull(normalizedId, normalizedValue)
            .firstNotNullOfOrNull { candidate ->
                members.firstOrNull { member ->
                    member.id.equals(candidate, ignoreCase = true) ||
                        member.handle.removePrefix("@").equals(candidate, ignoreCase = true) ||
                        member.displayName.equals(candidate, ignoreCase = true)
                }
            }
    return member?.displayName?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        ?: normalizedValue?.takeIf { it.isDisplaySafeTaskAssigneeName(normalizedId) }
}

internal fun TaskSummary.threadLinkedTaskAssigneeDisplayName(): String? {
    val assignee = assignee ?: return null
    val assigneeId = assignee.id.value.trim().removePrefix("@").takeIf { it.isNotBlank() }
    val displayName = assignee.displayName.trim().removePrefix("@").takeIf { it.isNotBlank() } ?: return null
    if (assigneeId != null && displayName.equals(assigneeId, ignoreCase = true)) return null
    return displayName.takeIf { it.isDisplaySafeTaskAssigneeName(assigneeId) }
}

internal fun KuiklyThreadMessageItem.withLinkedTask(linkedTask: TaskSummary?): KuiklyThreadMessageItem {
    linkedTask ?: return this
    return copy(
        taskId = linkedTask.id.value,
        taskNumber = linkedTask.taskNumber ?: taskNumber,
        taskStatus = linkedTask.status.name,
        taskAssigneeId = linkedTask.assignee?.id?.value,
        taskAssigneeName = linkedTask.threadLinkedTaskAssigneeDisplayName()
    )
}

internal fun TasksUiState.threadLinkedTasksByMessageId(): Map<String, TaskSummary> {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return emptyMap()
    return content.tasks.mapNotNull { task ->
        val messageId = task.messageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        messageId to task
    }.toMap()
}

internal fun KuiklyThreadScreenPayload.supportsChannelTasks(): Boolean =
    composer.showAsTaskToggle || showTaskOpenActions || showTaskStatusActions

internal fun shouldStartThreadTasksJoin(
    payload: KuiklyThreadScreenPayload,
    startedThreadId: String?,
    taskChannelId: ChannelId
): Boolean =
    payload.status == "content" &&
        payload.supportsChannelTasks() &&
        startedThreadId != taskChannelId.value &&
        !payload.sourceThreadId.isNullOrBlank()

private fun TasksUiState.threadTasksJoinDiagnosticsResult(): Pair<String, Int>? =
    when (val state = loadState) {
        ScreenLoadState.Loading -> null
        is ScreenLoadState.Content -> "content" to state.data.tasks.size
        is ScreenLoadState.Empty -> "empty:${state.reason.name}" to 0
        is ScreenLoadState.Error -> "error:${state.error.code}" to 0
        is ScreenLoadState.NoPermission -> "no_permission:${state.blocker.code}" to 0
    }

private fun KuiklyThreadScreenPayload.threadRenderDiagnosticsSource(): String =
    when {
        status == "loading" -> "loading"
        status == "content" && messages.isNotEmpty() -> "content"
        status == "content" -> "empty_content"
        errorCode != null -> "error"
        else -> status.ifBlank { "unknown" }
    }

private fun requestKuiklyThreadPerformanceSnapshot(
    bridge: ai.slock.compose.base.BridgeModule,
    threadId: String,
    reason: String
) {
    runCatching {
        PagerManager.getCurrentPager()
            .acquireModule<PerformanceModule>(PerformanceModule.MODULE_NAME)
            .getPerformanceData { data ->
                val summary = bridge.reportKuiklyPerformanceSnapshot(
                    routeName = "kuikly_thread",
                    reason = reason,
                    threadId = threadId,
                    data = data
                )
                bridge.performanceMarker(
                    phase = "thread_first_frame",
                    routeName = "kuikly_thread",
                    event = "instant",
                    threadId = threadId,
                    durationMs = data?.pageLoadTime?.firstPaintCost?.toLong(),
                    metadata =
                        mapOf(
                            "reason" to reason,
                            "status" to "content"
                        )
                )
                bridge.log("kuikly_perf_snapshot $summary")
            }
    }.onFailure { error ->
        val summary = bridge.reportKuiklyPerformanceSnapshot(
            routeName = "kuikly_thread",
            reason = reason,
            threadId = threadId,
            data = null,
            statusOverride = "failed"
        )
        bridge.performanceMarker(
            phase = "thread_first_frame",
            routeName = "kuikly_thread",
            event = "instant",
            threadId = threadId,
            metadata =
                mapOf(
                    "reason" to reason,
                    "status" to "failed"
                )
        )
        bridge.log("kuikly_perf_snapshot $summary error=${error.message.orEmpty()}")
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

private fun KuiklyThreadScreenPayload.findTaskParentMessage(taskId: String): KuiklyThreadMessageItem? =
    messages.firstOrNull { message ->
        message.id == taskId || message.taskId == taskId
    } ?: originMessage?.takeIf { message ->
        message.id == taskId || message.taskId == taskId
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

internal data class SharedThreadRealtimeEventSignal(
    val sequence: Long,
    val event: RealtimeEvent
)

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
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    val authStore = sharedStores.authStore
    val threadLocalStore =
        remember(sharedStores, authStore) {
            sharedStores.createThreadLocalDataStore(
                currentServerId = {
                    authStore.readSession()
                        ?.currentServerId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::ServerId)
                }
            )
        }
    val localWriteThrough =
        remember(threadLocalStore) {
            ComposeLocalWriteThrough(
                threadLocalStore,
                logger = { message -> bridge.log("shared_thread_cache $message") }
            )
        }
    val networkClient =
        remember {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME),
                warningLogger = { message -> bridge.log("kuikly_network $message") }
            )
        }
    val agentsApiClient = remember(networkClient) {
        SlockAgentsApiClient(SlockAgentsNetworkApi(networkClient))
    }
    val computersApiClient = remember(networkClient) {
        SlockComputersApiClient(SlockComputersNetworkApi(networkClient))
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        ai.slock.compose.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("thread_create_agent $message") }
        )
    }
    val dataSource =
        remember {
            SharedThreadDataSource(
                networkApi = NetworkSlockThreadApi(networkClient),
                sessionStore = authStore,
                localWriteThrough = localWriteThrough,
                memberRepository =
                    RealMemberRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        logger = { message -> bridge.log("thread_member_repository $message") }
                    ),
                conversationRepository =
                    RealConversationRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_conversation_repository $message") }
                    ),
                channelRepository =
                    RealChannelRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_channel_repository $message") }
                    ),
                agentsApiClient = SlockAgentsApiClient(SlockAgentsNetworkApi(networkClient)),
                actionCardsApiClient = SlockIntegrationsActionsPushApiClient(
                    SlockIntegrationsActionsPushNetworkApi(networkClient)
                ),
                savedStatusRepository =
                    RealSavedRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        warningLogger = { message -> bridge.log("thread_saved_repository $message") }
                    ),
                createAgentCoordinator = createAgentCoordinator
            )
        }
    val tasksDataSource =
        remember {
            SharedTasksDataSource(
                api = NetworkSlockTasksApi(networkClient)
            )
        }
    SharedThreadRouteContent(
        initialPayload = payload,
        bridge = bridge,
        dataSource = dataSource,
        authStore = authStore,
        tasksDataSource = tasksDataSource,
        createAgentCoordinator = createAgentCoordinator,
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
    Column(modifier = Modifier.width(342.dp).background(SlockColor.SurfaceWhite).padding(SlockSpacing.Md)) {
        if (message != null) {
            ThreadMessageCard(
                message = message,
                highlightedMentions = emptySet(),
                alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                showReplyAction = payload.showReplyActions,
                resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                    value = message.taskAssigneeName,
                    id = message.taskAssigneeId
                ),
                resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                taskOpenEnabled = payload.showTaskOpenActions,
                taskStatusActionsEnabled = payload.showTaskStatusActions,
                timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                onShowActions = {},
                onOpenMember = {},
                onOpenThread = {},
                onOpenTask = {},
                onOpenTaskStatusMenu = {},
                onToggleReaction = {},
                onToggleSaved = {},
                onOpenAttachment = {},
                onOpenMarkdownTag = {},
                onOpenMarkdownUrl = {},
                outerPadding = 0.dp
            )
        }
    }
}

@Composable
private fun ChannelMembersPanelVisualScreen() {
    val members = remember { channelPanelVisualMembers() }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadMembersModal(
            members = members,
            membersCount = members.size,
            canAddMembers = true,
            onDismiss = {},
            onAddMember = {},
            onManageMember = {}
        )
    }
}

@Composable
private fun ChannelSettingsPanelVisualScreen() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadSettingsModal(
            channelName = "design",
            subtitle = "Product and UI decisions",
            actions = channelPanelVisualSettingsActions(),
            onDismiss = {},
            onOpenAction = {}
        )
    }
}

@Composable
private fun ChannelAddMemberPanelVisualScreen() {
    val members = remember { channelPanelVisualMembers() }
    val candidates = remember { channelPanelVisualCandidates() }
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        ThreadAddMemberModal(
            members = members,
            candidates = candidates,
            canAddMembers = true,
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            initialSelectedId = "user_designer",
            onDismiss = {},
            onSubmit = {}
        )
    }
}

private fun channelPanelVisualMembers(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent_android",
            displayName = "Android Developer",
            handle = "Android-Developer",
            roleLabel = "Online",
            avatarLabel = "AD",
            kind = "agent"
        ),
        KuiklyThreadMemberItem(
            id = "user_artin",
            displayName = "Artin",
            handle = "artin",
            roleLabel = "Project owner",
            avatarLabel = "A",
            kind = "human"
        )
    )

private fun channelPanelVisualCandidates(): List<KuiklyThreadMemberItem> =
    listOf(
        KuiklyThreadMemberItem(
            id = "agent_reviewer",
            displayName = "Review Agent",
            handle = "Review-Agent",
            roleLabel = "online",
            avatarLabel = "RA",
            kind = "agent"
        ),
        KuiklyThreadMemberItem(
            id = "user_designer",
            displayName = "Product UX Designer",
            handle = "Product-UX-Designer",
            roleLabel = "Admin",
            avatarLabel = "PU",
            kind = "human"
        )
    )

private fun channelPanelVisualSettingsActions(): List<KuiklyThreadSettingsAction> =
    listOf(
        KuiklyThreadSettingsAction(
            id = "visibility",
            title = "Make Private",
            subtitle = "Restrict this channel to selected members",
            icon = "lock",
            destructive = true
        ),
        KuiklyThreadSettingsAction(
            id = "archive",
            title = "Archive Channel",
            subtitle = "Freeze writes and hide from sidebars",
            icon = "archive",
            destructive = true
        ),
        KuiklyThreadSettingsAction(
            id = "delete",
            title = "Delete Channel",
            subtitle = "Permanently remove this channel",
            icon = "trash",
            destructive = true
        )
    )

@Composable
private fun ThreadMessageVisualCard(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String> = emptySet(),
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    timestampLabel: String = message.timestampLabel,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = outerPadding, vertical = SlockSpacing.Sm)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .padding(horizontal = SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThreadMessageStaticAvatar(
                    avatarLabel = message.avatarLabel,
                    avatarWireValue = message.resolvedThreadAvatarWireValue(),
                    showStatus = message.showsAgentStatusIndicator()
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.threadMessageContentWidth(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                    ) {
                        Text(
                            text = message.authorName,
                            style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                        Text(
                            text = timestampLabel,
                            style =
                                SlockType.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = SlockColor.TextBlack.copy(alpha = 0.4f)
                                ),
                            color = SlockColor.TextBlack.copy(alpha = 0.4f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (message.body.isNotBlank()) {
                        Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                            SlockMarkdownBody(
                                body = message.body,
                                textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                highlightedMentions = highlightedMentions,
                                resolveTagLabel = resolveMarkdownTagLabel,
                                onOpenTag = onOpenMarkdownTag,
                                onOpenUrl = onOpenMarkdownUrl
                            )
                        }
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                        ThreadMessageAttachments(
                            attachments = message.attachments,
                            alignEnd = false,
                            onOpenAttachment = {}
                        )
                    }
                    Box(modifier = Modifier.threadMessageContentWidth(1f)) {
                        ThreadMessageFooterMeta(
                            message = message,
                            showReplyAction = true,
                            taskOpenEnabled = false,
                            taskStatusActionsEnabled = false,
                            resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                            alignEnd = false,
                            onOpenThread = {},
                            onOpenTask = {},
                            onShowActions = {},
                            onOpenTaskStatusMenu = {}
                        )
                    }
                }
            }
        }
}
}

@Composable
internal fun SharedThreadRouteContent(
    initialPayload: KuiklyThreadScreenPayload,
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    authStore: SlockAuthSessionStore,
    tasksDataSource: SharedTasksDataSource,
    createAgentCoordinator: ai.slock.compose.data.SharedCreateAgentCoordinator,
    onBack: () -> Unit,
    onOpenThreadPayload: (KuiklyThreadScreenPayload) -> Unit = {},
    onReplaceThreadPayload: (KuiklyThreadScreenPayload) -> Unit = onOpenThreadPayload,
    onOpenParentThread: () -> Boolean = { false },
    onAuthRequired: (String) -> Unit = {},
    realtimeEventSignal: SharedThreadRealtimeEventSignal? = null
) {
    PerformanceSceneEffect(bridge, "kuikly_thread")
    val threadViewModel: SharedThreadViewModel =
        viewModel(key = "shared-thread-${initialPayload.sourceThreadId ?: initialPayload.instanceId}") {
            SharedThreadViewModel(dataSource, diagnosticsLogger = { bridge.log(it) })
        }
    val threadState by threadViewModel.state.collectAsState()
    val payload = threadState.payload
    val taskChannelId =
        initialPayload.sourceThreadId
            ?.takeIf { it.isNotBlank() }
            ?.let(::ChannelId)
            ?: ChannelId(initialPayload.instanceId)
    val tasksViewModel: SharedTasksViewModel =
        viewModel(key = "shared-thread-tasks-${taskChannelId.value}") {
            SharedTasksViewModel(
                authStore = authStore,
                dataSource = tasksDataSource,
                scope = SharedTasksScope.Channel(taskChannelId)
            )
        }
    val tasksState by tasksViewModel.state.collectAsState()
    var composerDraftText by remember(initialPayload.instanceId) { mutableStateOf("") }
    var composerDraftAsTask by remember(initialPayload.instanceId) { mutableStateOf(false) }
    var composerSyncNonce by remember(initialPayload.instanceId) { mutableStateOf(0) }
    var composerSending by remember(initialPayload.instanceId) { mutableStateOf(false) }
    var composerFailureLabel by remember(initialPayload.instanceId) { mutableStateOf<String?>(null) }
    var removedAttachmentIds by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingReactionKeys by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingActionCardIds by remember(initialPayload.instanceId) { mutableStateOf<Set<String>>(emptySet()) }
    var createAgentDialogState by remember(initialPayload.instanceId) { mutableStateOf<ThreadCreateAgentActionDialogState?>(null) }
    var attachmentOpenStates by remember(initialPayload.instanceId) {
        mutableStateOf<Map<String, ThreadAttachmentOpenUiState>>(emptyMap())
    }
    var uploadedAttachments by remember(initialPayload.instanceId) {
        mutableStateOf<List<SharedThreadComposerAttachment>>(emptyList())
    }
    var firstRenderLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var firstContentLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var performanceSnapshotLoggedThreadId by remember { mutableStateOf<String?>(null) }
    var startedTasksJoinThreadId by remember { mutableStateOf<String?>(null) }
    var lastTasksJoinResultKey by remember { mutableStateOf<String?>(null) }
    var tasksJoinStartedAt by remember { mutableStateOf<TimeMark?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun routeAuthRequired(code: String? = null): Boolean {
        if (!code.isAuthSessionErrorCode()) return false
        onAuthRequired(code ?: "auth_required")
        return true
    }

    fun routeAuthRequired(result: DataResult.NoPermission): Boolean =
        if (result.blocker.isAuthSessionBlocker()) {
            onAuthRequired(result.blocker.code)
            true
        } else {
            false
        }

    LaunchedEffect(initialPayload) {
        val composer = initialPayload.resolveThreadComposer()
        bridge.performanceMarker(
            phase = "thread_open_start",
            routeName = "kuikly_thread",
            event = "instant",
            threadId = initialPayload.sourceThreadId ?: initialPayload.instanceId,
            metadata =
                mapOf(
                    "status" to initialPayload.status.ifBlank { "unknown" },
                    "source" to initialPayload.threadRenderDiagnosticsSource()
                )
        )
        composerDraftText = composer.seedText
        composerDraftAsTask = composer.seedAsTask
        composerSyncNonce = composer.syncNonce
        composerSending = false
        composerFailureLabel = null
        removedAttachmentIds = emptySet()
        pendingActionCardIds = emptySet()
        createAgentDialogState = null
        attachmentOpenStates = emptyMap()
        uploadedAttachments = emptyList()
        threadViewModel.dispatch(SharedThreadIntent.Start(initialPayload))
    }
    LaunchedEffect(realtimeEventSignal?.sequence, initialPayload.instanceId) {
        val signal = realtimeEventSignal ?: return@LaunchedEffect
        val activeReplyParentMessageId =
            initialPayload.originParentMessageId
                ?.takeIf { it.isNotBlank() }
                ?.let(::MessageId)
        threadViewModel.dispatch(
            SharedThreadIntent.ApplyRealtimeEvent(
                event = signal.event,
                activeReplyParentMessageId = activeReplyParentMessageId
            )
        )
    }
    LaunchedEffect(payload.sourceThreadId, payload.tasks) {
        if (payload.tasks.isNotEmpty()) {
            val taskSummaries = payload.tasks.map { it.toTaskSummary() }
            tasksViewModel.dispatch(
                SharedTasksRouteIntent.SeedContent(
                    TaskSnapshot(
                        tasks = taskSummaries,
                        summaryCounts = taskSummaries.summaryCounts()
                    )
                )
            )
        }
    }
    val threadRouteKey =
        payload.sourceThreadId
            ?: initialPayload.sourceThreadId
            ?: payload.instanceId.ifBlank { initialPayload.instanceId }
    LaunchedEffect(threadRouteKey, payload.status, payload.messages.size) {
        if (firstRenderLoggedThreadId != threadRouteKey) {
            firstRenderLoggedThreadId = threadRouteKey
            bridge.performanceMarker(
                phase = "thread_first_render",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadRouteKey,
                metadata =
                    mapOf(
                        "status" to payload.status.ifBlank { "unknown" },
                        "messages" to payload.messages.size.toString(),
                        "source" to payload.threadRenderDiagnosticsSource()
                    )
            )
            bridge.log(
                "thread_first_render threadId=${threadRouteKey.orEmpty()} status=${payload.status} " +
                    "messages=${payload.messages.size} source=${payload.threadRenderDiagnosticsSource()}"
            )
        }
        if (payload.status == "content" && firstContentLoggedThreadId != threadRouteKey) {
            firstContentLoggedThreadId = threadRouteKey
            bridge.performanceMarker(
                phase = "thread_payload_ready",
                routeName = "kuikly_thread",
                event = "instant",
                threadId = threadRouteKey,
                metadata =
                    mapOf(
                        "messages" to payload.messages.size.toString(),
                        "tasks" to payload.tasks.size.toString()
                    )
            )
            bridge.log(
                "thread_content_gate threadId=${threadRouteKey.orEmpty()} messages=${payload.messages.size}"
            )
            if (performanceSnapshotLoggedThreadId != threadRouteKey) {
                performanceSnapshotLoggedThreadId = threadRouteKey
                delay(ThreadFirstContentTaskJoinDelayMs)
                requestKuiklyThreadPerformanceSnapshot(
                    bridge = bridge,
                    threadId = threadRouteKey.orEmpty(),
                    reason = "thread_first_content"
                )
            }
        }
    }
    LaunchedEffect(threadRouteKey, taskChannelId.value, payload.status) {
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        delay(ThreadFirstContentTaskJoinDelayMs)
        if (!shouldStartThreadTasksJoin(payload, startedTasksJoinThreadId, taskChannelId)) {
            return@LaunchedEffect
        }
        startedTasksJoinThreadId = taskChannelId.value
        lastTasksJoinResultKey = null
        tasksJoinStartedAt = TimeSource.Monotonic.markNow()
        bridge.log(
            "thread_tasks_join_start threadId=${threadRouteKey.orEmpty()} reason=after_first_content"
        )
        tasksViewModel.dispatch(SharedTasksRouteIntent.Start)
    }
    LaunchedEffect(startedTasksJoinThreadId, taskChannelId.value, tasksState.loadState) {
        if (startedTasksJoinThreadId != taskChannelId.value) {
            return@LaunchedEffect
        }
        val (result, taskCount) = tasksState.threadTasksJoinDiagnosticsResult() ?: return@LaunchedEffect
        val resultKey = "${taskChannelId.value}:$result:$taskCount"
        if (lastTasksJoinResultKey == resultKey) {
            return@LaunchedEffect
        }
        lastTasksJoinResultKey = resultKey
        val durationMs = tasksJoinStartedAt?.elapsedNow()?.inWholeMilliseconds ?: -1L
        bridge.log(
            "thread_tasks_join_result threadId=${threadRouteKey.orEmpty()} result=$result " +
                "tasks=$taskCount durationMs=$durationMs"
        )
    }

    val routeFilesPayload = remember(payload.filesPayload, payload.instanceId) {
        parseThreadFilesPayload(payload.filesPayload, payload.instanceId)
    }
    var filesPayload by remember(payload.instanceId) { mutableStateOf(routeFilesPayload) }
    var filesRequestInFlight by remember(payload.instanceId) { mutableStateOf(false) }
    var imagePreviewState by remember(payload.instanceId) { mutableStateOf<ThreadImagePreviewState?>(null) }
    var imagePreviewUrlCache by remember(payload.instanceId) { mutableStateOf<Map<String, ThreadImagePreviewUrl>>(emptyMap()) }
    LaunchedEffect(payload.instanceId, payload.filesPayload) {
        val rawFilesPayload = payload.filesPayload.trim()
        if (rawFilesPayload.isNotBlank() && rawFilesPayload != "{}") {
            filesPayload = routeFilesPayload
        }
    }
    val renderedComposerState =
        remember(
            payload.composer,
            payload.instanceId,
            payload.title,
            payload.surfaceKind,
            payload.sourceThreadId,
            payload.streamChannelId,
            payload.parentChannelId,
            payload.parentMessageId,
            payload.originParentMessageId,
            payload.canSend
        ) {
            payload.resolveThreadComposer()
        }
    val composerState =
        remember(
            renderedComposerState,
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
                (renderedComposerState.pendingAttachments.map { attachment ->
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
            renderedComposerState.copy(
                instanceId = renderedComposerState.instanceId.ifBlank { payload.instanceId },
                seedText = composerDraftText,
                seedAsTask = composerDraftAsTask,
                syncNonce = composerSyncNonce,
                pendingAttachments =
                    pendingAttachments.map { attachment ->
                        KuiklyPendingAttachmentItem(
                            id = attachment.id,
                            fileName = attachment.fileName,
                            sizeLabel = attachment.sizeLabel,
                            mimeType = attachment.mimeType,
                            status = attachment.status
                        )
                    },
                composerMode =
                    when {
                        composerSending -> "sending"
                        composerFailureLabel != null -> "failed"
                        renderedComposerState.composerMode == "failed" -> "failed"
                        else -> renderedComposerState.composerMode
                    },
                statusLabel =
                    when {
                        composerSending -> "Sending..."
                        composerFailureLabel != null -> composerFailureLabel
                        renderedComposerState.composerMode == "failed" -> renderedComposerState.statusLabel
                        else -> renderedComposerState.statusLabel
                    },
                enabled = payload.canSend && !composerSending
            )
        }
    val composerSurface =
        remember(composerState, payload.sourceThreadId, payload.parentChannelId) {
            composerState.toThreadComposerSurface(
                channelId = payload.sourceThreadId.orEmpty(),
                parentChannelId = payload.parentChannelId
            )
        }
    val renderedPayload = remember(payload) {
        payload.withSharedShellEventCapabilities()
    }
    LaunchedEffect(
        renderedPayload.status,
        renderedPayload.errorCode,
        renderedPayload.olderMessagesStatus,
        renderedPayload.newerMessagesStatus
    ) {
        if (
            renderedPayload.status == "no_permission" ||
            renderedPayload.status == "error" ||
            renderedPayload.olderMessagesStatus == "error" ||
            renderedPayload.newerMessagesStatus == "error"
        ) {
            routeAuthRequired(renderedPayload.errorCode)
        }
    }
    LaunchedEffect(filesPayload.status, filesPayload.errorCode) {
        if (filesPayload.status == "no_permission" || filesPayload.status == "error") {
            routeAuthRequired(filesPayload.errorCode)
        }
    }
    fun loadThreadFiles(reason: String, cursor: String? = null) {
        if (filesRequestInFlight || (cursor != null && filesPayload.loadingMore)) return
        val requestBasePayload = renderedPayload
        filesRequestInFlight = true
        filesPayload =
            if (cursor != null) {
                filesPayload.copy(loadingMore = true, errorCode = null)
            } else {
                filesPayload.copy(status = "loading", loadingMore = false, errorCode = null)
            }
        coroutineScope.launch {
            val result =
                runCatchingPreservingCancellation {
                    dataSource.fetchFilesPayload(
                        basePayload = requestBasePayload,
                        cursor = cursor,
                        currentPayload = filesPayload
                    )
                }.getOrElse {
                    DataResult.Error(ScreenError(code = "thread_files_network", retryable = true))
                }
            if (requestBasePayload.sourceThreadId == renderedPayload.sourceThreadId) {
                filesPayload =
                    when (result) {
                        is DataResult.Content -> result.data
                        is DataResult.Empty ->
                            filesPayload.copy(
                                status = if (filesPayload.items.isEmpty()) "empty" else "content",
                                hasMore = false,
                                loadingMore = false,
                                nextCursor = null,
                                errorCode = null
                            )
                        is DataResult.Error ->
                            filesPayload.copy(
                                status = if (filesPayload.items.isEmpty()) "error" else "content",
                                loadingMore = false,
                                errorCode = result.error.code
                            )
                        is DataResult.NoPermission ->
                            filesPayload.copy(
                                status = "no_permission",
                                loadingMore = false,
                                errorCode = result.blocker.requiredCapability ?: result.blocker.code
                            )
                    }
            }
            filesRequestInFlight = false
            bridge.log(
                "thread_files_result reason=$reason threadId=${requestBasePayload.sourceThreadId.orEmpty()} cursor=${cursor.orEmpty()} status=${filesPayload.status} items=${filesPayload.items.size} hasMore=${filesPayload.hasMore} error=${filesPayload.errorCode.orEmpty()}"
            )
        }
    }
    fun requestImagePreviewUrl(attachmentId: String) {
        if (attachmentId in imagePreviewUrlCache) return
        val currentPreview = imagePreviewState ?: return
        if (attachmentId in currentPreview.loadingAttachmentIds) return
        imagePreviewState =
            currentPreview.copy(
                loadingAttachmentIds = currentPreview.loadingAttachmentIds + attachmentId,
                failedAttachmentIds = currentPreview.failedAttachmentIds - attachmentId
            )
        coroutineScope.launch {
            when (val response = dataSource.attachmentUrl(attachmentId)) {
                is ThreadApiResult.Success -> {
                    imagePreviewUrlCache =
                        imagePreviewUrlCache +
                            (attachmentId to ThreadImagePreviewUrl(response.data.url, response.data.expiresAt))
                    imagePreviewState =
                        imagePreviewState?.takeIf { preview -> preview.images.any { it.id == attachmentId } }?.let { preview ->
                            preview.copy(
                                loadingAttachmentIds = preview.loadingAttachmentIds - attachmentId,
                                failedAttachmentIds = preview.failedAttachmentIds - attachmentId
                            )
                        }
                }
                is ThreadApiResult.Failure -> {
                    imagePreviewState =
                        imagePreviewState?.takeIf { preview -> preview.images.any { it.id == attachmentId } }?.let { preview ->
                            preview.copy(
                                loadingAttachmentIds = preview.loadingAttachmentIds - attachmentId,
                                failedAttachmentIds = preview.failedAttachmentIds + attachmentId
                            )
                        }
                    if (imagePreviewState?.current?.id == attachmentId && imagePreviewState?.current?.thumbnailUrl.isNullOrBlank()) {
                        bridge.toast("Unable to load image")
                    }
                }
            }
        }
    }
    fun openImagePreview(group: ThreadImageAttachmentGroup) {
        if (group.images.isEmpty()) return
        val currentIndex = group.currentIndex.coerceIn(0, group.images.lastIndex)
        imagePreviewState = ThreadImagePreviewState(images = group.images, currentIndex = currentIndex)
        requestImagePreviewUrl(group.images[currentIndex].id)
    }
    fun selectImagePreviewIndex(index: Int) {
        val preview = imagePreviewState ?: return
        if (preview.images.isEmpty()) return
        val nextIndex = index.coerceIn(0, preview.images.lastIndex)
        imagePreviewState = preview.copy(currentIndex = nextIndex)
        requestImagePreviewUrl(preview.images[nextIndex].id)
    }
    fun openAttachmentExternally(attachment: KuiklyThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(status = "downloading"))
        coroutineScope.launch {
            val result =
                openSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment
                )
            if (result.status == "opened") {
                attachmentOpenStates = attachmentOpenStates - attachment.id
            } else {
                attachmentOpenStates =
                    attachmentOpenStates +
                        (attachment.id to ThreadAttachmentOpenUiState(status = result.status, message = result.message))
                bridge.toast(result.message.ifBlank { "Unable to open attachment" })
            }
        }
    }
    LaunchedEffect(renderedPayload.sourceThreadId, renderedPayload.selectedTab) {
        if (renderedPayload.selectedTab == KuiklyThreadTab.Files.wireValue && filesPayload.shouldFetchInitialFiles()) {
            loadThreadFiles("initial_tab")
        }
    }
    val threadRenderReplyRows = remember(renderedPayload.messages) {
        renderedPayload.messages.filter { it.replyCount > 0 }
    }
    val threadRenderFirstReply = threadRenderReplyRows.firstOrNull()
    LaunchedEffect(
        renderedPayload.instanceId,
        renderedPayload.sourceThreadId,
        renderedPayload.status,
        renderedPayload.selectedTab,
        renderedPayload.messages.size,
        renderedPayload.tasks.size,
        renderedPayload.showReplyActions,
        threadRenderReplyRows.size,
        threadRenderFirstReply?.id?.take(8),
        threadRenderFirstReply?.replyCount,
        threadRenderFirstReply?.unreadReplyCount
    ) {
        bridge.log(
            "thread_render instanceId=${renderedPayload.instanceId} status=${renderedPayload.status} tab=${renderedPayload.selectedTab} " +
                "messages=${renderedPayload.messages.size} tasks=${renderedPayload.tasks.size} " +
                "showReplyActions=${renderedPayload.showReplyActions} replyRows=${threadRenderReplyRows.size} " +
                "firstReply=${threadRenderFirstReply?.let { "${it.id.take(8)}:${it.replyCount}/${it.unreadReplyCount}" } ?: "none"}"
        )
    }
    LaunchedEffect(renderedPayload.sourceThreadId, renderedPayload.status, renderedPayload.messages.size) {
        if (renderedPayload.status == "content" && renderedPayload.messages.isNotEmpty()) {
            threadViewModel.dispatch(SharedThreadIntent.MarkVisibleReadAll)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ThreadPageContent(
            payload = renderedPayload,
            filesPayload = filesPayload,
            composerSurface = composerSurface,
            composerState = composerState,
            tasksState = tasksState,
            onTasksIntent = tasksViewModel::dispatch,
            highlightedMentions = threadState.highlightedMentions,
            diagnosticsLogger = { bridge.log(it) },
            pendingActionCardIds = pendingActionCardIds,
            attachmentOpenStates = attachmentOpenStates,
            onBack = onBack,
            onThreadEvent = { action, itemId, attachmentId, tab ->
                when {
                    action == "openAttachment" && !attachmentId.isNullOrBlank() -> {
                        val imageGroup =
                            renderedPayload.findThreadImageAttachmentGroup(attachmentId)
                                ?: filesPayload.findThreadImageAttachmentGroup(attachmentId)
                        if (imageGroup != null) {
                            openImagePreview(imageGroup)
                        } else {
                            val attachment =
                                renderedPayload.findThreadAttachment(attachmentId)
                                    ?: filesPayload.findThreadAttachment(attachmentId)
                                    ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                            openAttachmentExternally(attachment)
                        }
                    }
                    action == "downloadAttachment" && !attachmentId.isNullOrBlank() -> {
                        val attachment =
                            renderedPayload.findThreadAttachment(attachmentId)
                                ?: filesPayload.findThreadAttachment(attachmentId)
                                ?: KuiklyThreadAttachmentItem(id = attachmentId, name = "Attachment")
                        openAttachmentExternally(attachment)
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
                    val loadingPayload = renderedPayload.replyThreadLoadingPayload(parentMessage)
                    onOpenThreadPayload(loadingPayload)
                    coroutineScope.launch {
                        when (val result = dataSource.createOrOpenReplyThread(renderedPayload, parentMessage)) {
                            is DataResult.Content -> onReplaceThreadPayload(result.data)
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_reply_open_failed")
                                )
                                bridge.toast("Unable to open replies")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_reply_open_failed")
                                )
                                bridge.toast("Unable to open replies")
                            }
                        }
                    }
                }
                action == "openParentMessage" -> {
                    if (!onOpenParentThread()) {
                        onBack()
                    }
                }
                action == "toggleMessageSaved" && !itemId.isNullOrBlank() -> {
                    val previousSaved =
                        renderedPayload.threadActionMessage(itemId)?.isSaved
                            ?: renderedPayload.originMessage?.takeIf { it.id == itemId }?.isSaved
                            ?: false
                    val nextSaved = !previousSaved
                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, nextSaved))
                    coroutineScope.launch {
                        val result =
                            if (nextSaved) {
                                dataSource.saveMessage(itemId)
                            } else {
                                dataSource.unsaveMessage(itemId)
                            }
                        when (result) {
                            is DataResult.Content ->
                                bridge.toast(if (nextSaved) "Saved" else "Removed from saved")
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                bridge.toast(if (nextSaved) "Unable to save message" else "Unable to remove saved message")
                            }
                            is DataResult.NoPermission -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyMessageSaved(itemId, previousSaved))
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast(if (nextSaved) "Unable to save message" else "Unable to remove saved message")
                            }
                        }
                    }
                }
                action == "commitActionCard" && !itemId.isNullOrBlank() -> {
                    if (itemId in pendingActionCardIds) {
                        return@ThreadPageContent
                    }
                    val actionCard =
                        renderedPayload.messages.firstOrNull { it.id == itemId }?.threadActionCard()
                            ?: renderedPayload.originMessage?.takeIf { it.id == itemId }?.threadActionCard()
                    if (actionCard == null) {
                        bridge.toast("Action is no longer available")
                        return@ThreadPageContent
                    }
                    if (!actionCard.isSupportedCommitAction()) {
                        bridge.toast("Action is not supported yet")
                        return@ThreadPageContent
                    }
                    if (actionCard.type.equals("agent:create", ignoreCase = true)) {
                        createAgentDialogState =
                            ThreadCreateAgentActionDialogState(
                                messageId = itemId,
                                card = actionCard,
                                submitting = false,
                                errorCode = null
                            )
                        return@ThreadPageContent
                    }
                    pendingActionCardIds = pendingActionCardIds + itemId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.commitThreadActionCard(renderedPayload, itemId, actionCard)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(itemId, result.data)
                                    )
                                    bridge.toast(actionCard.actionCardSuccessToast())
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    bridge.toast(actionCard.actionCardFailureToast())
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    bridge.toast(actionCard.actionCardFailureToast())
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - itemId
                        }
                    }
                }
                action == "toggleReaction" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val reactionKey = "$itemId:$tab"
                    if (reactionKey in pendingReactionKeys) {
                        return@ThreadPageContent
                    }
                    val previousPayload = renderedPayload
                    val toggleTarget = previousPayload.threadReactionToggleTarget(itemId, tab)
                    if (toggleTarget == null) {
                        return@ThreadPageContent
                    }
                    val nextSelected = toggleTarget.nextSelected
                    val previousReactions = toggleTarget.message.reactions
                    pendingReactionKeys = pendingReactionKeys + reactionKey
                    threadViewModel.dispatch(
                        SharedThreadIntent.ApplyMessageReactionSelection(itemId, tab, selected = nextSelected)
                    )
                    bridge.log(
                        "thread_reaction_toggle messageId=$itemId emoji=$tab selected=$nextSelected"
                    )
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.setMessageReaction(itemId, tab, selected = nextSelected)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(SharedThreadIntent.ApplyMessageMutation(result.data))
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.toast("Unable to update reaction")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyMessageReactions(itemId, previousReactions)
                                    )
                                    bridge.toast("Unable to update reaction")
                                }
                            }
                        } finally {
                            pendingReactionKeys = pendingReactionKeys - reactionKey
                        }
                    }
                }
                action == "openTask" && !itemId.isNullOrBlank() -> {
                    val parentMessage = renderedPayload.findTaskParentMessage(itemId)
                    if (parentMessage == null) {
                        bridge.toast("Unable to open task")
                        return@ThreadPageContent
                    }
                    val loadingPayload = renderedPayload.replyThreadLoadingPayload(parentMessage)
                    onOpenThreadPayload(loadingPayload)
                    coroutineScope.launch {
                        when (val result = dataSource.createOrOpenReplyThread(renderedPayload, parentMessage)) {
                            is DataResult.Content -> onReplaceThreadPayload(result.data)
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed")
                                )
                                bridge.toast("Unable to open task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                onReplaceThreadPayload(
                                    loadingPayload.copy(status = "error", errorCode = "thread_task_open_failed")
                                )
                                bridge.toast("Unable to open task")
                            }
                        }
                    }
                }
                action == "openMarkdownUrl" && !itemId.isNullOrBlank() -> {
                    val target = itemId.toSlockThreadWebLinkTarget()
                    if (target == null) {
                        bridge.openUrl(itemId, null)
                    } else {
                        val loadingPayload = renderedPayload.webLinkLoadingPayload(target)
                        onOpenThreadPayload(loadingPayload)
                        coroutineScope.launch {
                            when (val result = dataSource.fetchWebLinkThreadPayload(loadingPayload, target)) {
                                is DataResult.Content -> onReplaceThreadPayload(result.data)
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_weblink_open_failed")
                                    )
                                    bridge.toast("Unable to open link")
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    onReplaceThreadPayload(
                                        loadingPayload.copy(status = "error", errorCode = "thread_weblink_open_failed")
                                    )
                                    bridge.toast("Unable to open link")
                                }
                            }
                        }
                    }
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
                action == "submitAddMember" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedPayload
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetAddMemberStatus(status = "adding", errorCode = null)
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.addChannelMember(previousPayload, itemId, tab)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast("Member added")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast("Unable to add member")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_add_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetAddMemberStatus(status = "failed", errorCode = errorCode)
                                )
                                bridge.toast("Unable to add member")
                            }
                        }
                    }
                }
                action == "removeMember" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedPayload
                    val member = previousPayload.members.firstOrNull { it.id == itemId }
                    threadViewModel.dispatch(
                        SharedThreadIntent.SetRemoveMemberStatus(
                            status = "removing",
                            removingMemberId = itemId,
                            errorCode = null
                        )
                    )
                    coroutineScope.launch {
                        when (val result = dataSource.removeChannelMember(previousPayload, itemId, member?.kind)) {
                            is DataResult.Content -> {
                                threadViewModel.dispatch(
                                    SharedThreadIntent.ApplyMembersPatch(
                                        membersCount = result.data.membersCount,
                                        members = result.data.members,
                                        memberCandidates = result.data.memberCandidates,
                                        canAddMembers = result.data.canAddMembers
                                    )
                                )
                                bridge.toast("Member removed")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.toast("Unable to remove member")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                val errorCode = result.threadActionErrorCode("member_remove_failed")
                                threadViewModel.dispatch(
                                    SharedThreadIntent.SetRemoveMemberStatus(
                                        status = "failed",
                                        removingMemberId = itemId,
                                        errorCode = errorCode
                                    )
                                )
                                bridge.toast("Unable to remove member")
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "visibility" -> {
                    bridge.toast("Channel visibility updates are not wired yet")
                }
                action == "settingsAction" && itemId == "archive" -> {
                    val previousPayload = renderedPayload
                    coroutineScope.launch {
                        when (val result = dataSource.archiveChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast("Channel archived")
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast("Unable to archive channel")
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast("Unable to archive channel")
                            }
                        }
                    }
                }
                action == "settingsAction" && itemId == "delete" -> {
                    val previousPayload = renderedPayload
                    coroutineScope.launch {
                        when (val result = dataSource.deleteChannel(previousPayload)) {
                            is DataResult.Content -> {
                                bridge.toast("Channel deleted")
                                onBack()
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> bridge.toast("Unable to delete channel")
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                bridge.toast("Unable to delete channel")
                            }
                        }
                    }
                }
                action == "setTaskStatus" && !itemId.isNullOrBlank() && !tab.isNullOrBlank() -> {
                    val status = tab.toSharedThreadTaskStatus()
                    if (status == null) {
                        bridge.toast("Unsupported task status")
                        return@ThreadPageContent
                    }
                    val previousPayload = renderedPayload
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, status.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, status)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "markTaskDone" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedPayload
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, TaskStatus.Done.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Done)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "reopenTask" && !itemId.isNullOrBlank() -> {
                    val previousPayload = renderedPayload
                    val previousStatusLabel = previousPayload.taskStatusLabel(itemId)
                    threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, TaskStatus.Todo.name))
                    coroutineScope.launch {
                        when (val result = dataSource.updateTaskStatus(itemId, TaskStatus.Todo)) {
                            is DataResult.Content -> {
                                bridge.toast("Task updated")
                            }
                            is DataResult.Empty,
                            is DataResult.Error -> {
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                            is DataResult.NoPermission -> {
                                if (routeAuthRequired(result)) return@launch
                                threadViewModel.dispatch(SharedThreadIntent.ApplyTaskStatus(itemId, previousStatusLabel))
                                bridge.toast("Unable to update task")
                            }
                        }
                    }
                }
                action == "selectTab" -> {
                    if (tab == KuiklyThreadTab.Files.wireValue && filesPayload.shouldFetchInitialFiles()) {
                        loadThreadFiles("tab_select")
                    }
                }
                action == "loadMembers" -> {
                    threadViewModel.dispatch(SharedThreadIntent.LoadMembers)
                }
                action == "loadMoreFiles" -> {
                    val cursor = filesPayload.nextCursor?.takeIf { it.isNotBlank() }
                    if (cursor != null && filesPayload.hasMore) {
                        loadThreadFiles("load_more", cursor)
                    }
                }
                action == "retryFiles" -> {
                    loadThreadFiles("retry")
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
                val composerInstanceId = composerState.instanceId.ifBlank { payload.instanceId }
                when (action) {
                "change" -> {
                    if (composerSending) {
                        return@ThreadPageContent
                    }
                    value?.let { composerDraftText = it }
                    asTask?.let { composerDraftAsTask = it }
                    composerFailureLabel = null
                }
                "toggleAsTask" -> {
                    if (composerSending) {
                        return@ThreadPageContent
                    }
                    asTask?.let { composerDraftAsTask = it }
                    composerFailureLabel = null
                }
                "send" -> {
                    val threadId = composerSurface.sendChannelId
                    val draftText = composerDraftText.trim()
                    val attachmentIds = composerState.pendingAttachments.map { it.id }.filter { it.isNotBlank() }
                    if (!payload.canSend || composerSending || threadId == null ||
                        (draftText.isBlank() && attachmentIds.isEmpty())
                    ) {
                        return@ThreadPageContent
                    }
                    val draftAsTask = composerDraftAsTask
                    val sendStartedAt = TimeSource.Monotonic.markNow()
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "start",
                        threadId = threadId,
                        metadata =
                            mapOf(
                                "asTask" to draftAsTask.toString(),
                                "attachments" to attachmentIds.size.toString(),
                                "hasText" to draftText.isNotBlank().toString()
                            )
                    )
                    composerSending = true
                    composerFailureLabel = null
                    composerDraftText = ""
                    composerDraftAsTask = false
                    composerSyncNonce += 1
                    coroutineScope.launch {
                        val sendResponse =
                            runCatchingPreservingCancellation {
                                dataSource.sendMessage(
                                    channelId = threadId,
                                    body = draftText,
                                    asTask = draftAsTask,
                                    attachmentIds = attachmentIds
                                )
                            }.getOrElse {
                                bridge.performanceMarker(
                                    phase = "composer_send",
                                    routeName = "kuikly_thread",
                                    event = "end",
                                    threadId = threadId,
                                    durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                                    metadata = mapOf("result" to "exception")
                                )
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
                                    bridge.performanceMarker(
                                        phase = "composer_send",
                                        routeName = "kuikly_thread",
                                        event = "end",
                                        threadId = threadId,
                                        durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                                        metadata =
                                            mapOf(
                                                "result" to "failure",
                                                "code" to (sendResponse.contractCode ?: "unknown")
                                            )
                                    )
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
                        if (sentPage.messages.isEmpty()) {
                            bridge.performanceMarker(
                                phase = "composer_send",
                                routeName = "kuikly_thread",
                                event = "end",
                                threadId = threadId,
                                durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                                metadata = mapOf("result" to "empty")
                            )
                            composerSending = false
                            composerDraftText = draftText
                            composerDraftAsTask = draftAsTask
                            composerFailureLabel = "Send failed. Try again."
                            composerSyncNonce += 1
                            return@launch
                        }
                        threadViewModel.dispatch(SharedThreadIntent.AppendSentPage(sentPage))
                        threadViewModel.dispatch(SharedThreadIntent.Refresh)
                        bridge.performanceMarker(
                            phase = "composer_send",
                            routeName = "kuikly_thread",
                            event = "end",
                            threadId = threadId,
                            durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                            metadata =
                                mapOf(
                                    "result" to "success",
                                    "messages" to sentPage.messages.size.toString()
                                )
                        )
                        uploadedAttachments = emptyList()
                        removedAttachmentIds = emptySet()
                        composerDraftText = ""
                        composerDraftAsTask = false
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
                        val channelId = composerSurface.sendChannelId
                        if (channelId == null) {
                            bridge.toast("Attachment picker unavailable")
                            return@launch
                        }
                        val response =
                            bridge.threadAttachmentUpload(
                                channelId = channelId,
                                imageOnly = action == "attachImage"
                            )
                        val files = parseSharedThreadPickedAttachmentFiles(response)
                        if (files.isEmpty()) {
                            val errorCode = response?.optString("errorCode")?.takeIf { it.isNotBlank() }
                            when {
                                errorCode != null ->
                                    bridge.toast("Attachment upload failed: $errorCode")
                                response == null ->
                                    bridge.toast("Attachment picker unavailable")
                            }
                            return@launch
                        }
                        when (val uploadResult = dataSource.uploadAttachments(channelId, files)) {
                            is DataResult.Content -> {
                                val uploaded =
                                    uploadResult.data.attachments.map { attachment ->
                                        SharedThreadComposerAttachment(
                                            id = attachment.id.value,
                                            fileName = attachment.name,
                                            sizeLabel = attachment.sizeLabel,
                                            mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                            status = "ready"
                                        )
                                    }
                                if (uploaded.isEmpty()) {
                                    bridge.toast("Attachment upload failed: empty_upload_result")
                                    return@launch
                                }
                                uploadedAttachments =
                                    (uploadedAttachments + uploaded).distinctBy { attachment -> attachment.id }
                                removedAttachmentIds =
                                    removedAttachmentIds - uploaded.map { attachment -> attachment.id }.toSet()
                                composerFailureLabel = null
                                composerSyncNonce += 1
                            }
                            is DataResult.Error ->
                                bridge.toast("Attachment upload failed: ${uploadResult.error.code}")
                            is DataResult.NoPermission ->
                                bridge.toast("Attachment upload failed: no_permission")
                            is DataResult.Empty ->
                                bridge.toast("Attachment upload failed: empty_upload_result")
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
                "blur" -> {
                    bridge.log(
                        "thread_composer_blur instanceId=$composerInstanceId"
                    )
                }
                else -> {
                    bridge.log(
                        "thread_composer_event_unsupported instanceId=$composerInstanceId action=$action attachmentId=${attachmentId.orEmpty()}"
                    )
                }
                }
            }
        )
        imagePreviewState?.let { preview ->
            ThreadImagePreviewOverlay(
                state = preview,
                resolvedUrls = imagePreviewUrlCache,
                onDismiss = { imagePreviewState = null },
                onOpenExternally = { attachment ->
                    openAttachmentExternally(attachment)
                },
                onSelectIndex = ::selectImagePreviewIndex
            )
        }
        createAgentDialogState?.let { dialogState ->
            SharedCreateAgentDialog(
                submitting = dialogState.submitting,
                errorMessage = dialogState.errorCode?.let(::threadCreateAgentDialogErrorCopy),
                initialName = dialogState.card.name.removePrefix("@"),
                initialDescription = dialogState.card.description.orEmpty(),
                draftHint = dialogState.card.draftHint,
                suggestedComputer = dialogState.card.suggestedComputer,
                requiredComputer = dialogState.card.requiredComputer,
                onDismiss = {
                    if (!dialogState.submitting) {
                        createAgentDialogState = null
                    }
                },
                onSubmit = { request ->
                    if (dialogState.submitting) {
                        return@SharedCreateAgentDialog
                    }
                    val normalizedName = request.name.trim().removePrefix("@")
                    if (normalizedName.isBlank()) {
                        createAgentDialogState = dialogState.copy(errorCode = "agent_name_required")
                        return@SharedCreateAgentDialog
                    }
                    val submitCard =
                        dialogState.card.copy(
                            name = normalizedName,
                            description = request.description,
                            suggestedComputer =
                                if (dialogState.card.requiredComputer.isNullOrBlank()) {
                                    request.machineId
                                } else {
                                    dialogState.card.suggestedComputer
                                }
                        )
                    val submittingState =
                        dialogState.copy(
                            card = submitCard,
                            submitting = true,
                            errorCode = null
                        )
                    createAgentDialogState = submittingState
                    pendingActionCardIds = pendingActionCardIds + dialogState.messageId
                    coroutineScope.launch {
                        try {
                            when (val result = dataSource.commitAgentCreateActionCard(dialogState.messageId, submitCard)) {
                                is DataResult.Content -> {
                                    threadViewModel.dispatch(
                                        SharedThreadIntent.ApplyActionCard(dialogState.messageId, result.data)
                                    )
                                    createAgentDialogState = null
                                    bridge.toast(submitCard.actionCardSuccessToast())
                                }
                                is DataResult.Empty,
                                is DataResult.Error -> {
                                    createAgentDialogState =
                                        submittingState.copy(
                                            submitting = false,
                                            errorCode = result.threadActionErrorCode("agent_create_failed")
                                        )
                                }
                                is DataResult.NoPermission -> {
                                    if (routeAuthRequired(result)) return@launch
                                    createAgentDialogState =
                                        submittingState.copy(
                                            submitting = false,
                                            errorCode = result.blocker.code
                                        )
                                }
                            }
                        } finally {
                            pendingActionCardIds = pendingActionCardIds - dialogState.messageId
                        }
                    }
                }
            )
        }
    }
}

private suspend fun openSharedThreadAttachment(
    bridge: ai.slock.compose.base.BridgeModule,
    dataSource: SharedThreadDataSource,
    attachment: KuiklyThreadAttachmentItem
): ThreadAttachmentOpenResult {
    val attachmentId = attachment.id.takeIf { it.isNotBlank() }
        ?: return ThreadAttachmentOpenResult(status = "failed", message = "Unable to open attachment")
    return when (val response = dataSource.attachmentUrl(attachmentId, disposition = "attachment")) {
        is ThreadApiResult.Success -> {
            val nativeResult =
                bridge.openAttachment(
                    url = response.data.url,
                    attachmentId = attachmentId,
                    fileName = attachment.name.ifBlank { "attachment-${attachmentId.take(8)}" },
                    mimeType = attachment.mimeType
                )
            when (nativeResult?.optString("status").orEmpty()) {
                "opened" -> ThreadAttachmentOpenResult(status = "opened")
                "no_app" ->
                    ThreadAttachmentOpenResult(
                        status = "no_app",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "No app can open this file" }
                    )
                "storage_failed" ->
                    ThreadAttachmentOpenResult(
                        status = "storage_failed",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "Storage unavailable" }
                    )
                else ->
                    ThreadAttachmentOpenResult(
                        status = "failed",
                        message = nativeResult?.optString("message").orEmpty().ifBlank { "Unable to download attachment" }
                    )
            }
        }
        is ThreadApiResult.Failure ->
            ThreadAttachmentOpenResult(status = "failed", message = "Unable to download attachment")
    }
}

internal fun String.toSlockThreadWebLinkTarget(): SlockThreadWebLinkTarget? {
    val trimmed = trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val withoutScheme = trimmed.substring(schemeEnd + 3)
    val pathStart = withoutScheme.indexOf('/')
    val host = if (pathStart >= 0) withoutScheme.substring(0, pathStart) else withoutScheme
    if (host.substringBefore(':').lowercase() !in setOf("app.slock.ai", "app.raft.build")) return null
    val pathAndQuery = if (pathStart >= 0) withoutScheme.substring(pathStart) else ""
    val path = pathAndQuery.substringBefore('?')
    val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
    val segments = path.split('/').filter { it.isNotBlank() }
    val channelIndex = segments.indexOf("channel")
    val channelId = segments.getOrNull(channelIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
    val messageId =
        query.split('&')
            .firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == "msg" && it.isNotBlank() }
            }
    return SlockThreadWebLinkTarget(channelId = channelId, messageId = messageId)
}

private fun KuiklyThreadScreenPayload.webLinkLoadingPayload(target: SlockThreadWebLinkTarget): KuiklyThreadScreenPayload {
    val composer =
        buildThreadSurfaceComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            displayName = "Thread",
            fallbackId = target.channelId,
            enabled = canSend,
            streamChannelId = target.channelId
        )
    return copy(
        instanceId = "shared-shell-thread-${target.channelId}",
        title = "Thread",
        subtitle = null,
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.Channel,
        sourceThreadId = target.channelId,
        streamChannelId = target.channelId,
        parentChannelId = null,
        parentMessageId = null,
        selectedTab = KuiklyThreadTab.Chat.wireValue,
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originParentMessageId = null,
        originStatus = "none",
        originErrorCode = null,
        errorCode = null,
        filesPayload = "{}",
        composer = composer
    )
}

private fun KuiklyThreadScreenPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val messageCandidates = (listOfNotNull(originMessage) + messages).distinctBy { it.id }
    messageCandidates.forEach { message ->
        val images = message.attachments.filter { it.isImageAttachment() }
        val currentIndex = images.indexOfFirst { it.id == attachmentId }
        if (currentIndex >= 0) {
            return ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
        }
    }
    return null
}

private fun KuiklyThreadScreenPayload.findThreadAttachment(attachmentId: String): KuiklyThreadAttachmentItem? =
    (listOfNotNull(originMessage) + messages)
        .asSequence()
        .flatMap { it.attachments.asSequence() }
        .firstOrNull { it.id == attachmentId }

private fun KuiklyThreadFilesPayload.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val images =
        items
            .filter { it.mimeType.startsWith("image/", ignoreCase = true) }
            .map { item ->
                KuiklyThreadAttachmentItem(
                    id = item.attachmentId,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeLabel = item.sizeLabel,
                    thumbnailUrl = item.thumbnailUrl
                )
            }
    val currentIndex = images.indexOfFirst { it.id == attachmentId }
    return if (currentIndex >= 0) ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex) else null
}

private fun KuiklyThreadFilesPayload.findThreadAttachment(attachmentId: String): KuiklyThreadAttachmentItem? =
    items.firstOrNull { it.attachmentId == attachmentId }?.let { item ->
        KuiklyThreadAttachmentItem(
            id = item.attachmentId,
            name = item.name,
            mimeType = item.mimeType,
            sizeLabel = item.sizeLabel,
            thumbnailUrl = item.thumbnailUrl
        )
    }

@Composable
private fun ThreadImagePreviewOverlay(
    state: ThreadImagePreviewState,
    resolvedUrls: Map<String, ThreadImagePreviewUrl>,
    onDismiss: () -> Unit,
    onOpenExternally: (KuiklyThreadAttachmentItem) -> Unit,
    onSelectIndex: (Int) -> Unit
) {
    val current = state.current ?: return
    val thumbnailUrl = current.thumbnailUrl?.takeIf { it.isNotBlank() }
    val fullUrl = resolvedUrls[current.id]?.url?.takeIf { it.isNotBlank() }
    val displayUrl = fullUrl ?: thumbnailUrl
    val hasMultiple = state.images.size > 1
    val isLoading = current.id in state.loadingAttachmentIds
    val failedWithoutImage = current.id in state.failedAttachmentIds && displayUrl == null
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val swipeThresholdPx =
        with(LocalDensity.current) {
            56.dp.toPx()
        }
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.TextBlack.copy(alpha = 0.92f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .height(topSafeArea + 58.dp)
                    .background(SlockColor.SurfaceWhite)
                    .padding(start = 12.dp, top = topSafeArea, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThreadImagePreviewIconButton(icon = "arrow-left", onClick = onDismiss)
                Text(
                    text = current.name.ifBlank { "Image" },
                    style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ThreadImagePreviewIconButton(
                    icon = "external-link",
                    enabled = displayUrl != null,
                    onClick = { onOpenExternally(current) }
                )
                ThreadImagePreviewIconButton(icon = "x", onClick = onDismiss)
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack)
            )
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .then(
                            if (hasMultiple) {
                                Modifier.pointerInput(state.currentIndex, state.images.size) {
                                    var dragDistance = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragDistance = 0f },
                                        onDragEnd = {
                                            when {
                                                dragDistance <= -swipeThresholdPx && state.currentIndex < state.images.lastIndex -> {
                                                    onSelectIndex(state.currentIndex + 1)
                                                }

                                                dragDistance >= swipeThresholdPx && state.currentIndex > 0 -> {
                                                    onSelectIndex(state.currentIndex - 1)
                                                }
                                            }
                                        },
                                        onDragCancel = { dragDistance = 0f }
                                    ) { _, dragAmount ->
                                        dragDistance += dragAmount
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (displayUrl != null) {
                        SlockCachedImage(
                            source = displayUrl,
                            contentDescription = current.name,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        ThreadImagePreviewEmptyState(
                            title = if (failedWithoutImage) "Image unavailable" else "Loading image...",
                            detail = current.name
                        )
                    }
                }
                if (isLoading && displayUrl != null) {
                    ThreadImagePreviewLoadingPill(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = if (hasMultiple) 56.dp else 12.dp)
                    )
                }
                if (hasMultiple) {
                    ThreadImagePreviewNavigationPill(
                        label = "${state.currentIndex + 1}/${state.images.size}",
                        canGoPrevious = state.currentIndex > 0,
                        canGoNext = state.currentIndex < state.images.lastIndex,
                        onPrevious = { onSelectIndex(state.currentIndex - 1) },
                        onNext = { onSelectIndex(state.currentIndex + 1) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    )
                }
            }
            if (hasMultiple) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth()
                        .height(74.dp + bottomSafeArea)
                        .background(SlockColor.TextBlack)
                        .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp + bottomSafeArea),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(state.images.size, key = { state.images[it].id }) { index ->
                        ThreadImagePreviewThumbnail(
                            attachment = state.images[index],
                            selected = index == state.currentIndex,
                            resolvedUrl = resolvedUrls[state.images[index].id]?.url,
                            onClick = { onSelectIndex(index) }
                        )
                    }
                }
            } else {
                if (bottomSafeArea > 0.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bottomSafeArea)
                            .background(SlockColor.TextBlack.copy(alpha = 0.92f))
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadImagePreviewIconButton(
    icon: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        enabled = enabled,
        size = 36.dp,
        shadowOffset = 3.dp,
        disabledAlpha = 0.42f
    )
}

@Composable
private fun ThreadImagePreviewEmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier.widthIn(max = 260.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon("image", modifier = Modifier.size(34.dp), color = "#FF000000")
        Text(
            text = title,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = detail,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadImagePreviewLoadingPill(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Loading full image...",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
private fun ThreadImagePreviewNavigationPill(
    label: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreadImagePreviewInlineNavButton(
            icon = "chevron-left",
            enabled = canGoPrevious,
            onClick = onPrevious
        )
        Text(
            text = label,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
        ThreadImagePreviewInlineNavButton(
            icon = "chevron-right",
            enabled = canGoNext,
            onClick = onNext
        )
    }
}

@Composable
private fun ThreadImagePreviewInlineNavButton(
    icon: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(icon, modifier = Modifier.size(16.dp), color = "#FF000000")
    }
}

@Composable
private fun ThreadImagePreviewThumbnail(
    attachment: KuiklyThreadAttachmentItem,
    selected: Boolean,
    resolvedUrl: String?,
    onClick: () -> Unit
) {
    val source = attachment.thumbnailUrl?.takeIf { it.isNotBlank() } ?: resolvedUrl?.takeIf { it.isNotBlank() }
    Button(
        onClick = onClick,
        pressOffset = 2.dp,
        modifier = Modifier.size(54.dp)
            .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .padding(if (selected) 3.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Thin, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (source != null) {
                SlockCachedImage(
                    source = source,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SlockLucideIcon("image", modifier = Modifier.size(20.dp), color = "#FF000000")
            }
        }
    }
}

internal fun KuiklyThreadScreenPayload.replyThreadLoadingPayload(
    parentMessage: KuiklyThreadMessageItem
): KuiklyThreadScreenPayload {
    val composer =
        buildThreadSurfaceComposerPayload(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            displayName = ThreadReplyPageTitle,
            fallbackId = parentMessage.id,
            enabled = canSend,
            mentionChannelId = sourceThreadId,
            parentMessageId = parentMessage.id,
            streamChannelId = null
        )
    return copy(
        instanceId = "shared-shell-thread-pending-${parentMessage.id}",
        title = ThreadReplyPageTitle,
        subtitle = replyThreadHeaderSubtitle(),
        status = "loading",
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = null,
        streamChannelId = null,
        parentChannelId = sourceThreadId,
        parentMessageId = parentMessage.id,
        navigationLabel = "Close",
        selectedTab = "chat",
        showModeTabs = false,
        showReplyActions = false,
        showOpenInChannelAction = true,
        originMessage = parentMessage,
        originParentMessageId = parentMessage.id,
        originStatus = "loaded",
        messages = emptyList(),
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        emptyTitle = "Opening replies",
        emptyDetail = "Loading the message thread.",
        composer = composer
    )
}

private fun KuiklyThreadFilesPayload.shouldFetchInitialFiles(): Boolean =
    status == "loading" || (status == "error" && items.isEmpty())

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

@Composable
private fun ThreadPageContent(
    payload: KuiklyThreadScreenPayload,
    filesPayload: KuiklyThreadFilesPayload,
    composerSurface: ThreadComposerSurface,
    composerState: KuiklyThreadComposerPayload,
    tasksState: TasksUiState,
    onTasksIntent: (SharedTasksRouteIntent) -> Unit,
    highlightedMentions: Set<String>,
    diagnosticsLogger: (String) -> Unit = {},
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onBack: () -> Unit,
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onComposerEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    var selectedTab by remember(payload.selectedTab) {
        mutableStateOf(KuiklyThreadTab.fromWireValue(payload.selectedTab))
    }
    val availableTabs =
        remember(payload.showTaskOpenActions, payload.showTaskStatusActions, payload.composer.showAsTaskToggle) {
            KuiklyThreadTab.entries.filter { tab ->
                tab != KuiklyThreadTab.Tasks || payload.supportsChannelTasks()
            }
        }
    LaunchedEffect(availableTabs, selectedTab) {
        if (selectedTab !in availableTabs) {
            selectedTab = KuiklyThreadTab.Chat
            onThreadEvent("selectTab", null, null, KuiklyThreadTab.Chat.wireValue)
        }
    }
    var activeModal by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var activeMemberId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var composerKeyboardInsetDp by remember(payload.instanceId) { mutableStateOf(0f) }
    val taskChannelId =
        remember(payload.sourceThreadId, payload.instanceId) {
            payload.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: ChannelId(payload.instanceId)
        }
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
                    onThreadEvent("loadMembers", null, null, null)
                },
                onShowSettings = {
                    activeModal = "settings"
                }
            )
            if (payload.showModeTabs) {
                ThreadPageTabs(
                    selectedTab = selectedTab,
                    tabs = availableTabs,
                    onSelect = { tab ->
                        selectedTab = tab
                        onThreadEvent("selectTab", null, null, tab.wireValue)
                    }
                )
            }
            val linkedTasksByMessageId = remember(tasksState.loadState) {
                tasksState.threadLinkedTasksByMessageId()
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceWhite)
            ) {
                when (selectedTab) {
                    KuiklyThreadTab.Chat ->
                        ThreadChatTab(
                            payload = payload,
                            composerKeyboardInsetDp = composerKeyboardInsetDp,
                            linkedTasksByMessageId = linkedTasksByMessageId,
                            highlightedMentions = highlightedMentions,
                            pendingActionCardIds = pendingActionCardIds,
                            attachmentOpenStates = attachmentOpenStates,
                            onThreadEvent = onThreadEvent,
                            onOpenMarkdownTag = openMarkdownTag,
                            onOpenMarkdownUrl = openMarkdownUrl,
                            diagnosticsLogger = diagnosticsLogger
                        )
                    KuiklyThreadTab.Tasks ->
                        ThreadTasksTab(
                            state = tasksState,
                            channelId = taskChannelId,
                            openTaskEnabled = payload.showTaskOpenActions,
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onIntent = onTasksIntent
                        )
                    KuiklyThreadTab.Files ->
                        ThreadFilesContent(payload = filesPayload, attachmentOpenStates = attachmentOpenStates) { action, attachmentId ->
                            when (action) {
                                "open" ->
                                    onThreadEvent("openAttachment", null, attachmentId, null)
                                "download" ->
                                    onThreadEvent("downloadAttachment", null, attachmentId, null)
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
                                start = SlockSpacing.Sm,
                                top = 12.dp,
                                end = SlockSpacing.Sm,
                                bottom = 12.dp + composerKeyboardInsetDp.dp
                            )
                    ) {
                        ThreadComposerContent(
                            surface = composerSurface,
                            state = composerState.toThreadComposerUiState(),
                            onEvent = onComposerEvent,
                            onKeyboardHeightChange = { nextInset ->
                                if (nextInset != composerKeyboardInsetDp) {
                                    composerKeyboardInsetDp = nextInset
                                }
                            },
                            contentPaddingTop = 0.dp,
                            contentPaddingBottom = 0.dp
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
            ThreadHeaderIconButton(icon = "arrow-left", onClick = onBack)
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
            style = SlockType.PageTitle.withSlockFontWeight(FontWeight.W700).copy(color = SlockColor.TextBlack),
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
    SlockHeaderIconAction(
        icon = "users",
        contentDescription = "Members",
        onClick = onClick,
        label = count.takeIf { it > 0 }?.toString().orEmpty()
    )
}

@Composable
private fun ThreadHeaderAction(label: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 30.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadPageTabs(
    selectedTab: KuiklyThreadTab,
    tabs: List<KuiklyThreadTab> = KuiklyThreadTab.entries,
    onSelect: (KuiklyThreadTab) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().height(ThreadTabHeight).background(SlockColor.SurfaceWhite)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            tabs.forEach { tab ->
                val selected = selectedTab == tab
                Row(
                    modifier = Modifier.width(ThreadTabWidth)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable { onSelect(tab) }
                        .padding(horizontal = SlockSpacing.Sm + SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm - SlockSpacing.Xxs, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(tab.icon, modifier = Modifier.size(16.dp), color = "#FF000000")
                    Text(
                        text = tab.label.uppercase(),
                        style = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
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

private val ThreadTabWidth = 98.dp
private val ThreadTabHeight = 32.dp

@Composable
private fun ThreadChatTab(
    payload: KuiklyThreadScreenPayload,
    composerKeyboardInsetDp: Float,
    linkedTasksByMessageId: Map<String, TaskSummary>,
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    when (payload.status) {
        "loading" ->
            if (payload.originStatus != "none") {
                ThreadMessagesList(
                    payload = payload,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksByMessageId = linkedTasksByMessageId,
                    highlightedMentions = highlightedMentions,
                    pendingActionCardIds = pendingActionCardIds,
                    attachmentOpenStates = attachmentOpenStates,
                    onThreadEvent = onThreadEvent,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger
                )
            } else {
                ThreadStateMessage("Loading thread…", null)
            }
        "error" ->
            ThreadStateActionMessage(
                title = "Failed to load thread",
                detail = payload.errorCode ?: "unknown_error",
                actionLabel = "Retry",
                onClick = { onThreadEvent("retryThreadLoad", null, null, null) }
            )
        "no_permission" -> ThreadStateMessage("No permission", payload.errorCode ?: "missing capability")
        "empty" ->
            if (payload.originStatus != "none") {
                ThreadMessagesList(
                    payload = payload,
                    composerKeyboardInsetDp = composerKeyboardInsetDp,
                    linkedTasksByMessageId = linkedTasksByMessageId,
                    highlightedMentions = highlightedMentions,
                    pendingActionCardIds = pendingActionCardIds,
                    attachmentOpenStates = attachmentOpenStates,
                    onThreadEvent = onThreadEvent,
                    onOpenMarkdownTag = onOpenMarkdownTag,
                    onOpenMarkdownUrl = onOpenMarkdownUrl,
                    diagnosticsLogger = diagnosticsLogger
                )
            } else {
                ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
            }
        else ->
            ThreadMessagesList(
                payload = payload,
                composerKeyboardInsetDp = composerKeyboardInsetDp,
                linkedTasksByMessageId = linkedTasksByMessageId,
                highlightedMentions = highlightedMentions,
                pendingActionCardIds = pendingActionCardIds,
                attachmentOpenStates = attachmentOpenStates,
                onThreadEvent = onThreadEvent,
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl,
                diagnosticsLogger = diagnosticsLogger
            )
    }
}

@Composable
private fun ThreadMessagesList(
    payload: KuiklyThreadScreenPayload,
    composerKeyboardInsetDp: Float,
    linkedTasksByMessageId: Map<String, TaskSummary>,
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    if (payload.messages.isEmpty() && payload.originStatus == "none") {
        ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
        return
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var actionMessageId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var actionMenuAnchor by remember(payload.instanceId) { mutableStateOf(Offset(16f, 16f)) }
    var taskStatusMenuMessageId by remember(payload.instanceId) { mutableStateOf<String?>(null) }
    var taskStatusMenuAnchor by remember(payload.instanceId) { mutableStateOf(Offset(16f, 16f)) }
    var allowBottomAutoAnchor by remember(payload.instanceId) { mutableStateOf(true) }
    var initialBottomAnchorReady by remember(payload.instanceId) { mutableStateOf(false) }
    var lastComposerKeyboardInsetDp by remember(payload.instanceId) { mutableStateOf(composerKeyboardInsetDp) }
    var pendingKeyboardBottomAnchor by remember(payload.instanceId) { mutableStateOf(false) }
    val latestItemIndex = remember(
        payload.originStatus,
        payload.messages.size,
        payload.messages
    ) {
        payload.latestMessageListItemIndex()
    }
    val latestMessageLayoutKey = remember(
        payload.originStatus,
        payload.canLoadMoreBefore,
        payload.canLoadMoreAfter,
        payload.messages
    ) {
        payload.latestMessageLayoutKey()
    }
    val showReplyEmptyState = payload.messages.isEmpty() && payload.originStatus != "none"
    val lazyItemCount =
        remember(
            payload.originStatus,
            payload.canLoadMoreBefore,
            payload.messages.size,
            payload.canLoadMoreAfter,
            showReplyEmptyState
        ) {
            threadMessagesLazyItemCount(
                originStatus = payload.originStatus,
                showHistoryBoundary = payload.hasThreadHistoryBoundaryItem(),
                messagesCount = payload.messages.size,
                canLoadMoreAfter = payload.canLoadMoreAfter
            ) + if (showReplyEmptyState) 1 else 0
        }
    var pendingOlderPageAnchor by remember(payload.instanceId) { mutableStateOf<ThreadVisibleMessageAnchor?>(null) }
    var previousOlderMessagesStatus by remember(payload.instanceId) { mutableStateOf(payload.olderMessagesStatus) }
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
    LaunchedEffect(
        payload.instanceId,
        payload.canLoadMoreBefore,
        payload.olderMessagesStatus,
        initialBottomAnchorReady,
        payload.messages.size
    ) {
        if (!initialBottomAnchorReady ||
            !payload.canLoadMoreBefore ||
            payload.olderMessagesStatus != "idle"
        ) {
            return@LaunchedEffect
        }
        val boundaryIndex = payload.threadHistoryBoundaryListItemIndex()
        snapshotFlow { listState.isThreadOlderAutoLoadThreshold(boundaryIndex) }
            .collect { shouldLoad ->
                if (shouldLoad) {
                    allowBottomAutoAnchor = false
                    onThreadEvent("loadOlder", null, null, null)
                }
            }
    }
    LaunchedEffect(
        payload.instanceId,
        payload.olderMessagesStatus,
        payload.messages.size,
        payload.messages.firstOrNull()?.id
    ) {
        val previousStatus = previousOlderMessagesStatus
        if (payload.olderMessagesStatus == "loading" && previousStatus != "loading") {
            pendingOlderPageAnchor = listState.threadFirstVisibleMessageAnchor(payload)
            allowBottomAutoAnchor = false
        } else if (previousStatus == "loading" && payload.olderMessagesStatus != "loading") {
            val anchor = pendingOlderPageAnchor
            pendingOlderPageAnchor = null
            allowBottomAutoAnchor = false
            if (anchor != null) {
                val targetIndex = payload.threadMessageListItemIndex(anchor.messageId)
                if (targetIndex >= 0) {
                    listState.scrollToItem(targetIndex, -anchor.offset)
                }
            }
        }
        previousOlderMessagesStatus = payload.olderMessagesStatus
    }
    LaunchedEffect(payload.instanceId, latestMessageLayoutKey, latestItemIndex, allowBottomAutoAnchor) {
        if (latestItemIndex < 0) return@LaunchedEffect
        if (!initialBottomAnchorReady) {
            repeat(ThreadInitialBottomAnchorAttempts) { attempt ->
                listState.awaitThreadMessagesLayoutStability(latestItemIndex)
                listState.scrollLatestItemToRenderedBottom(latestItemIndex)
                delay(ThreadLayoutStabilityFrameDelayMs)
                if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                    listState.isLatestItemAtRenderedBottom(latestItemIndex)
                ) {
                    initialBottomAnchorReady = true
                    return@LaunchedEffect
                }
            }
            initialBottomAnchorReady = true
            return@LaunchedEffect
        }
        if (!allowBottomAutoAnchor && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            return@LaunchedEffect
        }
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        if (listState.isScrollInProgress && !listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            allowBottomAutoAnchor = false
            return@LaunchedEffect
        }
        if (!listState.isLatestItemAtRenderedBottom(latestItemIndex)) {
            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
        }
    }
    LaunchedEffect(payload.instanceId, composerKeyboardInsetDp, latestItemIndex) {
        if (composerKeyboardInsetDp == lastComposerKeyboardInsetDp) {
            return@LaunchedEffect
        }
        val wasAtBottom = initialBottomAnchorReady &&
            latestItemIndex >= 0 &&
            allowBottomAutoAnchor
        lastComposerKeyboardInsetDp = composerKeyboardInsetDp
        if (!wasAtBottom) {
            pendingKeyboardBottomAnchor = false
            return@LaunchedEffect
        }
        allowBottomAutoAnchor = true
        pendingKeyboardBottomAnchor = true
        listState.awaitThreadMessagesLayoutStability(latestItemIndex)
        repeat(ThreadKeyboardBottomAnchorAttempts) { attempt ->
            listState.scrollLatestItemToRenderedBottom(latestItemIndex)
            delay(ThreadLayoutStabilityFrameDelayMs)
            if (attempt >= ThreadBottomAnchorMinimumAttempts &&
                listState.isLatestItemAtRenderedBottom(latestItemIndex)
            ) {
                pendingKeyboardBottomAnchor = false
                return@LaunchedEffect
            }
        }
        pendingKeyboardBottomAnchor = false
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (payload.originStatus != "none") {
            ThreadOriginCard(
                payload = payload,
                highlightedMentions = highlightedMentions,
                pendingActionCardIds = pendingActionCardIds,
                attachmentOpenStates = attachmentOpenStates,
                onThreadEvent = onThreadEvent,
                onShowActions = { message, anchor ->
                    actionMenuAnchor = anchor
                    actionMessageId = message.id
                    diagnosticsLogger(
                        "thread_origin_menu_tap messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y}"
                    )
                },
                onOpenTaskStatusMenu = { message, anchor ->
                    taskStatusMenuMessageId = message.id
                    taskStatusMenuAnchor = anchor
                },
                onOpenMarkdownTag = onOpenMarkdownTag,
                onOpenMarkdownUrl = onOpenMarkdownUrl
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .padding(vertical = 2.dp)
                    .alpha(if (initialBottomAnchorReady || latestItemIndex < 0) 1f else 0f)
                    .bouncesEnable(false),
                verticalArrangement = Arrangement.Bottom,
                beyondBoundsItemCount = threadMessagesBeyondBoundsItemCount(lazyItemCount)
            ) {
            if (payload.hasThreadHistoryBoundaryItem()) {
                item(key = ThreadOlderMessagesListItemKey) {
                    when {
                        payload.canLoadMoreBefore && payload.olderMessagesStatus == "error" ->
                            ThreadInlineActionRow(
                                label = "Retry older messages",
                                onClick = { onThreadEvent("retryLoadOlder", null, null, null) }
                            )
                        payload.canLoadMoreBefore ->
                            ThreadHistoryStatusRow(
                                label = "Loading older messages…",
                                iconName = "loader-circle"
                            )
                        else ->
                            ThreadHistoryStatusRow(
                                label = "Beginning of conversation",
                                iconName = "flag"
                            )
                    }
                }
            }
            if (showReplyEmptyState) {
                item(key = ThreadReplyEmptyListItemKey) {
                    ThreadStateMessage(payload.emptyTitle, payload.emptyDetail)
                }
            }
            items(payload.messages.size, key = { payload.messages[it].messageListItemKey() }) { index ->
                val message = payload.messages[index]
                val linkedTask = linkedTasksByMessageId[message.id]
                val displayMessage = message.withLinkedTask(linkedTask)
                val linkedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName()
                if (message.isSystemMessage() && message.threadActionCard() == null) {
                    ThreadSystemMessageRow(
                        message = displayMessage,
                        highlightedMentions = highlightedMentions,
                        showReplyAction = payload.showReplyActions,
                        timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                        resolvedTaskAssigneeName = linkedTaskAssigneeName,
                        resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                        taskOpenEnabled = payload.showTaskOpenActions,
                        taskStatusActionsEnabled = payload.showTaskStatusActions,
                        onOpenThread = { onThreadEvent("openMessageThread", message.id, null, null) },
                        onOpenTask = { onThreadEvent("openTask", it, null, null) },
                        onOpenTaskStatusMenu = { anchor ->
                            taskStatusMenuMessageId = message.id
                            taskStatusMenuAnchor = anchor
                        },
                        onOpenMarkdownTag = onOpenMarkdownTag,
                        onOpenMarkdownUrl = onOpenMarkdownUrl
                    )
                } else {
                    ThreadMessageCard(
                        message = displayMessage,
                        highlightedMentions = highlightedMentions,
                        alignOwnMessageRight = payload.alignOwnMessagesRight && message.isOwn,
                        showReplyAction = payload.showReplyActions,
                        resolvedTaskAssigneeName = linkedTaskAssigneeName,
                        resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                        taskOpenEnabled = payload.showTaskOpenActions,
                        taskStatusActionsEnabled = payload.showTaskStatusActions,
                        onShowActions = { anchor ->
                            actionMenuAnchor = anchor
                            actionMessageId = message.id
                            diagnosticsLogger(
                                "thread_message_menu_tap messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y}"
                            )
                        },
                        onOpenMember = { onThreadEvent("openMember", it, null, null) },
                        onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                        onOpenTask = { onThreadEvent("openTask", it, null, null) },
                        onOpenTaskStatusMenu = { anchor ->
                            taskStatusMenuMessageId = message.id
                            taskStatusMenuAnchor = anchor
                        },
                        onToggleReaction = { emoji -> onThreadEvent("toggleReaction", message.id, null, emoji) },
                        onToggleSaved = { onThreadEvent("toggleMessageSaved", message.id, null, null) },
                        timestampLabel = payload.formatTimestampLabel(message.timestampLabel),
                        onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                        attachmentOpenStates = attachmentOpenStates,
                        pendingActionCardIds = pendingActionCardIds,
                        onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
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
        if (initialBottomAnchorReady &&
            !pendingKeyboardBottomAnchor &&
            listState.shouldShowThreadBackToBottom(latestItemIndex)
        ) {
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
            payload.threadActionMessage(messageId)?.let { message ->
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
                        onThreadEvent("toggleMessageSaved", message.id, null, null)
                    },
                    onReact = { emoji ->
                        actionMessageId = null
                        onThreadEvent("toggleReaction", message.id, null, emoji)
                    },
                    onTaskStatusChange = { action ->
                        actionMessageId = null
                        onThreadEvent(action, message.taskId, null, null)
                    },
                    diagnosticsLogger = diagnosticsLogger
                )
            }
        }
        taskStatusMenuMessageId?.let { messageId ->
            payload.threadActionMessage(messageId)?.let { message ->
                val selectedStatus = message.taskStatus.threadTaskStatusOption()
                SlockBaseMenuOverlay(
                    anchor = taskStatusMenuAnchor,
                    items = ThreadTaskStatusMenuItems,
                    selectedId = selectedStatus.key,
                    selectedRowColor = SlockColor.BrandYellow,
                    onDismiss = { taskStatusMenuMessageId = null },
                    onSelect = { selectedId ->
                        taskStatusMenuMessageId = null
                        val taskId = message.taskId?.takeIf { it.isNotBlank() }
                        val status = selectedId.toSharedThreadTaskStatus()
                        if (taskId != null && status != null && status.name != selectedStatus.key) {
                            onThreadEvent("setTaskStatus", taskId, null, selectedId)
                        }
                    }
                )
            }
        }
    }
}
}

private const val ThreadOlderMessagesListItemKey = "thread-page:older-messages"
private const val ThreadNewerMessagesListItemKey = "thread-page:newer-messages"
private const val ThreadReplyEmptyListItemKey = "thread-page:reply-empty"

internal fun threadMessagesLazyItemCount(
    originStatus: String,
    showHistoryBoundary: Boolean,
    messagesCount: Int,
    canLoadMoreAfter: Boolean
): Int =
    (if (showHistoryBoundary) 1 else 0) +
        messagesCount.coerceAtLeast(0) +
        (if (canLoadMoreAfter) 1 else 0)

internal fun threadMessagesBeyondBoundsItemCount(itemCount: Int): Int =
    itemCount.coerceAtLeast(0)
        .coerceAtMost(ThreadMessagesMaxBeyondBoundsItemCount)

private fun KuiklyThreadMessageItem.messageListItemKey(): String =
    "thread-page:message:$id"

internal fun KuiklyThreadMessageItem.threadActionCard(): ThreadActionCard? =
    actionCardFromMessageMetadata(metadata)

private fun KuiklyThreadScreenPayload.threadActionMessage(messageId: String): KuiklyThreadMessageItem? =
    messages.firstOrNull { it.id == messageId }
        ?: originMessage?.takeIf { originStatus != "none" && it.id == messageId }

private fun KuiklyThreadScreenPayload.hasThreadHistoryBoundaryItem(): Boolean =
    messages.isNotEmpty()

private fun KuiklyThreadScreenPayload.threadHistoryBoundaryListItemIndex(): Int =
    0

private fun KuiklyThreadScreenPayload.firstMessageListItemIndex(): Int {
    var index = 0
    if (hasThreadHistoryBoundaryItem()) index += 1
    return index
}

private fun KuiklyThreadScreenPayload.threadMessageListItemIndex(messageId: String): Int {
    val messageIndex = messages.indexOfFirst { it.id == messageId }
    if (messageIndex < 0) return -1
    return firstMessageListItemIndex() + messageIndex
}

private fun KuiklyThreadScreenPayload.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    return firstMessageListItemIndex() + messages.lastIndex
}

private fun KuiklyThreadScreenPayload.latestMessageLayoutKey(): String {
    val latest = messages.lastOrNull()
        ?: return "empty:$originStatus:$canLoadMoreBefore:$canLoadMoreAfter"
    return buildString {
        append(originStatus)
        append(':')
        append(hasThreadHistoryBoundaryItem())
        append(':')
        append(canLoadMoreAfter)
        append(':')
        append(messages.size)
        append(':')
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.taskId.orEmpty())
        append(':')
        append(latest.replyCount)
        append(':')
        append(latest.unreadReplyCount)
    }
}

internal fun KuiklyThreadScreenPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

internal fun KuiklyThreadScreenPayload.formatTimestampLabel(value: String): String =
    formatSlockTimestampLabel(value, toSlockTimeContext())

private data class ThreadVisibleMessageAnchor(
    val messageId: String,
    val offset: Int
)

private fun LazyListState.threadFirstVisibleMessageAnchor(
    payload: KuiklyThreadScreenPayload
): ThreadVisibleMessageAnchor? {
    val firstMessageListItemIndex = payload.firstMessageListItemIndex()
    val messageCount = payload.messages.size
    if (messageCount <= 0) return null
    val item =
        layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.index >= firstMessageListItemIndex &&
                    item.index < firstMessageListItemIndex + messageCount
            }
            ?: return null
    val message = payload.messages.getOrNull(item.index - firstMessageListItemIndex) ?: return null
    return ThreadVisibleMessageAnchor(messageId = message.id, offset = item.offset)
}

private fun LazyListState.isThreadOlderAutoLoadThreshold(boundaryIndex: Int): Boolean {
    if (boundaryIndex < 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= boundaryIndex) return false
    val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return false
    return firstVisibleIndex <= boundaryIndex + ThreadOlderAutoLoadVisibleItemThreshold
}

private suspend fun LazyListState.scrollLatestItemToRenderedBottom(latestItemIndex: Int) {
    if (latestItemIndex < 0) return
    if (layoutInfo.totalItemsCount <= latestItemIndex) {
        return
    }
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val offset =
        latestItem
            ?.let {
                threadLatestItemBottomAnchorScrollOffset(
                    itemSize = it.size,
                    viewportHeight = viewportHeight,
                    visibleItemCount = layoutInfo.visibleItemsInfo.size,
                    totalItemCount = layoutInfo.totalItemsCount
                )
            }
            ?: 0
    scrollToItem(latestItemIndex, offset)
}

private suspend fun LazyListState.awaitThreadMessagesLayoutStability(latestItemIndex: Int): Boolean {
    var previous: ThreadMessagesLayoutStabilitySnapshot? = null
    var stableFrames = 0
    repeat(ThreadLayoutStabilityMaxFrames) {
        val current = threadMessagesLayoutStabilitySnapshot(latestItemIndex)
        if (current.canAnchor && current == previous) {
            stableFrames += 1
            if (stableFrames >= ThreadLayoutStabilityRequiredFrames) {
                return true
            }
        } else {
            previous = current
            stableFrames = 0
        }
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return false
}

private fun LazyListState.threadMessagesLayoutStabilitySnapshot(
    latestItemIndex: Int
): ThreadMessagesLayoutStabilitySnapshot {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val first = visibleItems.firstOrNull()
    val last = visibleItems.lastOrNull()
    val latest = visibleItems.firstOrNull { it.index == latestItemIndex }
    return ThreadMessagesLayoutStabilitySnapshot(
        totalItemCount = layoutInfo.totalItemsCount,
        viewportStartOffset = layoutInfo.viewportStartOffset,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        visibleItemCount = visibleItems.size,
        firstItemIndex = first?.index ?: -1,
        firstItemOffset = first?.offset ?: 0,
        firstItemSize = first?.size ?: 0,
        lastItemIndex = last?.index ?: -1,
        lastItemOffset = last?.offset ?: 0,
        lastItemSize = last?.size ?: 0,
        latestItemIndex = latest?.index ?: -1,
        latestItemOffset = latest?.offset ?: 0,
        latestItemSize = latest?.size ?: 0,
        canAnchor = latestItemIndex >= 0 &&
            layoutInfo.totalItemsCount > latestItemIndex &&
            visibleItems.isNotEmpty() &&
            layoutInfo.viewportEndOffset > layoutInfo.viewportStartOffset
    )
}

private data class ThreadMessagesLayoutStabilitySnapshot(
    val totalItemCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val visibleItemCount: Int,
    val firstItemIndex: Int,
    val firstItemOffset: Int,
    val firstItemSize: Int,
    val lastItemIndex: Int,
    val lastItemOffset: Int,
    val lastItemSize: Int,
    val latestItemIndex: Int,
    val latestItemOffset: Int,
    val latestItemSize: Int,
    val canAnchor: Boolean
)

internal fun threadLatestItemBottomAnchorScrollOffset(
    itemSize: Int,
    viewportHeight: Int,
    visibleItemCount: Int,
    totalItemCount: Int
): Int {
    if (itemSize <= 0 || viewportHeight <= 0) return 0
    val rawOffset = itemSize - viewportHeight
    if (rawOffset >= 0) return rawOffset
    return 0
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
    SlockBrutalActionButton(
        label = "Back to bottom",
        onClick = onClick,
        modifier = modifier,
        backgroundColor = SlockColor.AccentCyan,
        minWidth = 0.dp,
        minHeight = 30.dp,
        shadowOffset = 4.dp,
        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
        trailingContent = {
            SlockLucideIcon(
                "chevron-down",
                modifier = Modifier.size(14.dp),
                color = "#FF000000"
            )
        }
    )
}

private const val ThreadBackToBottomTolerancePx = 24
private const val ThreadBottomAnchorMinimumAttempts = 2
private const val ThreadInitialBottomAnchorAttempts = 4
private const val ThreadLayoutStabilityFrameDelayMs = 16L
private const val ThreadFirstContentTaskJoinDelayMs = 16L
private const val ThreadLayoutStabilityMaxFrames = 30
private const val ThreadLayoutStabilityRequiredFrames = 3
private const val ThreadBackToBottomAnchorAttempts = 12
private const val ThreadKeyboardBottomAnchorAttempts = 8
private const val ThreadOlderAutoLoadVisibleItemThreshold = 1

@Composable
private fun ThreadSystemMessageRow(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String>,
    showReplyAction: Boolean,
    timestampLabel: String,
    resolvedTaskAssigneeName: String?,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)?,
    taskOpenEnabled: Boolean,
    taskStatusActionsEnabled: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
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
            text = if (expanded) message.expandedSystemSummary(timestampLabel) else message.collapsedSystemSummary(timestampLabel),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Medium).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
        )
        ThreadMessageFooterMeta(
            message = message,
            showReplyAction = showReplyAction,
            taskOpenEnabled = taskOpenEnabled,
            taskStatusActionsEnabled = taskStatusActionsEnabled,
            resolvedTaskAssigneeName = resolvedTaskAssigneeName,
            alignEnd = false,
            onOpenThread = onOpenThread,
            onOpenTask = onOpenTask,
            onShowActions = {},
            onOpenTaskStatusMenu = onOpenTaskStatusMenu
        )
    }
}

@Composable
private fun ThreadInlineActionRow(label: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 36.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(fontSize = 12.sp, color = SlockColor.TextBlack)
    )
}

@Composable
private fun ThreadHistoryStatusRow(
    label: String,
    iconName: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(14.dp),
            color = "#FF6B7280"
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun ThreadMessageCard(
    message: KuiklyThreadMessageItem,
    highlightedMentions: Set<String> = emptySet(),
    alignOwnMessageRight: Boolean = false,
    showReplyAction: Boolean = true,
    resolvedTaskAssigneeName: String? = null,
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    taskOpenEnabled: Boolean = true,
    taskStatusActionsEnabled: Boolean = true,
    showSaveAction: Boolean = true,
    timestampLabel: String = message.timestampLabel,
    onShowActions: (Offset) -> Unit,
    onOpenMember: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onToggleReaction: (String) -> Unit,
    onToggleSaved: () -> Unit,
    onOpenAttachment: (String) -> Unit,
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    pendingActionCardIds: Set<String> = emptySet(),
    onCommitActionCard: (String) -> Unit = {},
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    outerPadding: com.tencent.kuikly.compose.ui.unit.Dp = SlockSpacing.Sm
) {
    val actionCard = message.threadActionCard()
    val horizontalArrangement =
        if (alignOwnMessageRight) Arrangement.spacedBy(12.dp, Alignment.End) else Arrangement.spacedBy(12.dp)
    val contentAlignment = if (alignOwnMessageRight) Alignment.End else Alignment.Start
    val messageMaxWidthFraction = if (alignOwnMessageRight) 0.82f else 1f
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).padding(horizontal = outerPadding, vertical = SlockSpacing.Sm)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .padding(horizontal = SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
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
                        showStatus = message.showsAgentStatusIndicator(),
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .threadMessageLongPressActions(message.id, onShowActions),
                        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                        horizontalAlignment = contentAlignment
                    ) {
                        Button(
                            modifier =
                                Modifier
                                    .threadMessageContentWidth(messageMaxWidthFraction)
                                    .padding(end = if (showSaveAction) 30.dp else 0.dp)
                                    .background(Color.Transparent),
                            pressFeedback = false
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
                                    style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                                    color = SlockColor.TextBlack,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 220.dp).clickable { onOpenMember(message.authorId) }
                                )
                                Text(
                                    text = timestampLabel,
                                    style =
                                        SlockType.Caption.copy(
                                            fontFamily = FontFamily.Monospace,
                                            color = SlockColor.TextBlack.copy(alpha = 0.4f)
                                        ),
                                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (actionCard != null) {
                            ThreadActionCardView(
                                card = actionCard,
                                pending = message.id in pendingActionCardIds,
                                modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction),
                                onCommit = { onCommitActionCard(message.id) }
                            )
                        } else if (message.body.isNotBlank()) {
                            Button(
                                modifier =
                                    Modifier
                                        .threadMessageContentWidth(messageMaxWidthFraction)
                                        .background(Color.Transparent),
                                pressFeedback = false
                            ) {
                                SlockMarkdownBody(
                                    body = message.body,
                                    textStyle = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                                    highlightedMentions = highlightedMentions,
                                    resolveTagLabel = resolveMarkdownTagLabel,
                                    onOpenTag = onOpenMarkdownTag,
                                    onOpenUrl = onOpenMarkdownUrl
                                )
                            }
                        }
                        Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                            ThreadMessageAttachments(
                                attachments = message.attachments,
                                alignEnd = alignOwnMessageRight,
                                attachmentOpenStates = attachmentOpenStates,
                                onOpenAttachment = onOpenAttachment
                            )
                        }
                        Box(modifier = Modifier.threadMessageContentWidth(messageMaxWidthFraction)) {
                            ThreadMessageFooterMeta(
                                message = message,
                                showReplyAction = showReplyAction,
                                taskOpenEnabled = taskOpenEnabled,
                                taskStatusActionsEnabled = taskStatusActionsEnabled,
                                resolvedTaskAssigneeName = resolvedTaskAssigneeName,
                                alignEnd = alignOwnMessageRight,
                                onOpenThread = { onOpenThread(message.id) },
                                onOpenTask = onOpenTask,
                                onShowActions = onShowActions,
                                onOpenTaskStatusMenu = onOpenTaskStatusMenu,
                                onToggleReaction = onToggleReaction
                            )
                        }
                    }
                    if (showSaveAction) {
                        ThreadMessageBookmarkButton(
                            saved = message.isSaved,
                            onClick = onToggleSaved,
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
                if (alignOwnMessageRight) {
                    ThreadMessageAvatar(
                        avatarLabel = message.avatarLabel,
                        avatarWireValue = message.resolvedThreadAvatarWireValue(),
                        showStatus = message.showsAgentStatusIndicator(),
                        onClick = { onOpenMember(message.authorId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadMessageBookmarkButton(
    saved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        modifier =
            modifier
                .size(24.dp)
                .background(Color.Transparent),
        onClick = onClick,
        pressFeedback = false
    ) {
        SlockLucideIcon(
            name = "bookmark",
            modifier = Modifier.size(13.dp),
            color = if (saved) "#FFF8A16F" else "#66000000",
            fill = if (saved) "#FFF8A16F" else null
        )
    }
}

@Composable
private fun ThreadActionCardView(
    card: ThreadActionCard,
    pending: Boolean,
    modifier: Modifier = Modifier,
    onCommit: () -> Unit
) {
    Column(
        modifier =
            modifier
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = card.actionCardTitle(),
                style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (card.isExecuted) {
                ThreadActionCardDoneBadge()
            }
        }
        val details = card.actionCardDetails()
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        card.draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier =
                    Modifier.fillMaxWidth()
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                        .padding(SlockSpacing.Xs)
            )
        }
        if (card.isExecuted) {
            val committed = card.actionCardCommittedLabel()
            if (committed.isNotBlank()) {
                Text(
                    text = committed,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        } else {
            SlockBrutalActionButton(
                label = if (pending) card.actionCardPendingLabel() else card.actionCardVerb(),
                onClick = onCommit,
                enabled = !pending && card.isSupportedCommitAction(),
                backgroundColor = SlockColor.AccentPink,
                disabledBackgroundColor = SlockColor.SurfaceCream,
                minWidth = 0.dp,
                minHeight = 34.dp,
                shadowOffset = 2.dp,
                textStyle = SlockType.Button.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack)
            )
        }
    }
}

@Composable
private fun ThreadActionCardDoneBadge() {
    Row(
        modifier =
            Modifier
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .background(SlockColor.AccentLime)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SlockLucideIcon("check", modifier = Modifier.size(10.dp), color = "#FF000000")
        Text(
            text = "Done",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
}

private fun ThreadActionCard.actionCardVerb(): String =
    when (type) {
        "channel:create" -> "Create Channel"
        "agent:create" -> "Create Agent"
        "channel:add_member" -> "Add Members"
        else -> "Action"
    }

private fun ThreadActionCard.actionCardPendingLabel(): String =
    when (type) {
        "channel:add_member" -> "Adding..."
        else -> "Creating..."
    }

private fun ThreadActionCard.actionCardSuccessToast(): String =
    when (type) {
        "channel:create" -> "Channel created"
        "agent:create" -> "Agent created"
        "channel:add_member" -> "Members added"
        else -> "Action completed"
    }

private fun ThreadActionCard.actionCardFailureToast(): String =
    when (type) {
        "channel:create" -> "Unable to create channel"
        "agent:create" -> "Unable to create agent"
        "channel:add_member" -> "Unable to add members"
        else -> "Unable to complete action"
    }

private fun ThreadActionCard.actionCardTitle(): String =
    when (type) {
        "channel:create" -> "Create ${visibility ?: "public"} channel #$name"
        "agent:create" -> "Create Agent @${name.removePrefix("@")}"
        "channel:add_member" -> {
            val count = humans.size + agents.size
            "Add $count member${if (count == 1) "" else "s"} to ${channel?.let { "#${it.removePrefix("#")}" } ?: "channel"}"
        }
        else -> actionCardVerb()
    }

private fun ThreadActionCard.actionCardDetails(): String =
    buildList {
        description?.takeIf { it.isNotBlank() }?.let { add("Description: $it") }
        if (type.equals("agent:create", ignoreCase = true)) {
            requiredComputer?.takeIf { it.isNotBlank() }?.let { add("Required computer: $it") }
            if (requiredComputer.isNullOrBlank()) {
                suggestedComputer?.takeIf { it.isNotBlank() }?.let { add("Suggested computer: $it") }
            }
            if (description.isNullOrBlank()) add("No description prefilled.")
        }
        if (initialHumans.isNotEmpty()) add("Humans: ${initialHumans.joinToString(", ")}")
        if (initialAgents.isNotEmpty()) add("Agents: ${initialAgents.joinToString(", ")}")
        if (humans.isNotEmpty()) add("Humans: ${humans.joinToString(", ")}")
        if (agents.isNotEmpty()) add("Agents: ${agents.joinToString(", ")}")
    }.joinToString("\n")

private fun ThreadActionCard.actionCardCommittedLabel(): String =
    buildString {
        executedByUserName?.takeIf { it.isNotBlank() }?.let { append("Committed by $it") }
        val result = when (resultKind) {
            "agent" -> resultName?.let { "@${it.removePrefix("@")}" }
            "channel" -> resultName?.let { "#${it.removePrefix("#")}" }
            "channel-members" -> {
                val total = resultAddedHumanIds.size + resultAddedAgentIds.size
                resultChannelName?.let { "$total added to #${it.removePrefix("#")}" }
            }
            else -> null
        }
        if (!result.isNullOrBlank()) {
            if (isNotBlank()) append(" -> ") else append("Result: ")
            append(result)
        }
    }

private fun threadCreateAgentDialogErrorCopy(code: String): String =
    when (code) {
        "agent_name_required",
        "action_card_agent_name_missing" -> "Agent name is required."
        "action_card_agents_api_missing" -> "Create Agent is not available on this build."
        "action_card_actions_api_missing" -> "Action card completion is not available on this build."
        "action_card_mark_executed_empty",
        "action_mark_executed_contract_mismatch" -> "Agent was created, but the action card could not be marked done. Try refreshing this thread."
        "agent_create_empty" -> "Agent was not created."
        else -> "Unable to create agent: $code"
    }

@Composable
private fun ThreadReactionChip(
    reaction: KuiklyThreadReactionItem,
    onClick: (() -> Unit)? = null
) {
    val background = if (reaction.selected) SlockColor.AccentPink.copy(alpha = 0.2f) else Color(0xFFEEEDE6)
    val chipModifier = Modifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    val chipContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ThreadQuickReactionGlyph(reaction.emoji, modifier = Modifier.size(15.dp))
            if (reaction.count > 0) {
                Text(
                    text = reaction.count.toString(),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (onClick != null) {
        Button(onClick = onClick, pressFeedback = false) {
            Box(
                modifier = chipModifier,
                contentAlignment = Alignment.Center
            ) {
                chipContent()
            }
        }
    } else {
        Box(
            modifier = chipModifier,
            contentAlignment = Alignment.Center
        ) {
            chipContent()
        }
    }
}

@Composable
private fun ThreadReactionAddChip(onClick: (Offset) -> Unit) {
    Button(
        onClick2 = onClick,
        modifier = Modifier.background(Color.Transparent)
    ) {
        ThreadReactionAddChipChrome()
    }
}

@Composable
private fun ThreadReactionAddChipChrome() {
    Box(
        modifier = Modifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(SlockColor.EmptyStateGray)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "+",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack
        )
    }
}

private fun Modifier.threadMessageContentWidth(maxWidthFraction: Float): Modifier =
    widthIn(max = 680.dp).fillMaxWidth(maxWidthFraction)

@Composable
private fun Modifier.threadMessageLongPressActions(
    messageId: String,
    onShowActions: (Offset) -> Unit
): Modifier {
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { rootPos = it.positionInRoot() }
        .pointerInput(messageId) {
            detectTapGestures(
                onLongPress = { localOffset ->
                    onShowActions(Offset(rootPos.x + localOffset.x, rootPos.y + localOffset.y))
                }
            )
        }
}

internal fun KuiklyThreadMessageItem.resolvedThreadAvatarWireValue(): String? {
    val isAgent = authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()
    return avatarWireValueForAvatarSlot(
        kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
        id = authorId.ifBlank { authorName },
        avatarWireValue = avatarWireValue,
        gravatarHash = gravatarHash
    )
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
    resolvedTaskAssigneeName: String? = null,
    alignEnd: Boolean,
    onOpenThread: () -> Unit,
    onOpenTask: (String) -> Unit,
    onShowActions: (Offset) -> Unit,
    onOpenTaskStatusMenu: (Offset) -> Unit,
    onToggleReaction: ((String) -> Unit)? = null
) {
    val hasTask =
        !message.taskId.isNullOrBlank() ||
            message.taskNumber != null ||
            !message.taskStatus.isNullOrBlank() ||
            !message.taskAssigneeName.isNullOrBlank()
    val hasReplies = message.replyCount > 0
    val visibleReactions = message.reactions.filter { it.count > 0 }
    val hasReactions = visibleReactions.isNotEmpty()
    if (!hasTask && !hasReplies && !hasReactions) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
            if (alignEnd) Arrangement.spacedBy(6.dp, Alignment.End) else Arrangement.spacedBy(6.dp)
    ) {
        if (hasTask) {
            val label = threadMessageTaskMetaLabel(message, resolvedTaskAssigneeName) ?: "Task"
            val selectedStatus = message.taskStatus.threadTaskStatusOption()
            val taskChipClick: ((Offset) -> Unit)? =
                when {
                    taskStatusActionsEnabled -> onOpenTaskStatusMenu
                    taskOpenEnabled -> { _ -> message.taskId?.let(onOpenTask) }
                    else -> null
                }
            SlockAnchoredMetaChip(
                label = label,
                iconName = selectedStatus.icon,
                iconSize = selectedStatus.iconSize,
                color = selectedStatus.color,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = 260.dp),
                onClick = taskChipClick
            )
        }
        if (hasReplies) {
            val replyLabel = threadMessageRepliesMetaLabel(message)
            SlockAnchoredMetaChip(
                label = replyLabel,
                iconName = "message-square",
                iconSize = 12.dp,
                color = SlockColor.AccentCyan.copy(alpha = 0.2f),
                modifier = Modifier.widthIn(max = 220.dp),
                onClick = if (showReplyAction) { _ -> onOpenThread() } else null
            )
        }
        if (hasReactions) {
            visibleReactions.forEach { reaction ->
                ThreadReactionChip(
                    reaction = reaction,
                    onClick = onToggleReaction?.let { callback -> { callback(reaction.emoji) } }
                )
            }
        }
    }
}

internal fun threadMessageTaskMetaLabel(
    message: KuiklyThreadMessageItem,
    resolvedTaskAssigneeName: String? = null
): String? {
    val number = message.taskNumber?.let { "#$it" }
    val assignee =
        resolvedTaskAssigneeName
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
    if (number == null && assignee == null && message.taskStatus.isNullOrBlank() && message.taskId.isNullOrBlank()) {
        return null
    }
    return buildString {
        append(number ?: "Task")
        assignee?.let {
            append(" @")
            append(it)
        }
    }
}

internal fun String.isDisplaySafeTaskAssigneeName(assigneeId: String? = null): Boolean {
    val trimmed = trim().removePrefix("@")
    if (trimmed.isBlank()) return false
    if (trimmed.isUuidLike()) return false
    val normalizedId = assigneeId?.trim()?.removePrefix("@")
    return normalizedId == null || !trimmed.equals(normalizedId, ignoreCase = true) || !normalizedId.isUuidLike()
}

private fun String.isUuidLike(): Boolean {
    val trimmed = trim()
    if (trimmed.length != 36) return false
    val dashPositions = setOf(8, 13, 18, 23)
    return trimmed.withIndex().all { (index, char) ->
        if (index in dashPositions) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}

internal fun threadMessageRepliesMetaLabel(message: KuiklyThreadMessageItem): String =
    buildString {
        append(message.replyCount)
        append(if (message.replyCount == 1) " reply" else " replies")
        if (message.unreadReplyCount > 0) {
            append(" · ")
            append(message.unreadReplyCount)
            append(" new")
        }
    }

private data class ThreadTaskStatusOption(
    val key: String,
    val label: String,
    val icon: String,
    val color: Color,
    val iconSize: Dp = 10.dp
)

private val threadTaskStatusOptions =
    listOf(
        ThreadTaskStatusOption("Todo", "Todo", "circle", SlockColor.AccentOrange),
        ThreadTaskStatusOption("InProgress", "In progress", "play", SlockColor.AccentCyan),
        ThreadTaskStatusOption("InReview", "In review", "eye", SlockColor.AccentLavender),
        ThreadTaskStatusOption("Done", "Done", "circle-check-big", SlockColor.AccentLime),
        ThreadTaskStatusOption("Closed", "Closed", "ban", SlockColor.AccentStone)
    )

private val ThreadTaskStatusMenuItems: List<SlockBaseMenuItem> =
    threadTaskStatusOptions.map { option ->
        SlockBaseMenuItem(id = option.key, label = option.label)
    }

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

internal data class ThreadReactionToggleTarget(
    val message: KuiklyThreadMessageItem,
    val nextSelected: Boolean
)

internal fun KuiklyThreadScreenPayload.threadReactionToggleTarget(
    messageId: String,
    emoji: String
): ThreadReactionToggleTarget? {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return null
    val candidates =
        (messages + listOfNotNull(originMessage))
            .filter { message -> message.id == cleanMessageId && !message.isSystemMessage() }
    if (candidates.isEmpty()) return null
    val selectedCandidate =
        candidates.firstOrNull { message ->
            message.reactions.any { reaction -> reaction.emoji == cleanEmoji && reaction.selected }
        }
    val message = selectedCandidate ?: candidates.first()
    return ThreadReactionToggleTarget(
        message = message,
        nextSelected = selectedCandidate == null
    )
}

internal fun KuiklyThreadMessageItem.collapsedSystemSummary(timestamp: String): String =
    listOf(timestamp, body.sanitizeThreadSystemText())
        .filter { it.isNotBlank() }
        .joinToString(" ")

internal fun KuiklyThreadMessageItem.expandedSystemSummary(timestamp: String): String =
    listOf(collapsedSystemSummary(timestamp), expandedSystemDetails())
        .filter { it.isNotBlank() }
        .joinToString("\n")

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
private val ThreadQuickReactionEmojis = listOf("👍", "❤️", "🎉", "👀", "🔥", "😂")

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
    onReact: (String) -> Unit,
    onTaskStatusChange: (String) -> Unit,
    diagnosticsLogger: (String) -> Unit = {}
) {
    val actions =
        buildList {
            if (showReplyAction) {
                add(ThreadMessageMenuAction("Reply", "message-square", "message", onReply))
            }
            add(ThreadMessageMenuAction("Copy markdown", "copy", "copy", onCopy))
            if (showSaveAction) {
                add(
                    ThreadMessageMenuAction(
                        label = if (message.isSaved) "Remove from saved" else "Save message",
                        icon = "bookmark",
                        group = "message",
                        onClick = onSave
                    )
                )
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
    var menuSize by remember { mutableStateOf(Size.Zero) }
    val density = LocalDensity.current
    val menuWidth = menuSize.width.takeIf { it > 0f } ?: ThreadMessageMenuWidth.value
    val menuHeight = menuSize.height.takeIf { it > 0f } ?: ThreadMessageMenuFallbackHeight
    val contentSize = Size(
        width = menuWidth + 4f,
        height = menuHeight + 4f
    )
    SlockAnchoredPopover(
        anchor = anchor,
        contentSize = contentSize,
        onDismiss = onDismiss,
        viewportGap = ThreadMessageMenuAnchorGap,
        horizontalOffset = -ThreadMessageMenuHorizontalNudge,
        verticalOffset = ThreadMessageMenuAnchorGap,
        verticalPlacement = SlockFloatingVerticalPlacement.Auto
    ) { popoverModifier, position ->
        LaunchedEffect(message.id, anchor, position, menuSize) {
            diagnosticsLogger(
                "thread_message_menu_position messageId=${message.id} rootX=${anchor.x} rootY=${anchor.y} " +
                    "overlayX=${position.overlayLayout.origin.x} overlayY=${position.overlayLayout.origin.y} " +
                    "localX=${position.localAnchor.x} localY=${position.localAnchor.y} " +
                    "overlayW=${position.overlayLayout.size.width} overlayH=${position.overlayLayout.size.height} " +
                    "menuW=$menuWidth menuH=$menuHeight finalX=${position.offset.x} finalY=${position.offset.y}"
            )
        }
        Box(
            modifier = popoverModifier
                .padding(end = 4.dp, bottom = 4.dp)
                .width(ThreadMessageMenuWidth)
                .onGloballyPositioned { coordinates ->
                    menuSize = coordinates.size.toSlockDpSize(density)
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
                    .slockFloatingContentClickTarget()
            ) {
                ThreadMessageReactionRow(onReact = onReact)
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

@Composable
private fun ThreadMessageReactionRow(onReact: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = SlockSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ThreadQuickReactionEmojis.forEach { reaction ->
            Button(
                onClick = { onReact(reaction) },
                pressFeedback = false,
                modifier = Modifier
                    .size(28.dp)
            ) {
                ThreadQuickReactionGlyph(reaction, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ThreadQuickReactionGlyph(
    emoji: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val unit = size.width / 24f
        fun scaledSize(width: Float, height: Float) = Size(width * unit, height * unit)
        fun scaledOffset(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun path(block: Path.() -> Unit): Path = Path().apply(block)
        fun Path.m(x: Float, y: Float) = moveTo(x * unit, y * unit)
        fun Path.l(x: Float, y: Float) = lineTo(x * unit, y * unit)
        fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cubicTo(x1 * unit, y1 * unit, x2 * unit, y2 * unit, x3 * unit, y3 * unit)
        val ink = SlockColor.ShadowBlack
        val yellow = SlockColor.BrandYellow
        val cream = Color(0xFFFFF8E7)
        val blueGray = Color(0xFF8FC4D9)
        val red = SlockColor.AccentRed
        val cyan = SlockColor.AccentCyan
        val orange = SlockColor.AccentOrange
        val lavender = SlockColor.AccentLavender
        val stroke2 = Stroke(width = 2f * unit)
        val stroke15 = Stroke(width = 1.5f * unit)

        when (emoji) {
            "👍" -> {
                val hand = path {
                    m(8f, 10f)
                    l(11f, 10f)
                    l(12.3f, 4.8f)
                    c(12.5f, 4f, 13.3f, 3.5f, 14.1f, 3.7f)
                    c(15.1f, 3.9f, 15.7f, 4.9f, 15.4f, 5.9f)
                    l(14.5f, 10f)
                    l(19f, 10f)
                    c(20.1f, 10f, 21f, 10.9f, 21f, 12f)
                    l(21f, 13.2f)
                    c(21f, 13.6f, 20.9f, 14f, 20.7f, 14.3f)
                    l(18.8f, 20f)
                    c(18.5f, 20.6f, 17.8f, 21f, 17.1f, 21f)
                    l(8f, 21f)
                    l(8f, 10f)
                    close()
                }
                val highlight = path {
                    m(13.2f, 5.3f)
                    c(13.4f, 4.6f, 14.1f, 4.2f, 14.8f, 4.5f)
                    c(14.9f, 4.6f, 15f, 4.6f, 15.1f, 4.7f)
                    c(14.5f, 4.7f, 14.1f, 5.1f, 13.9f, 5.7f)
                    l(13f, 9f)
                    l(11.8f, 9f)
                    l(13.2f, 5.3f)
                    close()
                }
                drawPath(hand, yellow, style = Fill)
                drawPath(highlight, cream, style = Fill)
                drawRect(blueGray, topLeft = scaledOffset(3f, 10f), size = scaledSize(5f, 11f))
                drawPath(hand, ink, style = stroke2)
                drawRect(ink, topLeft = scaledOffset(3f, 10f), size = scaledSize(5f, 11f), style = stroke2)
            }
            "❤️", "❤" -> {
                val heart = path {
                    m(12f, 21f)
                    l(4.4f, 13.4f)
                    c(2.3f, 11.3f, 2.2f, 8f, 4.1f, 6.1f)
                    c(5.8f, 4.4f, 8.4f, 4.5f, 10f, 6.1f)
                    l(12f, 8.1f)
                    l(14f, 6.1f)
                    c(15.6f, 4.5f, 18.2f, 4.4f, 19.9f, 6.1f)
                    c(21.8f, 8f, 21.7f, 11.3f, 19.6f, 13.4f)
                    l(12f, 21f)
                    close()
                }
                val highlight = path {
                    m(6.1f, 7.2f)
                    c(6.9f, 6.4f, 8.1f, 6.4f, 9f, 7.1f)
                    l(6f, 10.1f)
                    c(5.5f, 9.2f, 5.6f, 7.9f, 6.1f, 7.2f)
                    close()
                }
                drawPath(heart, red, style = Fill)
                drawPath(highlight, cream, style = Fill)
                drawPath(heart, ink, style = stroke2)
            }
            "🎉" -> {
                val cone = path {
                    m(4f, 20f)
                    l(8.5f, 8.5f)
                    l(16f, 16f)
                    l(4f, 20f)
                    close()
                }
                val stripe = path {
                    m(6.8f, 12.2f)
                    l(11.8f, 17.2f)
                    l(8.3f, 18.3f)
                    l(5.7f, 15.4f)
                    close()
                }
                val confettiLines = path {
                    m(14f, 5f)
                    l(17f, 5f)
                    m(18f, 9f)
                    l(20.3f, 6.7f)
                    m(19f, 13f)
                    l(21.5f, 13f)
                }
                drawPath(cone, lavender, style = Fill)
                drawPath(stripe, yellow, style = Fill)
                drawPath(cone, ink, style = stroke2)
                drawPath(confettiLines, ink, style = stroke2)
                drawRect(red, topLeft = scaledOffset(18f, 3f), size = scaledSize(2.5f, 2.5f))
                drawRect(cyan, topLeft = scaledOffset(13f, 6f), size = scaledSize(2.5f, 2.5f))
            }
            "👀" -> {
                val leftEye = path {
                    m(3f, 11f)
                    c(3f, 7.9f, 5.2f, 6f, 7.8f, 6f)
                    c(10.4f, 6f, 12.6f, 7.9f, 12.6f, 11f)
                    c(12.6f, 14.1f, 10.4f, 16f, 7.8f, 16f)
                    c(5.2f, 16f, 3f, 14.1f, 3f, 11f)
                    close()
                }
                val rightEye = path {
                    m(11.4f, 11f)
                    c(11.4f, 7.9f, 13.6f, 6f, 16.2f, 6f)
                    c(18.8f, 6f, 21f, 7.9f, 21f, 11f)
                    c(21f, 14.1f, 18.8f, 16f, 16.2f, 16f)
                    c(13.6f, 16f, 11.4f, 14.1f, 11.4f, 11f)
                    close()
                }
                drawPath(leftEye, cyan, style = Fill)
                drawPath(rightEye, cyan, style = Fill)
                drawRect(cream, topLeft = scaledOffset(5.2f, 9.2f), size = scaledSize(2f, 2f))
                drawRect(cream, topLeft = scaledOffset(13.6f, 9.2f), size = scaledSize(2f, 2f))
                drawRect(ink, topLeft = scaledOffset(7.8f, 8.8f), size = scaledSize(2.8f, 4.6f))
                drawRect(ink, topLeft = scaledOffset(16.2f, 8.8f), size = scaledSize(2.8f, 4.6f))
                drawPath(leftEye, ink, style = stroke2)
                drawPath(rightEye, ink, style = stroke2)
            }
            "🔥" -> {
                val flame = path {
                    m(12f, 22f)
                    c(7.6f, 22f, 4.4f, 19f, 4.4f, 14.9f)
                    c(4.4f, 11.8f, 6.3f, 9.5f, 8.5f, 7.8f)
                    c(8.8f, 9.8f, 9.7f, 11f, 10.8f, 11.7f)
                    c(10.5f, 8.4f, 11.8f, 5.4f, 14.9f, 3f)
                    c(15.4f, 6f, 17f, 7.7f, 18.3f, 9.3f)
                    c(19.4f, 10.7f, 19.6f, 12.2f, 19.6f, 14.4f)
                    c(19.6f, 18.8f, 16.5f, 22f, 12f, 22f)
                    close()
                }
                val core = path {
                    m(12.1f, 19.8f)
                    c(9.9f, 19.8f, 8.3f, 18.4f, 8.3f, 16.3f)
                    c(8.3f, 14.8f, 9.2f, 13.6f, 10.4f, 12.7f)
                    c(10.6f, 13.7f, 11f, 14.4f, 11.7f, 14.7f)
                    c(11.8f, 12.9f, 12.5f, 11.5f, 14f, 10.3f)
                    c(14.3f, 11.8f, 15f, 12.7f, 15.7f, 13.5f)
                    c(16.3f, 14.2f, 16.5f, 14.9f, 16.5f, 16f)
                    c(16.5f, 18.3f, 14.8f, 19.8f, 12.1f, 19.8f)
                    close()
                }
                drawPath(flame, orange, style = Fill)
                drawPath(core, yellow, style = Fill)
                drawPath(flame, ink, style = stroke2)
                drawPath(core, ink, style = stroke15)
            }
            "😂" -> {
                val smile = path {
                    m(8f, 16f)
                    c(9f, 17.2f, 10.2f, 17.8f, 12f, 17.8f)
                    c(13.8f, 17.8f, 15f, 17.2f, 16f, 16f)
                }
                drawRect(yellow, topLeft = scaledOffset(4f, 5f), size = scaledSize(16f, 14f))
                drawRect(ink, topLeft = scaledOffset(7f, 9f), size = scaledSize(3.2f, 2.4f))
                drawRect(ink, topLeft = scaledOffset(13.8f, 9f), size = scaledSize(3.2f, 2.4f))
                drawRect(ink, topLeft = scaledOffset(8f, 14f), size = scaledSize(8f, 2f))
                drawRect(cyan, topLeft = scaledOffset(3.5f, 12f), size = scaledSize(3f, 5f))
                drawRect(cyan, topLeft = scaledOffset(17.5f, 12f), size = scaledSize(3f, 5f))
                drawRect(ink, topLeft = scaledOffset(4f, 5f), size = scaledSize(16f, 14f), style = stroke2)
                drawPath(smile, ink, style = stroke15)
            }
            else -> {
                drawCircle(yellow, radius = 9f * unit, center = scaledOffset(12f, 12f))
                drawCircle(ink, radius = 9f * unit, center = scaledOffset(12f, 12f), style = stroke2)
            }
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
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 14.sp, lineHeight = 18.sp),
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
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onThreadEvent: (action: String, itemId: String?, attachmentId: String?, tab: String?) -> Unit,
    onShowActions: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenTaskStatusMenu: (KuiklyThreadMessageItem, Offset) -> Unit,
    onOpenMarkdownTag: (ThreadMarkdownTag) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
    ) {
        when (payload.originStatus) {
            "loading" -> {
                Text(
                    text = "Refreshing original message...",
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
                )
            }
            "missing" -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
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
            }
            else -> {
                payload.originMessage?.let { origin ->
                    if (origin.isSystemMessage() && origin.threadActionCard() == null) {
                        ThreadSystemMessageRow(
                            message = origin,
                            highlightedMentions = highlightedMentions,
                            showReplyAction = false,
                            timestampLabel = payload.formatTimestampLabel(origin.timestampLabel),
                            resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                                value = origin.taskAssigneeName,
                                id = origin.taskAssigneeId
                            ),
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions,
                            taskStatusActionsEnabled = payload.showTaskStatusActions,
                            onOpenThread = { onThreadEvent("openMessageThread", origin.id, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl
                        )
                    } else {
                        ThreadMessageCard(
                            message = origin,
                            highlightedMentions = highlightedMentions,
                            showReplyAction = false,
                            resolvedTaskAssigneeName = payload.resolveTaskAssigneeDisplayName(
                                value = origin.taskAssigneeName,
                                id = origin.taskAssigneeId
                            ),
                            resolveMarkdownTagLabel = payload::resolveMarkdownTagDisplayLabel,
                            taskOpenEnabled = payload.showTaskOpenActions,
                            taskStatusActionsEnabled = payload.showTaskStatusActions,
                            showSaveAction = payload.showMessageSaveAction,
                            onShowActions = { anchor -> onShowActions(origin, anchor) },
                            onOpenMember = { onThreadEvent("openMember", it, null, null) },
                            onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                            onOpenTask = { onThreadEvent("openTask", it, null, null) },
                            onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                            onToggleReaction = { emoji -> onThreadEvent("toggleReaction", origin.id, null, emoji) },
                            onToggleSaved = { onThreadEvent("toggleMessageSaved", origin.id, null, null) },
                            timestampLabel = payload.formatTimestampLabel(origin.timestampLabel),
                            onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                            attachmentOpenStates = attachmentOpenStates,
                            pendingActionCardIds = pendingActionCardIds,
                            onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                            onOpenMarkdownTag = onOpenMarkdownTag,
                            onOpenMarkdownUrl = onOpenMarkdownUrl
                        )
                    }
                } ?: payload.originPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun ThreadMessageAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    showStatus: Boolean,
    onClick: () -> Unit
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (showStatus) SlockColor.AccentLime else null,
            avatarSize = 40.dp,
            statusSize = 10.dp
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 40.dp,
                borderWidth = SlockStroke.Default
            )
        }
    }
}

@Composable
private fun ThreadMessageStaticAvatar(
    avatarLabel: String,
    avatarWireValue: String?,
    showStatus: Boolean
) {
    val avatarSource =
        remember(avatarWireValue) {
            avatarWireValue?.takeIf { it.isNotBlank() }
        }
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatarWithStatus(
            statusColor = if (showStatus) SlockColor.AccentLime else null,
            avatarSize = 40.dp,
            statusSize = 10.dp
        ) {
            SlockAvatar(
                avatarWireValue = avatarSource,
                label = avatarLabel,
                avatarSize = 40.dp,
                borderWidth = SlockStroke.Default
            )
        }
    }
}

private fun KuiklyThreadMessageItem.showsAgentStatusIndicator(): Boolean =
    authorKind.equals("agent", ignoreCase = true) || authorId.isLikelyThreadAgentId()

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
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    onOpenAttachment: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    val imageAttachments = remember(attachments) { attachments.filter { it.isImageAttachment() } }
    val fileAttachments = remember(attachments) { attachments.filterNot { it.isImageAttachment() } }

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
                openState = attachmentOpenStates[attachment.id],
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
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
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
    openState: ThreadAttachmentOpenUiState?,
    onOpen: () -> Unit
) {
    val isOpening = openState.threadAttachmentIsOpening()
    Row(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = if (isOpening) ({}) else onOpen)
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
            openState.threadAttachmentStatusLabel()?.let { statusLabel ->
                Text(
                    text = statusLabel,
                    style = SlockType.Caption.copy(
                        color = if (isOpening) SlockColor.TextBlack else SlockColor.TextMutedGray
                    ),
                    color = if (isOpening) SlockColor.TextBlack else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThreadTasksTab(
    state: TasksUiState,
    channelId: ChannelId,
    openTaskEnabled: Boolean,
    onOpenTask: (String) -> Unit,
    onIntent: (SharedTasksRouteIntent) -> Unit
) {
    SharedTasksPanel(
        state = state,
        showHeader = false,
        showChannelFilter = false,
        createTasksChannelId = channelId,
        onOpenTask = if (openTaskEnabled) {
            { task -> onOpenTask(task.id.value) }
        } else {
            null
        },
        onIntent = onIntent
    )
}

private fun KuiklyThreadTaskItem.toTaskSummary(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        title = title,
        status = status.toSharedThreadTaskStatus() ?: TaskStatus.Todo,
        taskNumber = taskNumber,
        messageId = messageId?.takeIf { it.isNotBlank() }?.let(::MessageId),
        assignee =
            assigneeName?.trim()?.takeIf { it.isNotBlank() }?.let { displayName ->
                MemberProfile(
                    id = MemberId("thread-task-assignee-${displayName.threadTaskAssigneeKey()}"),
                    displayName = displayName,
                    handle = displayName,
                    kind = MemberKind.Human
                )
            },
        updatedAtLabel = updatedAtLabel
    )

private fun String.threadTaskAssigneeKey(): String =
    trim()
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '@' || it == '.' || it == '-' || it == '_' }
        .ifBlank { "unknown" }

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
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
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
    SlockUnifiedDialog(
        title = "MEMBERS (${membersCount.takeIf { it > 0 } ?: members.size})",
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 332.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockModalListShell(height = threadMemberListHeight(agents.size, humans.size).dp) {
                if (members.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = "NO MEMBERS",
                            detail = "REFRESH THIS CONVERSATION AND TRY AGAIN."
                        )
                    }
                } else {
                    if (agents.isNotEmpty()) {
                        item { SlockModalSectionHeader("AGENTS") }
                        items(agents.size, key = { agents[it].memberKey }) { index ->
                            val member = agents[index]
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                trailingIcon = "x",
                                onTrailingClick = { onManageMember(member.id) },
                                onClick = { onManageMember(member.id) }
                            )
                        }
                    }
                    if (humans.isNotEmpty()) {
                        item { SlockModalSectionHeader("HUMANS") }
                        items(humans.size, key = { humans[it].memberKey }) { index ->
                            val member = humans[index]
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                trailingIcon = "x",
                                onTrailingClick = { onManageMember(member.id) },
                                onClick = { onManageMember(member.id) }
                            )
                        }
                    }
                }
            }
            SlockModalFullWidthActionButton(
                label = "ADD MEMBER",
                icon = "user-plus",
                onClick = { if (canAddMembers) onAddMember() },
                color = SlockColor.AccentPink,
                enabled = canAddMembers
            )
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
    initialSelectedId: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (KuiklyThreadMemberItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember(candidates, initialSelectedId) { mutableStateOf(initialSelectedId) }
    var localError by remember { mutableStateOf(false) }
    val currentKeys = remember(members) { members.map { it.memberKey }.toSet() }
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
            blocked -> "YOU DO NOT HAVE PERMISSION TO ADD MEMBERS."
            localError -> "SELECT A MEMBER BEFORE ADDING."
            addMemberStatus == "failed" -> "UNABLE TO ADD MEMBER: ${(addMemberErrorCode ?: "member_add_failed").uppercase()}"
            else -> null
        }
    SlockUnifiedDialog(
        title = "ADD MEMBER",
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 332.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            SlockModalTextField(
                label = "SEARCH",
                value = query,
                placeholder = "NAME",
                leadingIcon = "search",
                enabled = !blocked,
                onValueChange = {
                    if (!blocked) {
                        query = it
                        selectedId = null
                        localError = false
                    }
                }
            )
            SlockModalListShell(height = threadMemberListHeight(agentCandidates.size, humanCandidates.size).dp) {
                if (filteredCandidates.isEmpty()) {
                    item {
                        ThreadModalEmptyState(
                            title = if (candidates.isEmpty()) "NO PEOPLE LOADED" else "NO MATCHING PEOPLE",
                            detail = if (candidates.isEmpty()) "REFRESH THIS CONVERSATION AND TRY AGAIN." else "TRY A DIFFERENT SEARCH."
                        )
                    }
                } else {
                    if (agentCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader("AGENTS") }
                        items(agentCandidates.size, key = { agentCandidates[it].memberKey }) { index ->
                            val member = agentCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (member.id == selectedId) "check" else null,
                                trailingText = if (alreadyJoined) "JOINED" else "",
                                onClick = {
                                    selectedId = member.id
                                    localError = false
                                    onSubmit(member)
                                }
                            )
                        }
                    }
                    if (humanCandidates.isNotEmpty()) {
                        item { SlockModalSectionHeader("HUMANS") }
                        items(humanCandidates.size, key = { humanCandidates[it].memberKey }) { index ->
                            val member = humanCandidates[index]
                            val alreadyJoined = member.memberKey in currentKeys
                            SlockModalCompactMemberRow(
                                title = member.displayName,
                                subtitle = member.roleLabel,
                                avatarWireValue = member.modalAvatarWireValue(),
                                avatarLabel = member.modalAvatarLabel(),
                                statusColor = member.modalStatusColor(),
                                selected = member.id == selectedId,
                                enabled = !blocked && !adding && !alreadyJoined,
                                avatarSize = 26.dp,
                                trailingIcon = if (member.id == selectedId) "check" else null,
                                trailingText = if (alreadyJoined) "JOINED" else "",
                                onClick = {
                                    selectedId = member.id
                                    localError = false
                                    onSubmit(member)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun threadMemberListHeight(agentCount: Int, humanCount: Int, maxHeight: Int = 288): Int {
    val sectionCount = listOf(agentCount, humanCount).count { it > 0 }
    val rowCount = agentCount + humanCount
    return (sectionCount * 30 + rowCount * 58 + 8).coerceIn(108, maxHeight)
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
                "UNABLE TO REMOVE MEMBER: ${(removeMemberErrorCode ?: "member_remove_failed").uppercase()}"
            removeMemberStatus == "no_permission" && removingMemberId == member.id ->
                "UNABLE TO REMOVE MEMBER: NO PERMISSION"
            else -> null
        }
    SlockUnifiedDialog(
        title = member.displayName,
        onDismiss = onDismiss,
        minWidth = 300.dp,
        maxWidth = 332.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val detailSubtitle = errorText ?: member.handle.takeIf { it.isNotBlank() }?.let { "@$it" }
            if (!detailSubtitle.isNullOrBlank()) {
                Text(
                    text = detailSubtitle,
                    style =
                        SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                            color = if (errorText != null) SlockColor.AccentRed else SlockColor.TextMutedGray
                        ),
                    color = if (errorText != null) SlockColor.AccentRed else SlockColor.TextMutedGray
                )
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SlockAvatarWithStatus(
                    statusColor = member.modalStatusColor(),
                    avatarSize = 46.dp,
                    statusSize = 10.dp
                ) {
                    SlockAvatar(
                        avatarWireValue = member.modalAvatarWireValue(),
                        label = member.modalAvatarLabel(),
                        avatarSize = 46.dp
                    )
                }
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
                        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
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
                SlockModalActionButton(label = "BACK", icon = "arrow-left", onClick = onBack)
                SlockModalActionButton(label = "OPEN PROFILE", icon = "user", onClick = onOpenProfile)
                if (member.kind.equals("agent", ignoreCase = true)) {
                    SlockModalActionButton(
                        label = if (isRemoving) "REMOVING" else "REMOVE AGENT",
                        icon = "user-minus",
                        color = SlockColor.AccentRed,
                        onClick = { if (!isRemoving) onRemoveMember() }
                    )
                }
            }
        }
    }
}

private val KuiklyThreadMemberItem.memberKey: String
    get() = "${kind.lowercase()}:$id"

private fun KuiklyThreadMemberItem.modalAvatarWireValue(): String? =
    avatarUrl?.takeIf { it.isNotBlank() }

private fun KuiklyThreadMemberItem.modalAvatarLabel(): String =
    avatarLabel.ifBlank { displayName.ifBlank { handle.ifBlank { id } } }

private fun KuiklyThreadMemberItem.modalStatusColor(): Color? =
    if (kind.equals("agent", ignoreCase = true)) SlockColor.AccentLime else null

private enum class ThreadSettingsDangerAction(
    val id: String,
    val title: String,
    val icon: String,
    val buttonLabel: String,
    val color: Color
) {
    Visibility("visibility", "MAKE CHANNEL PRIVATE", "lock", "Make Private", SlockColor.AccentOrange),
    Archive("archive", "ARCHIVE CHANNEL", "archive", "Archive Channel", SlockColor.AccentOrange),
    Delete("delete", "DELETE CHANNEL", "trash", "Delete Channel", SlockColor.AccentRed)
}

internal fun defaultThreadSettingsChannelActions(): List<KuiklyThreadSettingsAction> =
    listOf(
        KuiklyThreadSettingsAction(
            id = "visibility",
            title = "Make Private",
            subtitle = "Restrict this channel to selected members",
            icon = "lock",
            destructive = true
        ),
        KuiklyThreadSettingsAction(
            id = "archive",
            title = "Archive Channel",
            subtitle = "Freeze writes and hide from sidebars",
            icon = "archive",
            destructive = true
        ),
        KuiklyThreadSettingsAction(
            id = "delete",
            title = "Delete Channel",
            subtitle = "Permanently remove this channel",
            icon = "trash",
            destructive = true
        )
    )

internal fun List<KuiklyThreadSettingsAction>.normalizedThreadSettingsChannelActions(): List<KuiklyThreadSettingsAction> {
    val defaults = defaultThreadSettingsChannelActions()
    val defaultById = defaults.associateBy { it.id }
    val channelActions =
        mapNotNull { action ->
            when (action.id) {
                "visibility",
                "make-private",
                "make_public" -> action.copy(
                    id = "visibility",
                    title = action.title.ifBlank { defaultById.getValue("visibility").title },
                    icon = action.icon.ifBlank { defaultById.getValue("visibility").icon },
                    destructive = true
                )
                "archive" -> action.copy(
                    id = "archive",
                    title = action.title.ifBlank { defaultById.getValue("archive").title },
                    icon = action.icon.ifBlank { defaultById.getValue("archive").icon },
                    destructive = true
                )
                "delete" -> action.copy(
                    id = "delete",
                    title = action.title.ifBlank { defaultById.getValue("delete").title },
                    icon = action.icon.ifBlank { defaultById.getValue("delete").icon },
                    destructive = true
                )
                else -> null
            }
        }
    if (channelActions.isEmpty()) return defaults
    val presentIds = channelActions.map { it.id }.toSet()
    return channelActions + defaults.filter { it.id !in presentIds }
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
    val effectiveActions = actions.normalizedThreadSettingsChannelActions()
    val dangerActions = effectiveActions.mapNotNull { action ->
        when (action.id) {
            "visibility",
            "make-private",
            "make_public" -> ThreadSettingsDangerAction.Visibility
            "archive" -> ThreadSettingsDangerAction.Archive
            "delete" -> ThreadSettingsDangerAction.Delete
            else -> null
        }?.let { it to action }
    }
    val activeDanger = dangerAction
    val archiveConfirmationMatches = confirmationName.trim() == channelName.trim()
    SlockUnifiedDialog(
        title = activeDanger?.title ?: "EDIT CHANNEL",
        onDismiss = onDismiss
    ) {
        if (activeDanger == null) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SlockBrutalTextField(
                    label = "Name",
                    value = name,
                    placeholder = "e.g. ai-research",
                    onValueChange = { name = it },
                    required = true,
                    textStyle = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
                )
                SlockBrutalTextField(
                    label = "Description",
                    value = description,
                    placeholder = "What is this channel about?",
                    onValueChange = { description = it },
                    optional = true,
                    fieldMinHeight = 66.dp,
                    maxLines = 2
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    SlockModalActionButton(label = "Cancel", icon = "x", onClick = onDismiss)
                    SlockModalActionButton(
                        label = "Save Changes",
                        icon = "check",
                        color = SlockColor.AccentPink,
                        onClick = onDismiss
                    )
                }
                if (dangerActions.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(SlockColor.BorderBlack))
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        dangerActions.forEach { (danger, action) ->
                            SlockModalFullWidthActionButton(
                                label = action.title,
                                icon = action.icon.ifBlank { danger.icon },
                                color = danger.color,
                                onClick = { dangerAction = danger }
                            )
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (activeDanger) {
                    ThreadSettingsDangerAction.Visibility -> {
                        Text(
                            text = "Update #$channelName visibility?",
                            style = SlockType.Body,
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = "This changes who can find and join the channel.",
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextMutedGray
                        )
                    }
                    ThreadSettingsDangerAction.Archive,
                    ThreadSettingsDangerAction.Delete -> {
                        if (activeDanger == ThreadSettingsDangerAction.Delete) {
                            ThreadDeleteChannelWarning(channelName)
                        } else {
                            Text(
                                text = "Archive #$channelName? Members keep read access and history, but writes will be frozen and the channel will be hidden from sidebars.",
                                style = SlockType.Body,
                                color = SlockColor.TextBlack
                            )
                            Text(
                                text = "Type $channelName to confirm.",
                                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                                color = SlockColor.TextMutedGray
                            )
                            SlockBrutalTextField(
                                label = "Channel name",
                                value = confirmationName,
                                placeholder = channelName,
                                onValueChange = { confirmationName = it },
                                required = true,
                                fieldMinHeight = 42.dp
                            )
                            if (confirmationName.isNotBlank() && !archiveConfirmationMatches) {
                                Text(
                                    text = "Channel name does not match.",
                                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                                    color = SlockColor.AccentRed
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isDelete = activeDanger == ThreadSettingsDangerAction.Delete
                    SlockModalActionButton(
                        label = if (isDelete) "Cancel" else "Back",
                        icon = if (isDelete) "x" else "arrow-left",
                        onClick = { dangerAction = null }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val enabled =
                        activeDanger == ThreadSettingsDangerAction.Visibility ||
                            activeDanger == ThreadSettingsDangerAction.Delete ||
                            archiveConfirmationMatches
                    SlockModalActionButton(
                        label = activeDanger.buttonLabel,
                        icon = activeDanger.icon,
                        color = if (enabled) activeDanger.color else SlockColor.SurfaceCream,
                        enabled = enabled,
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
private fun ThreadDeleteChannelWarning(channelName: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.AccentRed.copy(alpha = 0.22f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            name = "triangle-alert",
            modifier = Modifier.size(26.dp),
            color = "#FF000000"
        )
        Text(
            text = "Are you sure you want to delete \"$channelName\"?\nAll messages will be lost.",
            style = SlockType.Body,
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
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
            style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
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
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
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
                style = SlockType.Body.withSlockFontWeight(FontWeight.W600).copy(color = SlockColor.TextBlack),
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
