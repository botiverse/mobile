package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.markdown.traceMarkdownSection
import build.raft.app.markdown.traceMarkdownSectionNonComposable
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockCheckbox
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.data.formatAttachmentSizeLabel
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.focus.FocusRequester
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalSoftwareKeyboardController
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

private const val THREAD_COMPOSER_PAGE_DATA_EVENT = "slockPageData"
private val ThreadComposerStandaloneTopPadding = 10.dp
private val ThreadComposerStandaloneBottomPadding = 8.dp
private val ThreadComposerHostKeyboardBasePadding = 12.dp
private val ThreadComposerVisualInputWidth = 321.dp
private val ThreadComposerTextFieldMinHeight = 20.dp
private val ThreadComposerTextFieldMaxHeight = 80.dp
private val ThreadComposerToolbarMinHeight = 28.dp
private val ThreadComposerIconButtonSize = 26.dp
private val ThreadComposerIconSize = 14.dp
private val ThreadComposerActionShadowOffset = 2.dp
private val ThreadComposerLeftActionGroupSpacing = 6.dp
private const val ThreadComposerAttachmentTrayMaxWidthDp = 321
private const val ThreadComposerAttachmentImageTileSizeDp = 64
private const val ThreadComposerAttachmentFileTileWidthDp = 128
private const val ThreadComposerAttachmentRowGapDp = 8
private const val ThreadComposerMentionActionNoticeDurationMillis = 1800L
private const val ThreadComposerSuggestionQueryDebounceMillis = 24L
private val ThreadComposerAttachmentTileSize = 64.dp
private val ThreadComposerAttachmentCloseSize = 22.dp
private val ThreadComposerDisabledSendBackground = Color(0xFFFFD8E5)
private val ThreadComposerDisabledSendBorder = Color(0xFFB3B3B3)
private val ThreadComposerDisabledSendShadow = Color(0xFFB8B8B8)
private const val ThreadComposerDisabledSendIconArgb = "#FFB3B3B3"
private val ComposerSuggestionPanelMaxHeight = 240.dp
private const val ComposerSuggestionPanelZIndex = 10f
private val ComposerSuggestionHeaderEstimatedHeight = 30.dp
private val ComposerMemberSuggestionRowMinHeight = 38.dp
private val ComposerChannelSuggestionRowMinHeight = 34.dp
private val ComposerSuggestionStatusRowMinHeight = 42.dp
private val ComposerMemberSuggestionAvatarSize = 20.dp
private val ComposerMemberSuggestionAvatarNameGap = 8.dp
private val ComposerAgentActivityDotSize = 10.dp
private val ComposerChannelSuggestionIconSize = 20.dp
private val ComposerChannelSuggestionGlyphSize = 12.dp
private val ThreadComposerVisualComponentWidth = 342.dp
private val ThreadComposerVisualComponentMinHeight = 100.dp
private const val ThreadComposerVisualPreviewUri =
    "data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%20128%20128%22%3E%3Crect%20width%3D%22128%22%20height%3D%22128%22%20fill%3D%22%23FDE047%22%2F%3E%3Ccircle%20cx%3D%2288%22%20cy%3D%2240%22%20r%3D%2220%22%20fill%3D%22%23F472B6%22%2F%3E%3Cpath%20d%3D%22M16%20104L48%2068l22%2024%2018-16%2024%2028H16z%22%20fill%3D%22%23000000%22%2F%3E%3C%2Fsvg%3E"
private val threadComposerJson = Json { ignoreUnknownKeys = true }
private val threadComposerContentPayloadKeys =
    setOf(
        "placeholder",
        "enabled",
        "attachmentActionsEnabled",
        "showAsTaskToggle",
        "composerMode",
        "statusLabel",
        "pendingAttachments",
        "pendingMentionActions",
        "mentionActionNotice",
        "mentionActionStates",
        "removingMentionActionIds",
        "executingMentionActionStates",
        "memberSuggestions",
        "channelSuggestions",
        "hookLabels"
    )

@Serializable
internal data class KuiklyThreadComposerPayload(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val placeholder: String = "Message",
    val enabled: Boolean = true,
    val attachmentActionsEnabled: Boolean = true,
    val showAsTaskToggle: Boolean = true,
    val composerMode: String = "idle",
    val statusLabel: String? = null,
    val pendingAttachments: List<KuiklyPendingAttachmentItem> = emptyList(),
    val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList(),
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionStates: Map<String, String> = emptyMap(),
    val memberSuggestions: List<KuiklyComposerMemberItem> = emptyList(),
    val channelSuggestions: List<KuiklyComposerChannelItem> = emptyList(),
    val suggestionsOpen: Boolean = false,
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
    val hookLabels: List<String> = emptyList()
)

@Serializable
internal data class WireThreadComposerLaunchPayload(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null
)

internal data class ThreadComposerSurface(
    val kind: String = KuiklyThreadSurfaceKind.Channel,
    val channelId: String,
    val streamChannelId: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val mentionChannelId: String? = null,
    val mentionChannelLabel: String? = null,
    val memberSuggestionHeadersVisible: Boolean = kind != KuiklyThreadSurfaceKind.DirectMessage,
    val showTaskButton: Boolean = true
) {
    val sendChannelId: String?
        get() = streamChannelId?.takeIf { it.isNotBlank() } ?: channelId.takeIf { it.isNotBlank() }

    val mentionScopeChannelId: String?
        get() =
            mentionChannelId?.takeIf { it.isNotBlank() }
                ?: parentChannelId?.takeIf { it.isNotBlank() }
                ?: streamChannelId?.takeIf { it.isNotBlank() }
                ?: channelId.takeIf { it.isNotBlank() }
}

internal fun ThreadComposerSurface.threadComposerDraftKey(): String? {
    val sendTarget = sendChannelId?.takeIf { it.isNotBlank() } ?: return null
    return listOf(
        "send" to sendTarget,
        "parent" to parentMessageId.orEmpty(),
        "mention" to mentionScopeChannelId.orEmpty()
    ).joinToString(separator = "|") { (name, value) ->
        "$name=${value.threadComposerDraftKeyPart()}"
    }
}

private fun String.threadComposerDraftKeyPart(): String =
    if (isBlank()) {
        "_"
    } else {
        replace("%", "%25")
            .replace("|", "%7C")
            .replace("=", "%3D")
    }

internal data class ThreadComposerUiState(
    val instanceId: String = "",
    val seedText: String = "",
    val seedAsTask: Boolean = false,
    val syncNonce: Int = 0,
    val focusRequestNonce: Int = 0,
    val placeholder: String = "Message",
    val enabled: Boolean = true,
    val attachmentActionsEnabled: Boolean = true,
    val composerMode: String = "idle",
    val statusLabel: String? = null,
    val pendingAttachments: List<KuiklyPendingAttachmentItem> = emptyList(),
    val pendingMentionActions: List<KuiklyPendingMentionActionItem> = emptyList(),
    val mentionActionNotice: String? = null,
    val mentionActionStates: Map<String, String> = emptyMap(),
    val removingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionIds: List<String> = emptyList(),
    val executingMentionActionStates: Map<String, String> = emptyMap(),
    val memberSuggestions: List<KuiklyComposerMemberItem> = emptyList(),
    val memberSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val memberSuggestionsErrorCode: String? = null,
    val channelSuggestions: List<KuiklyComposerChannelItem> = emptyList(),
    val channelSuggestionsStatus: SharedMembersLoadStatus = SharedMembersLoadStatus.Idle,
    val channelSuggestionsErrorCode: String? = null,
    val suggestionsEnabled: Boolean = true,
    val suggestionsOpen: Boolean = false,
    val hookLabels: List<String> = emptyList()
)

internal data class ThreadComposerInsertMentionRequest(
    val nonce: Int,
    val handle: String
)

internal enum class ThreadComposerSuggestionPanelState {
    Hidden,
    MemberItems,
    MemberLoading,
    MemberEmpty,
    MemberError,
    ChannelItems,
    ChannelLoading,
    ChannelEmpty,
    ChannelError
}

internal enum class ThreadComposerSuggestionRefreshRequest {
    Member,
    Channel
}

internal enum class ThreadComposerEnterAction {
    AcceptMemberSuggestion,
    AcceptChannelSuggestion,
    CommitSuggestionToken,
    Send,
    Noop
}

internal enum class ThreadComposerSuggestionSelectionMove {
    Up,
    Down
}

internal fun threadComposerSuggestionSelectedIndex(
    selectedIndex: Int,
    itemCount: Int
): Int =
    if (itemCount <= 0) {
        0
    } else {
        selectedIndex.coerceIn(0, itemCount - 1)
    }

internal fun threadComposerSuggestionSelectedIndexAfterMove(
    selectedIndex: Int,
    itemCount: Int,
    move: ThreadComposerSuggestionSelectionMove
): Int {
    if (itemCount <= 0) return 0
    val current = threadComposerSuggestionSelectedIndex(selectedIndex, itemCount)
    return when (move) {
        ThreadComposerSuggestionSelectionMove.Up ->
            if (current == 0) itemCount - 1 else current - 1
        ThreadComposerSuggestionSelectionMove.Down ->
            if (current == itemCount - 1) 0 else current + 1
    }
}

internal fun threadComposerEnterAction(
    trigger: KuiklyComposerSuggestionTrigger,
    panelState: ThreadComposerSuggestionPanelState,
    memberCount: Int,
    channelCount: Int,
    canSend: Boolean,
    isImeComposing: Boolean
): ThreadComposerEnterAction {
    if (isImeComposing) return ThreadComposerEnterAction.Noop
    return when (trigger) {
        is KuiklyComposerSuggestionTrigger.MemberTrigger ->
            when (panelState) {
                ThreadComposerSuggestionPanelState.MemberItems ->
                    if (memberCount > 0) ThreadComposerEnterAction.AcceptMemberSuggestion else ThreadComposerEnterAction.CommitSuggestionToken
                ThreadComposerSuggestionPanelState.MemberLoading,
                ThreadComposerSuggestionPanelState.MemberEmpty,
                ThreadComposerSuggestionPanelState.MemberError ->
                    ThreadComposerEnterAction.CommitSuggestionToken
                else ->
                    if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
            }

        is KuiklyComposerSuggestionTrigger.ChannelTrigger ->
            when (panelState) {
                ThreadComposerSuggestionPanelState.ChannelItems ->
                    if (channelCount > 0) ThreadComposerEnterAction.AcceptChannelSuggestion else ThreadComposerEnterAction.CommitSuggestionToken
                ThreadComposerSuggestionPanelState.ChannelLoading,
                ThreadComposerSuggestionPanelState.ChannelEmpty,
                ThreadComposerSuggestionPanelState.ChannelError ->
                    ThreadComposerEnterAction.CommitSuggestionToken
                else ->
                    if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
            }

        KuiklyComposerSuggestionTrigger.None ->
            if (canSend) ThreadComposerEnterAction.Send else ThreadComposerEnterAction.Noop
    }
}

internal fun threadComposerSuggestionRefreshRequest(
    focused: Boolean,
    trigger: KuiklyComposerSuggestionTrigger
): ThreadComposerSuggestionRefreshRequest? {
    if (!focused) return null
    return when (trigger) {
        is KuiklyComposerSuggestionTrigger.MemberTrigger -> ThreadComposerSuggestionRefreshRequest.Member
        is KuiklyComposerSuggestionTrigger.ChannelTrigger -> ThreadComposerSuggestionRefreshRequest.Channel
        KuiklyComposerSuggestionTrigger.None -> null
    }
}

internal fun threadComposerEffectiveSuggestionStatus(
    triggerActive: Boolean,
    rankingReady: Boolean,
    usesImmediateSuggestions: Boolean,
    sourceStatus: SharedMembersLoadStatus
): SharedMembersLoadStatus {
    if (!triggerActive || rankingReady || usesImmediateSuggestions) return sourceStatus
    return when (sourceStatus) {
        SharedMembersLoadStatus.InFlight -> SharedMembersLoadStatus.InFlight
        else -> sourceStatus
    }
}

internal fun threadComposerSuggestionPanelState(
    focused: Boolean,
    trigger: KuiklyComposerSuggestionTrigger,
    memberCount: Int,
    memberStatus: SharedMembersLoadStatus,
    channelCount: Int,
    channelStatus: SharedMembersLoadStatus
): ThreadComposerSuggestionPanelState {
    if (!focused) return ThreadComposerSuggestionPanelState.Hidden
    return when (trigger) {
        is KuiklyComposerSuggestionTrigger.MemberTrigger ->
            when {
                memberCount > 0 -> ThreadComposerSuggestionPanelState.MemberItems
                memberStatus == SharedMembersLoadStatus.Error -> ThreadComposerSuggestionPanelState.MemberError
                memberStatus == SharedMembersLoadStatus.InFlight -> ThreadComposerSuggestionPanelState.MemberLoading
                memberStatus == SharedMembersLoadStatus.Loaded -> ThreadComposerSuggestionPanelState.MemberEmpty
                else -> ThreadComposerSuggestionPanelState.MemberEmpty
            }

        is KuiklyComposerSuggestionTrigger.ChannelTrigger ->
            when {
                channelCount > 0 -> ThreadComposerSuggestionPanelState.ChannelItems
                channelStatus == SharedMembersLoadStatus.Error -> ThreadComposerSuggestionPanelState.ChannelError
                channelStatus == SharedMembersLoadStatus.InFlight -> ThreadComposerSuggestionPanelState.ChannelLoading
                channelStatus == SharedMembersLoadStatus.Loaded -> ThreadComposerSuggestionPanelState.ChannelEmpty
                else -> ThreadComposerSuggestionPanelState.ChannelEmpty
            }

        KuiklyComposerSuggestionTrigger.None -> ThreadComposerSuggestionPanelState.Hidden
    }
}

internal fun <T> threadComposerRenderedSuggestions(
    loadedSuggestions: List<T>,
    retainedSuggestions: List<T>,
    fallbackSuggestions: List<T>,
    status: SharedMembersLoadStatus
): List<T> =
    loadedSuggestions.takeIf { it.isNotEmpty() || status == SharedMembersLoadStatus.Loaded }
        ?: retainedSuggestions.takeIf { it.isNotEmpty() }
        ?: fallbackSuggestions

@Serializable
internal data class KuiklyPendingAttachmentItem(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val previewUri: String? = null,
    val status: String = "ready",
    val uploadProgress: Int = 0,
    val errorCode: String? = null
)

@Serializable
internal data class KuiklyPendingMentionActionItem(
    val resolutionId: String,
    val messageId: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val targetAvatarUrl: String? = null,
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
internal data class KuiklyComposerMemberItem(
    val displayName: String,
    val handle: String,
    val inMentionChannel: Boolean = true,
    val inThread: Boolean = false,
    val sourceServerId: String? = null,
    val isLocalServer: Boolean = true,
    val sourceServerLabel: String? = null,
    val kind: MemberKind = MemberKind.Human,
    val description: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = displayName.take(1).uppercase(),
    val memberId: String? = null
)

@Serializable
internal data class KuiklyComposerChannelItem(
    val name: String,
    val subtitle: String? = null
)

data class SharedThreadComposerSnapshot(
    val value: String = "",
    val asTask: Boolean = false
)

data class SharedThreadComposerFlowState(
    val hostDraftText: String,
    val liveDraftText: String,
    val payloadTextSeed: String,
    val hostDraftAsTask: Boolean,
    val liveDraftAsTask: Boolean,
    val payloadAsTaskSeed: Boolean,
    val syncNonce: Int = 0
) {
    companion object {
        fun initial(snapshot: SharedThreadComposerSnapshot): SharedThreadComposerFlowState =
            SharedThreadComposerFlowState(
                hostDraftText = snapshot.value,
                liveDraftText = snapshot.value,
                payloadTextSeed = snapshot.value,
                hostDraftAsTask = snapshot.asTask,
                liveDraftAsTask = snapshot.asTask,
                payloadAsTaskSeed = snapshot.asTask
            )
    }

    fun applyHostSnapshot(snapshot: SharedThreadComposerSnapshot): SharedThreadComposerFlowState =
        if (snapshot.value != hostDraftText || snapshot.asTask != hostDraftAsTask) {
            copy(
                hostDraftText = snapshot.value,
                liveDraftText = snapshot.value,
                payloadTextSeed = snapshot.value,
                hostDraftAsTask = snapshot.asTask,
                liveDraftAsTask = snapshot.asTask,
                payloadAsTaskSeed = snapshot.asTask,
                syncNonce = syncNonce + 1
            )
        } else {
            this
        }

    fun applyLocalChange(value: String, asTask: Boolean = liveDraftAsTask): SharedThreadComposerFlowState =
        copy(liveDraftText = value, liveDraftAsTask = asTask)
}

@Serializable
data class SharedThreadComposerAttachment(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val previewUri: String? = null,
    val status: String = "ready",
    val errorCode: String? = null
)

/** Composer UI formatting boundary; raw bytes win over old persisted display labels. */
internal fun KuiklyPendingAttachmentItem.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let(::formatAttachmentSizeLabel) ?: sizeLabel

internal fun SharedThreadComposerAttachment.displaySizeLabel(): String =
    sizeBytes.takeIf { it > 0L }?.let(::formatAttachmentSizeLabel) ?: sizeLabel

data class SharedThreadComposerMemberSuggestion(
    val displayName: String,
    val handle: String
)

data class SharedThreadComposerChannelSuggestion(
    val name: String,
    val subtitle: String? = null
)

internal sealed class KuiklyComposerSuggestionTrigger {
    data class MemberTrigger(val query: String, val triggerStart: Int) : KuiklyComposerSuggestionTrigger()
    data class ChannelTrigger(val query: String, val triggerStart: Int) : KuiklyComposerSuggestionTrigger()
    object None : KuiklyComposerSuggestionTrigger()
}

@Composable
internal fun ThreadComposerScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    var payloadText by remember { mutableStateOf(params.optString("payload")) }
    var instanceId by remember { mutableStateOf(params.optString("instanceId")) }
    var visualFixture by remember { mutableStateOf(params.optString("visualFixture")) }
    androidx.compose.runtime.DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != THREAD_COMPOSER_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                    eventData.optString("visualFixture").takeIf { it.isNotBlank() }?.let {
                        visualFixture = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payloadTrace =
        rememberThreadComposerPayloadTrace(
            payload = payloadText,
            fallbackPageData = params.toString(),
            instanceId = instanceId,
            visualFixture = visualFixture
        )
    val payload = payloadTrace.payload
    LaunchedEffect(payloadText, instanceId, visualFixture) {
        bridge.log(
            "thread_composer_launch_payload_parse source=${payloadTrace.source} result=${payloadTrace.result} " +
                "responseBytes=${payloadTrace.responseBytes} durationMs=${payloadTrace.durationMs} dispatcher=caller"
        )
    }
    LaunchedEffect(payload.instanceId, payload.syncNonce) {
        bridge.log(
            "thread_composer_render instanceId=${payload.instanceId} syncNonce=${payload.syncNonce} attachments=${payload.pendingAttachments.size} mode=${payload.composerMode}"
        )
    }
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(modifier = Modifier.width(ThreadComposerVisualComponentWidth).heightIn(min = ThreadComposerVisualComponentMinHeight)) {
            Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
            Box(
                modifier = Modifier
                    .width(ThreadComposerVisualInputWidth)
                    .offset(x = 0f, y = 14f),
                contentAlignment = Alignment.TopCenter
            ) {
                ThreadComposerContent(
                    payload = payload,
                    onEvent = { action, _, _, attachmentId ->
                        bridge.log(
                            "thread_composer_visual_event instanceId=${payload.instanceId} action=$action attachmentId=${attachmentId.orEmpty()}"
                        )
                    },
                    contentPaddingTop = 0.dp,
                    contentPaddingBottom = ThreadComposerStandaloneBottomPadding
                )
            }
        }
    }
}

@Composable
private fun rememberThreadComposerPayloadTrace(
    payload: String,
    fallbackPageData: String,
    instanceId: String,
    visualFixture: String
): ThreadComposerPayloadParseTrace =
    remember(payload, fallbackPageData, instanceId, visualFixture) {
        visualFixture.takeIf { it.isNotBlank() }?.let { fixture ->
            ThreadComposerPayloadParseTrace(
                payload = threadComposerVisualFixture(fixture, instanceId),
                durationMs = 0,
                responseBytes = fixture.encodeToByteArray().size,
                result = "visual_fixture",
                source = "visualFixture"
            )
        } ?: parseThreadComposerPayloadWithTrace(
            payload = payload,
            fallbackPageData = fallbackPageData,
            instanceId = instanceId
        )
    }

internal fun parseThreadComposerPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklyThreadComposerPayload {
    val primaryPayload = payload.trim()
    if (primaryPayload.isEmpty()) {
        return parseThreadComposerLaunchPayload(
            source = fallbackPageData,
            instanceId = instanceId,
            rejectAsFailure = true
        )
    }
    return parseThreadComposerLaunchPayload(
        source = primaryPayload,
        instanceId = instanceId,
        rejectAsFailure = true
    )
}

private fun parseThreadComposerPayloadWithTrace(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): ThreadComposerPayloadParseTrace {
    val startedAt = TimeSource.Monotonic.markNow()
    val primaryPayload = payload.trim()
    val source = if (primaryPayload.isEmpty()) fallbackPageData else primaryPayload
    val parsed = parseThreadComposerPayload(payload, fallbackPageData, instanceId)
    return ThreadComposerPayloadParseTrace(
        payload = parsed,
        durationMs = startedAt.elapsedNow().inWholeMilliseconds,
        responseBytes = source.encodeToByteArray().size,
        result = if (parsed.composerMode == "failed") "failure" else "success",
        source = if (primaryPayload.isEmpty()) "fallbackPageData" else "payload"
    )
}

private data class ThreadComposerPayloadParseTrace(
    val payload: KuiklyThreadComposerPayload,
    val durationMs: Long,
    val responseBytes: Int,
    val result: String,
    val source: String
)

private fun failedThreadComposerPayload(instanceId: String): KuiklyThreadComposerPayload =
    KuiklyThreadComposerPayload(
        instanceId = instanceId,
        composerMode = "failed",
        statusLabel = "Composer payload parse failed"
    )

private fun String.hasThreadComposerContentKeys(): Boolean =
    runCatching {
        val element = threadComposerJson.parseToJsonElement(this)
        (element as? JsonObject)?.keys.orEmpty().any { key -> key in threadComposerContentPayloadKeys }
    }.getOrDefault(false)

private fun parseThreadComposerLaunchPayload(
    source: String,
    instanceId: String,
    rejectAsFailure: Boolean
): KuiklyThreadComposerPayload {
    val launchSource = source.trim().takeIf { it.isNotEmpty() } ?: "{}"
    if (launchSource.hasThreadComposerContentKeys()) {
        return if (rejectAsFailure) {
            failedThreadComposerPayload(instanceId)
        } else {
            KuiklyThreadComposerPayload(instanceId = instanceId)
        }
    }
    return runCatching {
        threadComposerJson
            .decodeFromString(WireThreadComposerLaunchPayload.serializer(), launchSource)
            .toThreadComposerContentPayload(instanceId)
    }.getOrElse {
        if (rejectAsFailure) {
            failedThreadComposerPayload(instanceId)
        } else {
            KuiklyThreadComposerPayload(instanceId = instanceId)
        }
    }
}

private fun threadComposerVisualFixture(
    fixture: String,
    instanceId: String
): KuiklyThreadComposerPayload {
    val resolvedInstanceId = instanceId.ifBlank { "visual-thread-composer" }
    val base =
        KuiklyThreadComposerPayload(
            instanceId = resolvedInstanceId,
            // components.thread.composer.states draft — matches the react
            // render host's rendered draft (latest/react capture, task #370).
            seedText = "Review the Android composer crop",
            syncNonce = 1,
            focusRequestNonce = 0,
            placeholder = "Message design",
            memberSuggestions =
                listOf(
                    KuiklyComposerMemberItem(
                        displayName = "Cindy",
                        handle = "Cindy",
                        inMentionChannel = true,
                        kind = MemberKind.Agent,
                        description = "Keeps visual testing release notes aligned.",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "C"
                    ),
                    KuiklyComposerMemberItem(
                        displayName = "Product UX Designer",
                        handle = "Product-UX-Designer",
                        inMentionChannel = true,
                        kind = MemberKind.Agent,
                        description = "Turns screenshots into actionable UI specs.",
                        avatarWireValue = "pixel:paint",
                        avatarLabel = "P"
                    ),
                    KuiklyComposerMemberItem(
                        displayName = "Android Developer",
                        handle = "Android-Developer-4",
                        inMentionChannel = false,
                        kind = MemberKind.Agent,
                        description = "Implements shared Android/KMP UI fixes.",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "A"
                    )
                ),
            channelSuggestions =
                listOf(
                    KuiklyComposerChannelItem(name = "design", subtitle = "Product and UI decisions"),
                    KuiklyComposerChannelItem(name = "product", subtitle = "Release tracking and acceptance"),
                    KuiklyComposerChannelItem(name = "android-artifacts", subtitle = "Builds and screenshots")
                )
        )
    return when (fixture) {
        "empty" -> base.copy(seedText = "")
        "as_task_selected" -> base.copy(seedAsTask = true, seedText = "Review the Android composer crop")
        "pending_mention_actions" ->
            base.copy(
                seedText = "",
                pendingMentionActions =
                    listOf(
                        KuiklyPendingMentionActionItem(
                            resolutionId = "visual-pending-mention-resolution",
                            messageId = "visual-pending-mention-message",
                            channelId = "visual-design",
                            channelName = "design",
                            targetType = "agent",
                            targetHandle = "Android-Developer-4",
                            targetAvatarUrl = "pixel:robot",
                            reason = "target_not_in_channel",
                            availableActions = listOf("add", "notify")
                        )
                    )
            )
        "member_suggestions" -> base.copy(seedText = "@", suggestionsOpen = true)
        "channel_suggestions" -> base.copy(seedText = "#", suggestionsOpen = true)
        "image_preview" ->
            base.copy(
                seedText = "Uploading the design crop for review.",
                pendingAttachments =
                    listOf(
                        KuiklyPendingAttachmentItem(
                            id = "visual-att-image-preview",
                            fileName = "composer-preview.png",
                            sizeLabel = "128 KB",
                            mimeType = "image/png",
                            previewUri = ThreadComposerVisualPreviewUri,
                            status = "ready"
                        )
                    )
            )
        "file_previews" ->
            base.copy(
                seedText = "Attaching build artifacts and logs.",
                pendingAttachments =
                    listOf(
                        KuiklyPendingAttachmentItem(
                            id = "visual-att-report",
                            fileName = "release-notes.pdf",
                            sizeLabel = "428 KB",
                            mimeType = "application/pdf",
                            status = "ready"
                        ),
                        KuiklyPendingAttachmentItem(
                            id = "picked-logs",
                            fileName = "android-smoke-log.zip",
                            sizeLabel = "1.8 MB",
                            mimeType = "application/zip",
                            status = "uploading",
                            uploadProgress = 64
                        ),
                        KuiklyPendingAttachmentItem(
                            id = "picked-trace",
                            fileName = "oversized-trace.jsonl",
                            sizeLabel = "8.4 MB",
                            mimeType = "application/json",
                            status = "failed",
                            uploadProgress = 0,
                            errorCode = "too_large"
                        )
                    )
            )
        else -> base
    }
}

private fun WireThreadComposerLaunchPayload.toThreadComposerContentPayload(
    instanceIdFallback: String
): KuiklyThreadComposerPayload =
    KuiklyThreadComposerPayload(
        instanceId = instanceId.ifBlank { instanceIdFallback },
        seedText = seedText,
        seedAsTask = seedAsTask,
        syncNonce = syncNonce,
        focusRequestNonce = focusRequestNonce,
        surfaceKind = surfaceKind,
        mentionChannelId = mentionChannelId,
        parentMessageId = parentMessageId,
        streamChannelId = streamChannelId
    )

internal fun KuiklyThreadComposerPayload.isDefaultThreadComposer(): Boolean =
    this == KuiklyThreadComposerPayload()

internal fun KuiklyThreadComposerPayload.toThreadComposerSurface(
    channelId: String,
    parentChannelId: String? = null,
    mentionChannelLabel: String? = null,
    memberSuggestionHeadersVisible: Boolean = surfaceKind != KuiklyThreadSurfaceKind.DirectMessage
): ThreadComposerSurface =
    ThreadComposerSurface(
        kind = surfaceKind,
        channelId = channelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        mentionChannelId = mentionChannelId,
        mentionChannelLabel = mentionChannelLabel,
        memberSuggestionHeadersVisible = memberSuggestionHeadersVisible,
        showTaskButton = showAsTaskToggle
    )

internal fun KuiklyThreadComposerPayload.toThreadComposerUiState(): ThreadComposerUiState =
    ThreadComposerUiState(
        instanceId = instanceId,
        seedText = seedText,
        seedAsTask = seedAsTask,
        syncNonce = syncNonce,
        focusRequestNonce = focusRequestNonce,
        placeholder = placeholder,
        enabled = enabled,
        attachmentActionsEnabled = attachmentActionsEnabled,
        composerMode = composerMode,
        statusLabel = statusLabel,
        pendingAttachments = pendingAttachments,
        pendingMentionActions = pendingMentionActions,
        mentionActionNotice = mentionActionNotice,
        mentionActionStates = mentionActionStates,
        removingMentionActionIds = removingMentionActionIds,
        executingMentionActionIds = executingMentionActionIds,
        executingMentionActionStates = executingMentionActionStates,
        memberSuggestions = memberSuggestions,
        channelSuggestions = channelSuggestions,
        suggestionsOpen = suggestionsOpen,
        hookLabels = hookLabels
    )

internal fun threadComposerHostKeyboardLiftBottomPadding(
    keyboardInsetDp: Float,
    bottomContentPadding: Dp
): Dp {
    val keyboardInset = keyboardInsetDp.coerceAtLeast(0f).dp
    return if (keyboardInsetDp > 0f) {
        ThreadComposerHostKeyboardBasePadding + keyboardInset
    } else {
        bottomContentPadding
    }
}

internal fun KuiklyThreadScreenPayload.resolveThreadComposer(displayName: String = title): KuiklyThreadComposerPayload {
    val resolvedSurfaceKind =
        if (composer.surfaceKind == KuiklyThreadSurfaceKind.Channel && surfaceKind != KuiklyThreadSurfaceKind.Channel) {
            surfaceKind
        } else {
            composer.surfaceKind
        }
    val sourceStreamCandidate =
        sourceThreadId?.takeUnless {
            surfaceKind == KuiklyThreadSurfaceKind.ReplyThread &&
                parentChannelId != null &&
                it == parentChannelId
        }
    // Phase 3 #3 #3 (#280): replace composer > screen > origin 3-level ?:` chains with
    // explicit per-source resolution. Composer-scope value wins; if absent, screen-scope
    // (from the parent KuiklyThreadScreenPayload) wins; if absent, fall back to the
    // origin hint only when the surface is not ReplyThread (ReplyThread must use the
    // parent channel id, not the source thread id).
    val resolvedStreamChannelId = resolveComposerStreamChannelId(
        composerValue = composer.streamChannelId,
        screenValue = streamChannelId,
        originCandidate = sourceStreamCandidate,
        surfaceKind = resolvedSurfaceKind
    )
    val resolvedParentMessageId = resolveComposerParentMessageId(
        composerValue = composer.parentMessageId,
        screenValue = parentMessageId,
        originValue = originParentMessageId,
        surfaceKind = resolvedSurfaceKind
    )
    val resolvedMentionChannelId = composer.mentionChannelId ?: parentChannelId
    val resolvedPlaceholder =
        if (composer.placeholder == "Message") {
            threadSurfaceComposerPlaceholder(
                surfaceKind = resolvedSurfaceKind,
                displayName = displayName,
                fallbackId = sourceThreadId ?: instanceId
            )
        } else {
            composer.placeholder
        }
    return composer.copy(
        instanceId = composer.instanceId.ifBlank { instanceId },
        placeholder = resolvedPlaceholder,
        enabled = canSend && composer.enabled,
        showAsTaskToggle = composer.showAsTaskToggle && resolvedSurfaceKind != KuiklyThreadSurfaceKind.ReplyThread,
        surfaceKind = resolvedSurfaceKind,
        mentionChannelId = resolvedMentionChannelId,
        parentMessageId = resolvedParentMessageId,
        streamChannelId = resolvedStreamChannelId
    )
}

/**
 * Per-surface composer stream channel resolver. Replaces the prior 3-level
 * \`composer.streamChannelId ?: streamChannelId ?: sourceStreamCandidate\` (反例 8).
 *
 * - Composer value always wins (UI composer can override).
 * - ReplyThread surfaces fall back to screen value only; origin hint is rejected
 *   (origin is the parent channel id, not the thread stream id).
 * - Other surfaces fall back to screen value, then to origin candidate.
 */
private fun resolveComposerStreamChannelId(
    composerValue: String?,
    screenValue: String?,
    originCandidate: String?,
    surfaceKind: String
): String? = when {
    !composerValue.isNullOrBlank() -> composerValue
    surfaceKind == KuiklyThreadSurfaceKind.ReplyThread -> screenValue?.takeIf { it.isNotBlank() }
    !screenValue.isNullOrBlank() -> screenValue
    else -> originCandidate?.takeIf { it.isNotBlank() }
}

/**
 * Per-surface composer parent message resolver. Replaces the prior 3-level
 * \`composer.parentMessageId ?: parentMessageId ?: originParentMessageId\` (反例 9).
 *
 * Composer value always wins; otherwise falls back to screen value, then origin value.
 * No surface-kind branching needed because parent message id is always the same kind
 * across all surfaces (the message the thread is anchored to).
 */
private fun resolveComposerParentMessageId(
    composerValue: String?,
    screenValue: String?,
    originValue: String?,
    surfaceKind: String
): String? = when {
    !composerValue.isNullOrBlank() -> composerValue
    !screenValue.isNullOrBlank() -> screenValue
    else -> originValue?.takeIf { it.isNotBlank() }
}

internal enum class ThreadComposerSuggestionDemand {
    None,
    Members,
    Channels
}

@Composable
internal fun ThreadComposerContent(
    payload: KuiklyThreadComposerPayload,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding,
    hostPaddingHorizontal: Dp = 0.dp,
    hostPaddingTop: Dp = 0.dp,
    keyboardLiftBottomContentPadding: Dp? = null,
    composerSendKeyEnabled: Boolean = false,
    dismissKeyboardOnBlur: Boolean = false,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {}
) {
    ThreadComposerContent(
        surface =
            payload.toThreadComposerSurface(
                channelId = payload.streamChannelId?.takeIf { it.isNotBlank() } ?: payload.instanceId
            ),
        state = payload.toThreadComposerUiState(),
        onEvent = onEvent,
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = contentPaddingTop,
        contentPaddingBottom = contentPaddingBottom,
        hostPaddingHorizontal = hostPaddingHorizontal,
        hostPaddingTop = hostPaddingTop,
        keyboardLiftBottomContentPadding = keyboardLiftBottomContentPadding,
        composerSendKeyEnabled = composerSendKeyEnabled,
        dismissKeyboardOnBlur = dismissKeyboardOnBlur,
        insertMentionRequest = insertMentionRequest,
        onInsertMentionConsumed = onInsertMentionConsumed,
        onSuggestionDemandChanged = onSuggestionDemandChanged
    )
}

@Composable
internal fun ThreadComposerContent(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding,
    hostPaddingHorizontal: Dp = 0.dp,
    hostPaddingTop: Dp = 0.dp,
    keyboardLiftBottomContentPadding: Dp? = null,
    composerSendKeyEnabled: Boolean = false,
    dismissKeyboardOnBlur: Boolean = false,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    insertMentionRequest: ThreadComposerInsertMentionRequest? = null,
    onInsertMentionConsumed: (ThreadComposerInsertMentionRequest) -> Unit = {},
    onSuggestionDemandChanged: (ThreadComposerSuggestionDemand) -> Unit = {}
) {
    // #192 trace scope extension: wrap the full composer render. IME-lift / state
    // updates / suggestion list filtering are all under this; per-phase breakdown
    // can come later if this marker shows hot. bodyLength proxy uses seedText length.
    traceMarkdownSection(SlockComposerTraceNames.COMPOSER_RENDER, state.seedText.length) {
    var fieldValue by remember(state.instanceId) {
        mutableStateOf(TextFieldValue(state.seedText, selection = TextRange(state.seedText.length)))
    }
    val text = fieldValue.text
    var asTask by remember(state.instanceId) { mutableStateOf(state.seedAsTask) }
    var focused by remember(state.instanceId) { mutableStateOf(state.suggestionsOpen) }
    var handledFocusRequestNonce by remember(state.instanceId) { mutableStateOf(state.focusRequestNonce) }
    var keyboardInsetDp by remember(state.instanceId) { mutableStateOf(0f) }
    val composerFocusRequester = remember(state.instanceId) { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val shouldAutoFocus = state.focusRequestNonce > handledFocusRequestNonce
    LaunchedEffect(state.instanceId, state.syncNonce) {
        fieldValue = TextFieldValue(state.seedText, selection = TextRange(state.seedText.length))
        asTask = state.seedAsTask
        focused = state.suggestionsOpen
    }
    val selectionStart = minOf(fieldValue.selection.start, fieldValue.selection.end).coerceIn(0, text.length)
    val selectionEnd = maxOf(fieldValue.selection.start, fieldValue.selection.end).coerceIn(0, text.length)
    val suggestionTrigger =
        remember(text, selectionStart, state.suggestionsEnabled) {
            threadComposerSuggestionTrigger(
                text = text,
                cursor = selectionStart,
                suggestionsEnabled = state.suggestionsEnabled
            )
        }
    val suggestionDemand =
        when (suggestionTrigger) {
            is KuiklyComposerSuggestionTrigger.MemberTrigger -> ThreadComposerSuggestionDemand.Members
            is KuiklyComposerSuggestionTrigger.ChannelTrigger -> ThreadComposerSuggestionDemand.Channels
            KuiklyComposerSuggestionTrigger.None -> ThreadComposerSuggestionDemand.None
        }
    val latestSuggestionDemandChanged by rememberUpdatedState(onSuggestionDemandChanged)
    LaunchedEffect(state.instanceId, suggestionDemand) {
        latestSuggestionDemandChanged(suggestionDemand)
    }
    DisposableEffect(state.instanceId) {
        onDispose { latestSuggestionDemandChanged(ThreadComposerSuggestionDemand.None) }
    }
    var indexedMemberSuggestions by remember(state.instanceId) {
        mutableStateOf<ThreadComposerIndexedSuggestions<KuiklyComposerMemberItem>?>(null)
    }
    var indexedChannelSuggestions by remember(state.instanceId) {
        mutableStateOf<ThreadComposerIndexedSuggestions<KuiklyComposerChannelItem>?>(null)
    }
    LaunchedEffect(state.instanceId, state.memberSuggestions) {
        val source = state.memberSuggestions
        val entries = withContext(Dispatchers.Default) { source.toThreadComposerMemberSuggestionSearchEntries() }
        indexedMemberSuggestions = ThreadComposerIndexedSuggestions(source = source, entries = entries)
    }
    LaunchedEffect(state.instanceId, state.channelSuggestions) {
        val source = state.channelSuggestions
        val entries = withContext(Dispatchers.Default) { source.toThreadComposerChannelSuggestionSearchEntries() }
        indexedChannelSuggestions = ThreadComposerIndexedSuggestions(source = source, entries = entries)
    }
    val memberRankingRequest =
        remember(suggestionTrigger, indexedMemberSuggestions, state.memberSuggestions) {
            (suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger)
                ?.takeIf { trigger -> trigger.query.isNotBlank() }
                ?.let { trigger ->
                    indexedMemberSuggestions
                        ?.takeIf { indexed -> indexed.matches(state.memberSuggestions) }
                        ?.let { indexed -> ThreadComposerSuggestionRankingRequest(trigger.query, indexed.entries) }
                }
        }
    val channelRankingRequest =
        remember(suggestionTrigger, indexedChannelSuggestions, state.channelSuggestions) {
            (suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger)
                ?.takeIf { trigger -> trigger.query.isNotBlank() }
                ?.let { trigger ->
                    indexedChannelSuggestions
                        ?.takeIf { indexed -> indexed.matches(state.channelSuggestions) }
                        ?.let { indexed -> ThreadComposerSuggestionRankingRequest(trigger.query, indexed.entries) }
                }
        }
    var memberRankingResult by remember(state.instanceId) {
        mutableStateOf<ThreadComposerSuggestionRankingResult<KuiklyComposerMemberItem>?>(null)
    }
    var channelRankingResult by remember(state.instanceId) {
        mutableStateOf<ThreadComposerSuggestionRankingResult<KuiklyComposerChannelItem>?>(null)
    }
    LaunchedEffect(state.instanceId, memberRankingRequest) {
        if (memberRankingRequest == null) {
            memberRankingResult = null
        } else {
            delay(ThreadComposerSuggestionQueryDebounceMillis)
            val suggestions =
                withContext(Dispatchers.Default) {
                    rankThreadComposerMemberSuggestionEntriesCancellable(
                        query = memberRankingRequest.query,
                        entries = memberRankingRequest.entries
                    )
                }
            memberRankingResult = ThreadComposerSuggestionRankingResult(memberRankingRequest, suggestions)
        }
    }
    LaunchedEffect(state.instanceId, channelRankingRequest) {
        if (channelRankingRequest == null) {
            channelRankingResult = null
        } else {
            delay(ThreadComposerSuggestionQueryDebounceMillis)
            val suggestions =
                withContext(Dispatchers.Default) {
                    rankThreadComposerChannelSuggestionEntriesCancellable(
                        query = channelRankingRequest.query,
                        entries = channelRankingRequest.entries
                    )
                }
            channelRankingResult = ThreadComposerSuggestionRankingResult(channelRankingRequest, suggestions)
        }
    }
    val memberRankingReady =
        memberRankingRequest != null && memberRankingResult?.request === memberRankingRequest
    val channelRankingReady =
        channelRankingRequest != null && channelRankingResult?.request === channelRankingRequest
    val immediateMemberSuggestions =
        (suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger)
            ?.let { trigger -> immediateThreadComposerMemberSuggestions(trigger.query, state.memberSuggestions) }
            .orEmpty()
    val immediateChannelSuggestions =
        (suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger)
            ?.let { trigger -> immediateThreadComposerSuggestions(trigger.query, state.channelSuggestions) }
            .orEmpty()
    val memberUsesImmediateSuggestions =
        (suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger)?.query?.isBlank() == true
    val channelUsesImmediateSuggestions =
        (suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger)?.query?.isBlank() == true
    val filteredMemberSuggestions =
        if (suggestionTrigger is KuiklyComposerSuggestionTrigger.MemberTrigger) {
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = memberUsesImmediateSuggestions,
                rankingReady = memberRankingReady,
                rankingResultSuggestions = memberRankingResult?.suggestions.orEmpty(),
                immediateSuggestions = immediateMemberSuggestions,
                pendingFallbackSuggestions = immediateThreadComposerMemberSuggestions("", state.memberSuggestions)
            )
        } else {
            emptyList()
        }
    val filteredChannelSuggestions =
        if (suggestionTrigger is KuiklyComposerSuggestionTrigger.ChannelTrigger) {
            displayedThreadComposerSuggestions(
                usesImmediateSuggestions = channelUsesImmediateSuggestions,
                rankingReady = channelRankingReady,
                rankingResultSuggestions = channelRankingResult?.suggestions.orEmpty(),
                immediateSuggestions = immediateChannelSuggestions,
                pendingFallbackSuggestions = immediateThreadComposerSuggestions("", state.channelSuggestions)
            )
        } else {
            emptyList()
        }
    val effectiveMemberSuggestionsStatus =
        threadComposerEffectiveSuggestionStatus(
            triggerActive = suggestionTrigger is KuiklyComposerSuggestionTrigger.MemberTrigger,
            rankingReady = memberRankingReady,
            usesImmediateSuggestions = memberUsesImmediateSuggestions,
            sourceStatus = state.memberSuggestionsStatus
        )
    val effectiveChannelSuggestionsStatus =
        threadComposerEffectiveSuggestionStatus(
            triggerActive = suggestionTrigger is KuiklyComposerSuggestionTrigger.ChannelTrigger,
            rankingReady = channelRankingReady,
            usesImmediateSuggestions = channelUsesImmediateSuggestions,
            sourceStatus = state.channelSuggestionsStatus
        )
    val suggestionPanelState =
        threadComposerSuggestionPanelState(
            focused = focused,
            trigger = suggestionTrigger,
            memberCount = filteredMemberSuggestions.size,
            memberStatus = effectiveMemberSuggestionsStatus,
            channelCount = filteredChannelSuggestions.size,
            channelStatus = effectiveChannelSuggestionsStatus
        )
    var memberSuggestionSelectedIndex by remember(state.instanceId) { mutableStateOf(0) }
    var channelSuggestionSelectedIndex by remember(state.instanceId) { mutableStateOf(0) }
    LaunchedEffect(suggestionTrigger, suggestionPanelState, filteredMemberSuggestions) {
        memberSuggestionSelectedIndex = 0
    }
    LaunchedEffect(suggestionTrigger, suggestionPanelState, filteredChannelSuggestions) {
        channelSuggestionSelectedIndex = 0
    }
    val suggestionRefreshRequest =
        remember(focused, suggestionTrigger) {
            threadComposerSuggestionRefreshRequest(
                focused = focused,
                trigger = suggestionTrigger
            )
        }
    LaunchedEffect(state.instanceId, suggestionRefreshRequest) {
        when (suggestionRefreshRequest) {
            ThreadComposerSuggestionRefreshRequest.Member -> onEvent("refreshMemberSuggestions", null, null, null)
            ThreadComposerSuggestionRefreshRequest.Channel -> onEvent("refreshChannelSuggestions", null, null, null)
            null -> Unit
        }
    }
    LaunchedEffect(state.instanceId, state.mentionActionNotice) {
        if (!state.mentionActionNotice.isNullOrBlank()) {
            delay(ThreadComposerMentionActionNoticeDurationMillis)
            onEvent("dismissMentionNotice", null, null, null)
        }
    }
    val composerEnabled = state.enabled && surface.sendChannelId != null
    val canSend =
        composerEnabled &&
            state.composerMode != "sending" &&
            state.composerMode != "uploading" &&
            state.composerMode != "no_permission" &&
            (text.isNotBlank() || state.pendingAttachments.isNotEmpty())
    val composerKeyboardOptions =
        if (composerSendKeyEnabled) {
            KeyboardOptions(imeAction = ImeAction.Send)
        } else {
            KeyboardOptions(imeAction = ImeAction.None)
        }

    fun emitChange(nextText: String = text, nextAsTask: Boolean = asTask) {
        onEvent("change", nextText, nextAsTask, null)
    }

    fun insertMentionAtCursor(handle: String): Boolean {
        val edit = applyKuiklyComposerDirectMentionInsert(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            handle = handle
        ) ?: return false
        fieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
        focused = true
        emitChange(nextText = edit.text)
        return true
    }

    LaunchedEffect(insertMentionRequest?.nonce) {
        val request = insertMentionRequest ?: return@LaunchedEffect
        if (insertMentionAtCursor(request.handle)) {
            composerFocusRequester.requestFocus()
            // Android Back can dismiss the IME while the editor remains focused. A repeated
            // avatar mention is an explicit input intent, so reassert both logical focus and the
            // software keyboard instead of relying on a focus transition that may be a no-op.
            softwareKeyboardController?.show()
        }
        onInsertMentionConsumed(request)
    }

    fun applyMemberSuggestion(member: KuiklyComposerMemberItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger ?: return
        applyKuiklyComposerSuggestionEdit(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            triggerStart = trigger.triggerStart,
            replacement = "@${member.handle} "
        )?.let { edit ->
            fieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
            emitChange(nextText = edit.text)
        }
    }

    fun applyChannelSuggestion(channel: KuiklyComposerChannelItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger ?: return
        applyKuiklyComposerSuggestionEdit(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            triggerStart = trigger.triggerStart,
            replacement = "#${channel.name} "
        )?.let { edit ->
            fieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
            emitChange(nextText = edit.text)
        }
    }

    fun commitSuggestionToken() {
        val triggerStart =
            when (val trigger = suggestionTrigger) {
                is KuiklyComposerSuggestionTrigger.MemberTrigger -> trigger.triggerStart
                is KuiklyComposerSuggestionTrigger.ChannelTrigger -> trigger.triggerStart
                KuiklyComposerSuggestionTrigger.None -> return
            }
        commitKuiklyComposerSuggestionToken(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            triggerStart = triggerStart
        )?.let { edit ->
            fieldValue = TextFieldValue(edit.text, selection = TextRange(edit.cursor))
            focused = false
            emitChange(nextText = edit.text)
        }
    }

    fun sendComposerMessage() {
        // The host send handler owns the canonical clear and advances syncNonce.
        // Keeping the pre-send local value lets that sync reject a late native echo.
        onEvent("send", null, null, null)
    }

    fun handleKeyboardSend() {
        when (
            threadComposerEnterAction(
                trigger = suggestionTrigger,
                panelState = suggestionPanelState,
                memberCount = filteredMemberSuggestions.size,
                channelCount = filteredChannelSuggestions.size,
                canSend = canSend,
                isImeComposing = fieldValue.composition != null
            )
        ) {
            ThreadComposerEnterAction.AcceptMemberSuggestion ->
                filteredMemberSuggestions
                    .composerMemberSuggestionCandidateAt(
                        selectedIndex = memberSuggestionSelectedIndex,
                        groupByMentionChannel = surface.memberSuggestionHeadersVisible
                    )
                    ?.let(::applyMemberSuggestion)
            ThreadComposerEnterAction.AcceptChannelSuggestion ->
                filteredChannelSuggestions
                    .getOrNull(threadComposerSuggestionSelectedIndex(channelSuggestionSelectedIndex, filteredChannelSuggestions.size))
                    ?.let(::applyChannelSuggestion)
            ThreadComposerEnterAction.CommitSuggestionToken -> commitSuggestionToken()
            ThreadComposerEnterAction.Send -> sendComposerMessage()
            ThreadComposerEnterAction.Noop -> Unit
        }
    }
    val composerKeyboardActions =
        if (composerSendKeyEnabled) {
            KeyboardActions(onSend = { handleKeyboardSend() })
        } else {
            KeyboardActions.Default
        }

    val hostPaddingBottom =
        keyboardLiftBottomContentPadding?.let { bottomContentPadding ->
            threadComposerHostKeyboardLiftBottomPadding(
                keyboardInsetDp = keyboardInsetDp,
                bottomContentPadding = bottomContentPadding
            )
        } ?: 0.dp

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .padding(
                    start = hostPaddingHorizontal,
                    top = hostPaddingTop,
                    end = hostPaddingHorizontal,
                    bottom = hostPaddingBottom
                ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = contentPaddingTop, bottom = contentPaddingBottom),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.statusLabel?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = localizedThreadComposerStatusLabel(status),
                    style = SlockType.Caption.copy(
                        color = if (state.composerMode == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray
                    ),
                    color = if (state.composerMode == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.pendingAttachments.isNotEmpty()) {
                ComposerAttachmentTray(
                    attachments = state.pendingAttachments,
                    enabled = state.enabled
                ) { action, attachmentId ->
                    onEvent(action, null, null, attachmentId)
                }
            }
            state.mentionActionNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                MentionActionNotice(notice)
            }
            if (state.pendingMentionActions.isNotEmpty()) {
                PendingMentionActionBanner(
                    actions = state.pendingMentionActions,
                    memberSuggestions = state.memberSuggestions,
                    channelLabel = surface.localizedMentionScopeChannelLabel(),
                    actionStates = state.mentionActionStates,
                    removingIds = state.removingMentionActionIds.toSet(),
                    executingIds = state.executingMentionActionIds.toSet(),
                    executingStates = state.executingMentionActionStates,
                    onInvite = { resolutionId -> onEvent("inviteMention", resolutionId, null, null) },
                    onNotify = { resolutionId -> onEvent("notifyMention", resolutionId, null, null) },
                    onDismiss = { resolutionId -> onEvent("dismissMention", resolutionId, null, null) }
                )
            }
            ComposerSuggestionSlot(
                panelState = suggestionPanelState,
                surface = surface,
                memberSuggestions = filteredMemberSuggestions,
                memberSelectedIndex = memberSuggestionSelectedIndex,
                memberErrorCode = state.memberSuggestionsErrorCode,
                channelSuggestions = filteredChannelSuggestions,
                channelSelectedIndex = channelSuggestionSelectedIndex,
                channelErrorCode = state.channelSuggestionsErrorCode,
                agentStatusResolver = agentStatusResolver,
                onMemberClick = { applyMemberSuggestion(it) },
                onChannelClick = { applyChannelSuggestion(it) }
            )
            Box(
                modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier.matchParentSize().offset(x = 2f, y = 2f).background(SlockColor.ShadowBlack)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .clickable(enabled = composerEnabled) { composerFocusRequester.requestFocus() }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = fieldValue,
                        placeholder = localizedThreadComposerPlaceholder(state.placeholder),
                        autoFocus = shouldAutoFocus,
                        onValueChange = { next ->
                            val textChanged = next.text != fieldValue.text
                            fieldValue = next
                            focused = true
                            if (textChanged) {
                                emitChange(nextText = next.text)
                            }
                        },
                        onFocus = {
                            focused = true
                            if (shouldAutoFocus) {
                                handledFocusRequestNonce = state.focusRequestNonce
                            }
                        },
                        onBlur = {
                            focused = false
                            if (dismissKeyboardOnBlur) {
                                softwareKeyboardController?.hide()
                            }
                            onEvent("blur", null, null, null)
                        },
                        maxLines = 4,
                        keyboardOptions = composerKeyboardOptions,
                        keyboardActions = composerKeyboardActions,
                        textStyle = SlockType.Input.copy(
                            color = if (composerEnabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                            fontSize = 16.sp,
                            lineHeight = 20.sp
                        ),
                        keyboardHeightChange = { params ->
                            val nextInset = params.height.coerceAtLeast(0f)
                            if (nextInset != keyboardInsetDp) {
                                keyboardInsetDp = nextInset
                            }
                            onKeyboardHeightChange(nextInset)
                        },
                        placeholderColor = SlockColor.TextMutedGray,
                        cursorBrush = SolidColor(SlockColor.TextBlack),
                        systemNewlineAction = true,
                        participatesInGlobalFocusDismiss = false,
                        // React mobile uses a one-line textarea; keep the toolbar as the touch target.
                        focusRequester = composerFocusRequester,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = ThreadComposerTextFieldMinHeight,
                                max = ThreadComposerTextFieldMaxHeight
                            )
                    )
                    if (state.hookLabels.isNotEmpty()) {
                        Text(
                            text = state.hookLabels.joinToString(" / "),
                            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = ThreadComposerToolbarMinHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = ThreadComposerToolbarMinHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ThreadComposerLeftActionGroupSpacing)
                        ) {
                            if (state.attachmentActionsEnabled) {
                                ComposerIconButton(SlockLucideIconName.ImagePlus, composerEnabled) {
                                    onEvent("attachImage", null, null, null)
                                }
                                ComposerIconButton(SlockLucideIconName.Paperclip, composerEnabled) {
                                    onEvent("attachFile", null, null, null)
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (surface.showTaskButton) {
                                Row(
                                    modifier = Modifier
                                        .clickable(enabled = composerEnabled) {
                                            val newValue = !asTask
                                            asTask = newValue
                                            onEvent("toggleAsTask", null, newValue, null)
                                        }
                                        .heightIn(min = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SlockCheckbox(
                                        checked = asTask,
                                        onCheckedChange = { checked ->
                                            asTask = checked
                                            onEvent("toggleAsTask", null, checked, null)
                                        },
                                        enabled = composerEnabled,
                                        size = 14.dp
                                    )
                                    val labelColor = if (composerEnabled) SlockColor.TextBlack.copy(alpha = 0.6f) else SlockColor.TextMutedGray
                                    Text(
                                        text = stringResource(MR.strings.thread_composer_as_task),
                                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(lineHeight = 18.sp, color = labelColor),
                                        color = labelColor
                                    )
                                }
                            }
                            ComposerSendButton(
                                enabled = canSend,
                                sending = state.composerMode == "sending" || state.composerMode == "uploading"
                            ) {
                                sendComposerMessage()
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ComposerSuggestionSlot(
    panelState: ThreadComposerSuggestionPanelState,
    surface: ThreadComposerSurface,
    memberSuggestions: List<KuiklyComposerMemberItem>,
    memberSelectedIndex: Int,
    memberErrorCode: String?,
    channelSuggestions: List<KuiklyComposerChannelItem>,
    channelSelectedIndex: Int,
    channelErrorCode: String?,
    agentStatusResolver: SlockAgentStatusResolver,
    onMemberClick: (KuiklyComposerMemberItem) -> Unit,
    onChannelClick: (KuiklyComposerChannelItem) -> Unit
) {
    if (panelState == ThreadComposerSuggestionPanelState.Hidden) return
    val memberSuggestionTitle = surface.memberSuggestionTitle()
    val memberPanelEntryCount =
        remember(surface.memberSuggestionHeadersVisible, memberSuggestionTitle, memberSuggestions) {
            if (surface.memberSuggestionHeadersVisible) {
                composerMemberSuggestionPanelEntries(memberSuggestionTitle, memberSuggestions).entries.size
            } else {
                memberSuggestions.size
            }
        }
    val slotHeight =
        remember(
            panelState,
            surface.memberSuggestionHeadersVisible,
            memberPanelEntryCount,
            channelSuggestions.size
        ) {
            composerSuggestionPanelSlotHeight(
                panelState = panelState,
                memberCount = memberSuggestions.size,
                channelCount = channelSuggestions.size,
                includeMemberHeader = surface.memberSuggestionHeadersVisible,
                memberPanelEntryCount = memberPanelEntryCount
            )
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(slotHeight)
                .zIndex(ComposerSuggestionPanelZIndex)
                .padding(end = 2.dp)
    ) {
        ComposerSuggestionPanelContent(
            panelState = panelState,
            surface = surface,
            memberSuggestions = memberSuggestions,
            memberSelectedIndex = memberSelectedIndex,
            memberErrorCode = memberErrorCode,
            channelSuggestions = channelSuggestions,
            channelSelectedIndex = channelSelectedIndex,
            channelErrorCode = channelErrorCode,
            agentStatusResolver = agentStatusResolver,
            onMemberClick = onMemberClick,
            onChannelClick = onChannelClick
        )
    }
}

internal fun composerSuggestionPanelSlotHeight(
    panelState: ThreadComposerSuggestionPanelState,
    memberCount: Int,
    channelCount: Int,
    includeMemberHeader: Boolean = true,
    memberPanelEntryCount: Int = memberCount
): Dp {
    val estimated =
        when (panelState) {
            ThreadComposerSuggestionPanelState.MemberItems ->
                composerSuggestionPanelContentHeight(
                    itemCount = memberPanelEntryCount,
                    rowMinHeight = ComposerMemberSuggestionRowMinHeight,
                    includeHeader = includeMemberHeader
                )
            ThreadComposerSuggestionPanelState.ChannelItems ->
                composerSuggestionPanelContentHeight(
                    itemCount = channelCount,
                    rowMinHeight = ComposerChannelSuggestionRowMinHeight,
                    includeHeader = false
                )
            ThreadComposerSuggestionPanelState.MemberLoading,
            ThreadComposerSuggestionPanelState.MemberEmpty,
            ThreadComposerSuggestionPanelState.MemberError ->
                composerSuggestionPanelContentHeight(
                    itemCount = 1,
                    rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                    includeHeader = includeMemberHeader
                )
            ThreadComposerSuggestionPanelState.ChannelLoading,
            ThreadComposerSuggestionPanelState.ChannelEmpty,
            ThreadComposerSuggestionPanelState.ChannelError ->
                composerSuggestionPanelContentHeight(
                    itemCount = 1,
                    rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                    includeHeader = false
                )
            ThreadComposerSuggestionPanelState.Hidden ->
                0.dp
    }
    return estimated
}

internal fun composerSuggestionPanelContentHeight(
    itemCount: Int,
    rowMinHeight: Dp,
    includeHeader: Boolean = true
): Dp {
    val headerHeight = if (includeHeader) ComposerSuggestionHeaderEstimatedHeight else 0.dp
    val estimated = headerHeight + rowMinHeight * itemCount.coerceAtLeast(1)
    return if (estimated > ComposerSuggestionPanelMaxHeight) ComposerSuggestionPanelMaxHeight else estimated
}

@Composable
private fun ComposerSuggestionPanelContent(
    panelState: ThreadComposerSuggestionPanelState,
    surface: ThreadComposerSurface,
    memberSuggestions: List<KuiklyComposerMemberItem>,
    memberSelectedIndex: Int,
    memberErrorCode: String?,
    channelSuggestions: List<KuiklyComposerChannelItem>,
    channelSelectedIndex: Int,
    channelErrorCode: String?,
    agentStatusResolver: SlockAgentStatusResolver,
    onMemberClick: (KuiklyComposerMemberItem) -> Unit,
    onChannelClick: (KuiklyComposerChannelItem) -> Unit
) {
    when (panelState) {
        ThreadComposerSuggestionPanelState.MemberItems -> {
            ComposerMemberSuggestionPanel(
                title = surface.memberSuggestionTitle(),
                showMembershipSections = surface.memberSuggestionHeadersVisible,
                members = memberSuggestions,
                selectedIndex = memberSelectedIndex,
                agentStatusResolver = agentStatusResolver,
                onClick = onMemberClick
            )
        }

        ThreadComposerSuggestionPanelState.MemberLoading -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = stringResource(MR.strings.thread_state_loading)
            )
        }

        ThreadComposerSuggestionPanelState.MemberEmpty -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = stringResource(MR.strings.composer_state_no_matching_members)
            )
        }

        ThreadComposerSuggestionPanelState.MemberError -> {
            ComposerSuggestionStatusPanel(
                title = surface.memberSuggestionTitle(),
                message = memberErrorCode?.takeIf { it.isNotBlank() }
                    ?: stringResource(MR.strings.thread_composer_load_members_failed)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelItems -> {
            ComposerSuggestionPanel(
                title = null,
                showTitleDivider = false,
                panelHeight =
                    composerSuggestionPanelContentHeight(
                        itemCount = channelSuggestions.size,
                        rowMinHeight = ComposerChannelSuggestionRowMinHeight,
                        includeHeader = false
                    ),
                itemCount = channelSuggestions.size,
                itemKey = { index -> "channel-$index-${channelSuggestions[index].name}" }
            ) { index ->
                val channel = channelSuggestions[index]
                val selectedIndex =
                    threadComposerSuggestionSelectedIndex(channelSelectedIndex, channelSuggestions.size)
                ComposerChannelSuggestionRow(
                    channel = channel,
                    selected = index == selectedIndex,
                    onClick = { onChannelClick(channel) }
                )
            }
        }

        ThreadComposerSuggestionPanelState.ChannelLoading -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = stringResource(MR.strings.thread_state_loading)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelEmpty -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = stringResource(MR.strings.composer_state_no_matching_channels)
            )
        }

        ThreadComposerSuggestionPanelState.ChannelError -> {
            ComposerSuggestionStatusPanel(
                title = null,
                message = channelErrorCode?.takeIf { it.isNotBlank() }
                    ?: stringResource(MR.strings.thread_composer_load_channels_failed)
            )
        }

        ThreadComposerSuggestionPanelState.Hidden -> Unit
    }
}

@Composable
private fun ThreadComposerSurface.memberSuggestionTitle(): String? =
    if (!memberSuggestionHeadersVisible) {
        null
    } else {
        when (kind) {
            KuiklyThreadSurfaceKind.ReplyThread -> stringResource(MR.strings.thread_composer_members_parent_channel)
            else -> stringResource(MR.strings.thread_composer_members_this_channel)
        }
    }

@Composable
private fun ThreadComposerSurface.localizedMentionScopeChannelLabel(): String =
    mentionChannelLabel.toComposerChannelMentionLabel()
        ?: stringResource(MR.strings.thread_composer_scope_this_channel)

@Composable
private fun localizedThreadComposerPlaceholder(rawPlaceholder: String): String {
    val normalized = rawPlaceholder.trim()
    return when {
        normalized == "Message" -> stringResource(MR.strings.thread_composer_placeholder_message)
        normalized == "Reply in thread" -> stringResource(MR.strings.thread_composer_placeholder_reply)
        normalized.startsWith("Message @") ->
            stringResource(
                MR.strings.thread_composer_placeholder_dm,
                normalized.removePrefix("Message @")
            )
        normalized.startsWith("Message #") ->
            stringResource(
                MR.strings.thread_composer_placeholder_channel,
                normalized.removePrefix("Message #")
            )
        else -> rawPlaceholder
    }
}

@Composable
private fun localizedThreadComposerStatusLabel(status: String): String =
    if (status == "Composer payload parse failed") {
        stringResource(MR.strings.thread_composer_payload_failed)
    } else {
        status
    }

internal fun ThreadComposerSurface.mentionScopeChannelLabel(): String =
    mentionChannelLabel.toComposerChannelMentionLabel()
        ?: "this channel"

internal fun String?.toComposerChannelMentionLabel(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("#") || raw.startsWith("@")) return raw
    val display = threadHeaderSafeTitleOrNull(raw) ?: return null
    return "#${display.removePrefix("#")}"
}

internal fun List<KuiklyComposerMemberItem>.firstComposerMemberSuggestionCandidate(): KuiklyComposerMemberItem? =
    composerMemberSuggestionCandidateAt(0)

internal fun List<KuiklyComposerMemberItem>.composerMemberSuggestionCandidateAt(
    selectedIndex: Int,
    groupByMentionChannel: Boolean = true
): KuiklyComposerMemberItem? {
    if (isEmpty()) return null
    if (!groupByMentionChannel) {
        return getOrNull(threadComposerSuggestionSelectedIndex(selectedIndex, size))
    }
    val groups = composerMemberSuggestionGroups()
    val ordered = groups.inThread + groups.inMentionChannel + groups.notInMentionChannel
    val index = threadComposerSuggestionSelectedIndex(selectedIndex, ordered.size)
    return ordered.getOrNull(index)
}

internal data class ComposerMemberSuggestionGroups(
    val inThread: List<KuiklyComposerMemberItem>,
    val inMentionChannel: List<KuiklyComposerMemberItem>,
    val notInMentionChannel: List<KuiklyComposerMemberItem>
)

internal fun List<KuiklyComposerMemberItem>.composerMemberSuggestionGroups(): ComposerMemberSuggestionGroups {
    val inThread = filter { it.inThread }
    val inThreadKeys = inThread.mapTo(mutableSetOf()) { it.composerMemberSuggestionKey() }
    val inMentionChannel =
        filter { item -> item.inMentionChannel && item.composerMemberSuggestionKey() !in inThreadKeys }
    val inMentionChannelKeys = inMentionChannel.mapTo(mutableSetOf()) { it.composerMemberSuggestionKey() }
    val notInMentionChannel =
        filter { item ->
            val key = item.composerMemberSuggestionKey()
            key !in inThreadKeys && key !in inMentionChannelKeys
        }
    return ComposerMemberSuggestionGroups(
        inThread = inThread,
        inMentionChannel = inMentionChannel,
        notInMentionChannel = notInMentionChannel
    )
}

private fun KuiklyComposerMemberItem.composerMemberSuggestionKey(): String =
    memberId?.trim()?.takeIf { it.isNotBlank() }
        ?.let { "${kind.name}:$it" }
        ?: handle.trim().lowercase().takeIf { it.isNotBlank() }?.let { "${kind.name}:$it" }
        ?: "${kind.name}:${displayName.trim().lowercase()}"

internal fun List<KuiklyPendingAttachmentItem>.readyThreadAttachmentIdsForSend(): List<String> =
    filter { attachment -> attachment.isReadyThreadAttachmentForSend() }
        .map { attachment -> attachment.id }

internal fun List<KuiklyPendingAttachmentItem>.hasBlockingThreadAttachmentUpload(): Boolean =
    any { attachment -> attachment.id.isNotBlank() && !attachment.isReadyThreadAttachmentForSend() }

internal fun List<KuiklyPendingAttachmentItem>.withThreadAttachmentUploadWaitingState(): List<KuiklyPendingAttachmentItem> =
    map { attachment ->
        if (attachment.isReadyThreadAttachmentForSend() || attachment.id.isBlank()) {
            attachment
        } else {
            attachment.copy(status = "uploading")
        }
    }

internal fun KuiklyPendingAttachmentItem.isReadyThreadAttachmentForSend(): Boolean =
    id.isNotBlank() && status == "ready" && !id.startsWith("picked-")

internal fun KuiklyPendingMentionActionItem.supportsMentionAction(verb: String): Boolean =
    availableActions.any { action ->
        when (verb) {
            "notify" -> action == "notify" || action == "notify_only"
            "invite" -> action == "invite" || action == "add"
            else -> false
        }
    }

internal fun KuiklyPendingMentionActionItem.targetLabel(): String {
    val handle = targetHandle.ifBlank { targetType }
    return if ((targetType == "user" || targetType == "agent") && handle.isNotBlank() && !handle.startsWith("@")) {
        "@$handle"
    } else {
        handle.ifBlank { "@unknown" }
    }
}

private fun KuiklyPendingMentionActionItem.targetInitial(): String =
    targetLabel().removePrefix("@").trim().take(1).ifBlank { "?" }.uppercase()

internal fun KuiklyPendingMentionActionItem.targetAvatarWireValue(): String? =
    targetAvatarUrl?.takeIf { it.isNotBlank() }

internal fun KuiklyPendingMentionActionItem.matchingTargetSuggestion(
    suggestions: List<KuiklyComposerMemberItem>
): KuiklyComposerMemberItem? {
    val target = targetHandle.normalizedPendingMentionTargetHandle() ?: return null
    return suggestions.firstOrNull { suggestion ->
        suggestion.handle.normalizedPendingMentionTargetHandle() == target
    } ?: suggestions.firstOrNull { suggestion ->
        suggestion.displayName.normalizedPendingMentionTargetHandle() == target
    }
}

private fun String?.normalizedPendingMentionTargetHandle(): String? =
    this
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()

internal fun KuiklyPendingMentionActionItem.pendingMentionActionDescription(
    channelLabel: String,
    localState: String? = null
): String {
    val target = targetLabel()
    if (localState == "added") {
        return "$target was added to $channelLabel"
    }
    if (localState == "notified") {
        return "Notified $target once - still not in $channelLabel"
    }
    val actionHint =
        when {
            supportsMentionAction("invite") && supportsMentionAction("notify") ->
                "Add them to $channelLabel or send a one-time notify."
            supportsMentionAction("invite") ->
                "Add them to $channelLabel so future mentions notify them."
            supportsMentionAction("notify") ->
                "Send a one-time notify without adding them."
            else ->
                "Dismiss this reminder when no action is needed."
        }
    return "$target isn't in $channelLabel. $actionHint"
}

@Composable
private fun KuiklyPendingMentionActionItem.localizedPendingMentionActionDescription(
    channelLabel: String,
    localState: String?
): String {
    val target = targetLabel()
    if (localState == "added") {
        return stringResource(MR.strings.thread_composer_mention_added, target, channelLabel)
    }
    if (localState == "notified") {
        return stringResource(MR.strings.thread_composer_mention_notified, target, channelLabel)
    }
    val actionHint =
        when {
            supportsMentionAction("invite") && supportsMentionAction("notify") ->
                stringResource(MR.strings.thread_composer_mention_add_or_notify, channelLabel)
            supportsMentionAction("invite") ->
                stringResource(MR.strings.thread_composer_mention_add_hint, channelLabel)
            supportsMentionAction("notify") ->
                stringResource(MR.strings.thread_composer_mention_notify_hint)
            else ->
                stringResource(MR.strings.thread_composer_mention_dismiss_hint)
        }
    return stringResource(MR.strings.thread_composer_mention_not_in_channel, target, channelLabel, actionHint)
}

@Composable
private fun ComposerMemberSuggestionPanel(
    title: String?,
    showMembershipSections: Boolean,
    members: List<KuiklyComposerMemberItem>,
    selectedIndex: Int,
    agentStatusResolver: SlockAgentStatusResolver,
    onClick: (KuiklyComposerMemberItem) -> Unit
) {
    if (!showMembershipSections) {
        val clampedSelectedIndex = threadComposerSuggestionSelectedIndex(selectedIndex, members.size)
        ComposerSuggestionPanel(
            title = null,
            showTitleDivider = false,
            panelHeight =
                composerSuggestionPanelContentHeight(
                    itemCount = members.size,
                    rowMinHeight = ComposerMemberSuggestionRowMinHeight,
                    includeHeader = false
                ),
            itemCount = members.size,
            itemKey = { index -> "member-${members[index].composerMemberSuggestionKey()}" }
        ) { index ->
            val member = members[index]
            ComposerMemberSuggestionRow(
                member = member,
                inChannel = true,
                selected = clampedSelectedIndex == index,
                agentStatusResolver = agentStatusResolver,
                onClick = { onClick(member) }
            )
        }
        return
    }
    val localizedParentChannelTitle = stringResource(MR.strings.thread_composer_members_parent_channel)
    val localizedNotInParentChannelTitle = stringResource(MR.strings.thread_composer_members_not_parent_channel)
    val localizedNotInThisChannelTitle = stringResource(MR.strings.thread_composer_members_not_this_channel)
    val panelEntries =
        composerMemberSuggestionPanelEntries(
            title = title,
            members = members,
            inThreadTitle = stringResource(MR.strings.thread_composer_members_this_thread),
            inThisChannelTitle = stringResource(MR.strings.thread_composer_members_this_channel),
            notInParentChannelTitle = localizedNotInParentChannelTitle,
            notInThisChannelTitle = localizedNotInThisChannelTitle,
            notInScopeTitle =
                if (title == localizedParentChannelTitle) {
                    localizedNotInParentChannelTitle
                } else {
                    localizedNotInThisChannelTitle
                }
        )
    val clampedSelectedIndex = threadComposerSuggestionSelectedIndex(selectedIndex, panelEntries.selectableCount)
    ComposerSuggestionPanel(
        title = panelEntries.title,
        showTitleDivider = false,
        panelHeight = composerSuggestionPanelContentHeight(
            itemCount = panelEntries.entries.size,
            rowMinHeight = ComposerMemberSuggestionRowMinHeight,
            includeHeader = panelEntries.title != null
        ),
        itemCount = panelEntries.entries.size,
        itemKey = { index ->
            panelEntries.entries[index].key
        }
    ) { index ->
        val entry = panelEntries.entries[index]
        if (entry.headerText != null) {
            ComposerSuggestionHeader(
                text = entry.headerText,
                topDivider = entry.topDivider
            )
        } else {
            entry.member?.let { member ->
                ComposerMemberSuggestionRow(
                    member = member,
                    inChannel = entry.inChannel,
                    selected = clampedSelectedIndex == entry.selectableIndex,
                    agentStatusResolver = agentStatusResolver,
                    onClick = { onClick(member) }
                )
            }
        }
    }
}

private data class ComposerMemberSuggestionPanelModel(
    val title: String?,
    val entries: List<ComposerMemberSuggestionPanelEntry>,
    val selectableCount: Int
)

private data class ComposerMemberSuggestionPanelEntry(
    val key: String,
    val headerText: String? = null,
    val topDivider: Boolean = false,
    val member: KuiklyComposerMemberItem? = null,
    val selectableIndex: Int = -1,
    val inChannel: Boolean = true
)

private fun composerMemberSuggestionPanelEntries(
    title: String?,
    members: List<KuiklyComposerMemberItem>,
    inThreadTitle: String = "IN THIS THREAD",
    inThisChannelTitle: String = "IN THIS CHANNEL",
    notInParentChannelTitle: String = "NOT IN PARENT CHANNEL",
    notInThisChannelTitle: String = "NOT IN THIS CHANNEL",
    notInScopeTitle: String? = null
): ComposerMemberSuggestionPanelModel {
    val groups = members.composerMemberSuggestionGroups()
    val entries = mutableListOf<ComposerMemberSuggestionPanelEntry>()
    var selectableIndex = 0
    fun addRows(prefix: String, rows: List<KuiklyComposerMemberItem>, inChannel: Boolean) {
        rows.forEach { member ->
            entries +=
                ComposerMemberSuggestionPanelEntry(
                    key = "$prefix-${member.composerMemberSuggestionKey()}",
                    member = member,
                    selectableIndex = selectableIndex,
                    inChannel = inChannel
                )
            selectableIndex += 1
        }
    }
    val hasThreadSection = groups.inThread.isNotEmpty()
    addRows(prefix = "thread", rows = groups.inThread, inChannel = true)
    if (hasThreadSection && groups.inMentionChannel.isNotEmpty()) {
        entries +=
            ComposerMemberSuggestionPanelEntry(
                key = "mention-channel-header",
                headerText = title ?: inThisChannelTitle,
                topDivider = true
            )
    }
    addRows(prefix = "mention-channel", rows = groups.inMentionChannel, inChannel = true)
    if (groups.notInMentionChannel.isNotEmpty()) {
        entries +=
            ComposerMemberSuggestionPanelEntry(
                key = "not-in-mention-channel-header",
                headerText =
                    notInScopeTitle
                        ?: title.notInMentionScopeHeader(
                            notInParentChannelTitle = notInParentChannelTitle,
                            notInThisChannelTitle = notInThisChannelTitle
                        ),
                topDivider = entries.isNotEmpty()
            )
    }
    addRows(prefix = "not-in-mention-channel", rows = groups.notInMentionChannel, inChannel = false)
    return ComposerMemberSuggestionPanelModel(
        title = if (hasThreadSection) inThreadTitle else title,
        entries = entries,
        selectableCount = selectableIndex
    )
}

private fun String?.notInMentionScopeHeader(
    notInParentChannelTitle: String = "NOT IN PARENT CHANNEL",
    notInThisChannelTitle: String = "NOT IN THIS CHANNEL"
): String =
    when (this) {
        "IN PARENT CHANNEL" -> notInParentChannelTitle
        "IN THIS CHANNEL" -> notInThisChannelTitle
        else -> notInThisChannelTitle
    }

@Composable
private fun ComposerSuggestionStatusPanel(
    title: String?,
    message: String
) {
    ComposerSuggestionPanel(
        title = title,
        showTitleDivider = false,
        panelHeight =
            composerSuggestionPanelContentHeight(
                itemCount = 1,
                rowMinHeight = ComposerSuggestionStatusRowMinHeight,
                includeHeader = title != null
            ),
        itemCount = 1,
        itemKey = { "status-$message" }
    ) {
        ComposerSuggestionStatusRow(message)
    }
}

@Composable
private fun ComposerSuggestionStatusRow(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ComposerSuggestionStatusRowMinHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message,
            style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ComposerSuggestionHeader(
    text: String,
    topDivider: Boolean = false,
    bottomDivider: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)) {
        if (topDivider) {
            ComposerSuggestionDivider()
        }
        Text(
            text = text,
            style =
                SlockType.Caption
                    .withSlockFontWeight(FontWeight.Bold)
                    .copy(color = SlockColor.TextMutedGray, letterSpacing = 1.1.sp),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (bottomDivider) {
            ComposerSuggestionDivider()
        }
    }
}

@Composable
private fun ComposerMemberSuggestionRow(
    member: KuiklyComposerMemberItem,
    inChannel: Boolean,
    selected: Boolean,
    agentStatusResolver: SlockAgentStatusResolver,
    onClick: () -> Unit
) {
    val description =
        member.description
            ?.takeIf { member.kind == MemberKind.Agent }
            ?.takeIf { it.isNotBlank() }
    val secondary = description ?: member.sourceServerLabel?.takeIf { it.isNotBlank() }
    val muted = !inChannel
    val contentColor =
        when {
            muted -> SlockColor.TextMutedGray
            else -> SlockColor.TextBlack
        }
    val handleColor =
        when {
            muted -> SlockColor.TextMutedGray
            else -> SlockColor.TextBlack.copy(alpha = 0.4f)
        }
    val secondaryColor =
        when {
            muted -> SlockColor.TextMutedGray
            selected -> SlockColor.TextBlack.copy(alpha = 0.7f)
            else -> SlockColor.TextBlack.copy(alpha = 0.5f)
        }
    ComposerSuggestionInteractiveRow(
        selected = selected,
        muted = muted,
        selectedColor = SlockColor.BrandYellow,
        minHeight = ComposerMemberSuggestionRowMinHeight,
        mutedOverlay = true,
        onClick = onClick
    ) {
        Box(
            modifier =
                Modifier
                    .size(ComposerMemberSuggestionAvatarSize)
                    .background(if (muted) SlockColor.SurfaceCream else SlockColor.SurfaceWhite),
            contentAlignment = Alignment.Center
        ) {
            SlockAvatar(
                avatarWireValue = member.avatarWireValue,
                label = member.avatarLabel.ifBlank { member.displayName },
                avatarSize = ComposerMemberSuggestionAvatarSize,
                borderWidth = SlockStroke.Thin
            )
        }
        Spacer(modifier = Modifier.width(ComposerMemberSuggestionAvatarNameGap))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (muted) {
                    SlockLucideIcon(
                        SlockLucideIconName.UserMinus,
                        modifier = Modifier.size(12.dp),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
                Text(
                    text = member.displayName,
                    style = SlockType.Button.copy(color = contentColor),
                    color = contentColor,
                    modifier = Modifier.widthIn(max = if (secondary != null) 96.dp else 170.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (inChannel && member.kind == MemberKind.Agent) {
                    rememberSlockAgentStatusIndicatorState(agentStatusResolver, member.memberId)?.let { status ->
                        ComposerAgentStatusDot(status)
                    }
                }
                if (secondary != null) {
                    Text(
                        text = secondary,
                        style = SlockType.Caption.copy(color = secondaryColor),
                        color = secondaryColor,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "@${member.handle}",
                style = SlockType.Caption.copy(color = handleColor, fontFamily = SlockMonoFamily),
                color = handleColor,
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposerAgentStatusDot(status: SlockAgentStatusIndicatorState) {
    SlockStatusIndicator(
        color = status.color,
        size = ComposerAgentActivityDotSize,
        borderColor = SlockColor.BorderBlack,
        borderWidth = SlockStroke.Thin,
        pulsing = status.pulsing
    )
}

@Composable
private fun MentionActionNotice(notice: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.TextBlack.copy(alpha = 0.04f))
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = notice,
            style = SlockType.Caption.copy(
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = SlockColor.TextBlack.copy(alpha = 0.7f)
            ),
            color = SlockColor.TextBlack.copy(alpha = 0.7f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PendingMentionActionBanner(
    actions: List<KuiklyPendingMentionActionItem>,
    memberSuggestions: List<KuiklyComposerMemberItem>,
    channelLabel: String,
    actionStates: Map<String, String>,
    removingIds: Set<String>,
    executingIds: Set<String>,
    executingStates: Map<String, String>,
    onInvite: (String) -> Unit,
    onNotify: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)) {
        Box(modifier = Modifier.matchParentSize().offset(x = 2f, y = 2f).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 172.dp)
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Keep mention actions one-by-one so follow-up rows do not clip controls above the composer.
            actions.take(1).forEach { action ->
                PendingMentionActionRow(
                    action = action,
                    targetSuggestion = action.matchingTargetSuggestion(memberSuggestions),
                    channelLabel = action.channelName.toComposerChannelMentionLabel() ?: channelLabel,
                    executing = action.resolutionId in executingIds,
                    localState = actionStates[action.resolutionId],
                    removing = action.resolutionId in removingIds,
                    executingState = executingStates[action.resolutionId],
                    onInvite = { onInvite(action.resolutionId) },
                    onNotify = { onNotify(action.resolutionId) },
                    onDismiss = { onDismiss(action.resolutionId) }
                )
            }
        }
    }
}

@Composable
private fun PendingMentionActionRow(
    action: KuiklyPendingMentionActionItem,
    targetSuggestion: KuiklyComposerMemberItem?,
    channelLabel: String,
    executing: Boolean,
    localState: String?,
    removing: Boolean,
    executingState: String?,
    onInvite: () -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = action.targetLabel()
    val targetAvatarLabel =
        targetSuggestion?.avatarLabel
            ?.takeIf { it.isNotBlank() }
            ?: targetSuggestion?.displayName
                ?.takeIf { it.isNotBlank() }
            ?: action.targetInitial()
    val targetAvatarWireValue = action.targetAvatarWireValue() ?: targetSuggestion?.avatarWireValue
    val canInvite = action.supportsMentionAction("invite") && localState == null
    val canNotify = action.supportsMentionAction("notify") && localState == null
    val contentColor = if (removing) SlockColor.TextBlack.copy(alpha = 0.45f) else SlockColor.TextBlack
    val mutedColor = if (removing) SlockColor.TextMutedGray.copy(alpha = 0.45f) else SlockColor.TextMutedGray
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.UserPlus,
                    modifier = Modifier.size(16.dp),
                    color = if (removing) "#777777" else SlockColor.TextMutedGrayArgb
                )
            }
            SlockAvatar(
                avatarWireValue = targetAvatarWireValue,
                label = targetAvatarLabel,
                avatarSize = 24.dp,
                borderWidth = SlockStroke.Default
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = targetLabel,
                    style = SlockType.Button.copy(fontSize = 12.sp),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = action.localizedPendingMentionActionDescription(channelLabel, localState),
                    style = SlockType.Caption.copy(color = mutedColor),
                    color = mutedColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (localState == "added") {
                ComposerTextChip(
                    stringResource(MR.strings.thread_composer_mention_added_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = SlockLucideIconName.Check,
                    onClick = {}
                )
            } else if (canInvite) {
                ComposerTextChip(
                    if (executingState == "added") {
                        stringResource(MR.strings.thread_composer_mention_adding_chip)
                    } else {
                        stringResource(MR.strings.thread_composer_mention_add_chip)
                    },
                    variant = ComposerTextChipVariant.Pink,
                    enabled = !executing,
                    onClick = onInvite
                )
            }
            if (localState == "notified") {
                ComposerTextChip(
                    stringResource(MR.strings.thread_composer_mention_notified_chip),
                    variant = ComposerTextChipVariant.Disabled,
                    enabled = false,
                    leadingIcon = SlockLucideIconName.Check,
                    onClick = {}
                )
            } else if (canNotify) {
                ComposerTextChip(
                    if (executingState == "notified") {
                        stringResource(MR.strings.thread_composer_mention_notifying_chip)
                    } else {
                        stringResource(MR.strings.thread_composer_mention_notify_chip)
                    },
                    variant = ComposerTextChipVariant.White,
                    enabled = !executing,
                    onClick = onNotify
                )
            }
            ComposerTextChip(
                stringResource(MR.strings.common_dismiss),
                variant = ComposerTextChipVariant.MutedText,
                enabled = !executing,
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun ComposerAttachmentTray(
    attachments: List<KuiklyPendingAttachmentItem>,
    enabled: Boolean,
    onEvent: (action: String, attachmentId: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ThreadComposerAttachmentRowGapDp.dp)
    ) {
        composerAttachmentRows(attachments).forEach { rowAttachments ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ThreadComposerAttachmentRowGapDp.dp)
            ) {
                rowAttachments.forEach { attachment ->
                    ComposerAttachmentPreviewChip(
                        attachment = attachment,
                        enabled = enabled,
                        onPreview = { onEvent("previewAttachment", attachment.id) },
                        onRemove = { onEvent("removeAttachment", attachment.id) },
                        onRetry = { onEvent("retryAttachments", attachment.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentPreviewChip(
    attachment: KuiklyPendingAttachmentItem,
    enabled: Boolean,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    val isFailed = attachment.status == "failed"
    val isUploading = attachment.status != "ready" && !isFailed
    val isImage = attachment.isComposerImageAttachment()
    val imagePreviewUri = attachment.previewUri?.takeIf { it.isNotBlank() }
    Box(modifier = Modifier.width(attachment.composerAttachmentPreviewWidthDp().dp).height(ThreadComposerAttachmentTileSize)) {
        if (isImage) {
            ComposerImageAttachmentPreview(
                attachment = attachment,
                imagePreviewUri = imagePreviewUri,
                previewEnabled = enabled && imagePreviewUri != null && attachment.status == "ready",
                onPreview = onPreview
            )
        } else {
            ComposerFileAttachmentPreview(attachment = attachment)
        }
        if (isUploading) {
            ComposerAttachmentUploadingOverlay(attachment = attachment)
        }
        if (isFailed && enabled) {
            ComposerAttachmentFailedOverlay(onRetry = onRetry)
        }
        if (enabled) {
            ComposerAttachmentRemoveButton(onRemove = onRemove)
        }
    }
}

@Composable
private fun ComposerImageAttachmentPreview(
    attachment: KuiklyPendingAttachmentItem,
    imagePreviewUri: String?,
    previewEnabled: Boolean,
    onPreview: () -> Unit
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(enabled = previewEnabled, onClick = onPreview)
                .slockSquareCorners()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        if (imagePreviewUri != null) {
            SlockAspectFitImagePreviewSurface(
                source = imagePreviewUri,
                contentDescription = attachment.fileName,
                modifier = Modifier.fillMaxSize(),
                borderWidth = 0.dp,
                contentScale = SlockImageThumbnailContentScale
            )
        } else {
            SlockLucideIcon(SlockLucideIconName.Image, modifier = Modifier.size(20.dp), color = "#FF000000")
        }
    }
}

@Composable
private fun ComposerFileAttachmentPreview(attachment: KuiklyPendingAttachmentItem) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .slockSquareCorners()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.FileText,
            modifier = Modifier.size(16.dp),
            color = "#B3000000"
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = attachment.fileName,
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(
                    stringResource(MR.strings.thread_composer_attachment_file),
                    attachment.displaySizeLabel(),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 10.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposerAttachmentUploadingOverlay(attachment: KuiklyPendingAttachmentItem) {
    val progress = attachment.uploadProgress.coerceIn(0, 100)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .slockSquareCorners()
                .background(SlockColor.SurfaceWhite.copy(alpha = 0.86f))
                .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SlockLucideIcon(SlockLucideIconName.LoaderCircle, modifier = Modifier.size(16.dp), color = "#FF000000", spinning = true)
            Text(
                text = if (progress > 0) "$progress%" else stringResource(MR.strings.thread_composer_attachment_uploading),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 11.sp),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        }
        if (progress > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .slockSquareCorners()
                        .background(SlockColor.ShadowBlack.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress.coerceAtLeast(4) / 100f)
                            .height(4.dp)
                            .slockSquareCorners()
                            .background(SlockColor.AccentPink)
                )
            }
        }
    }
}

@Composable
private fun ComposerAttachmentFailedOverlay(onRetry: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onRetry)
                .slockSquareCorners()
                // deliberate near-opaque failure scrim over the attachment thumbnail; triaged token-audit #1066
                .background(SlockColor.AccentOrange.copy(alpha = 0.92f))
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(MR.strings.thread_composer_attachment_upload_failed),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(MR.strings.thread_composer_attachment_tap_retry),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BoxScope.ComposerAttachmentRemoveButton(onRemove: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(ThreadComposerAttachmentCloseSize)
                .align(Alignment.TopEnd)
                .offset(x = 5f, y = -5f)
                .background(SlockColor.ShadowBlack, RoundedCornerShape(ThreadComposerAttachmentCloseSize / 2))
                .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(SlockLucideIconName.X, modifier = Modifier.size(12.dp), color = "#FFFFFFFF")
    }
}

internal fun composerAttachmentRows(
    attachments: List<KuiklyPendingAttachmentItem>,
    maxWidthDp: Int = ThreadComposerAttachmentTrayMaxWidthDp
): List<List<KuiklyPendingAttachmentItem>> {
    if (attachments.isEmpty()) return emptyList()
    val rows = mutableListOf<MutableList<KuiklyPendingAttachmentItem>>()
    var row = mutableListOf<KuiklyPendingAttachmentItem>()
    var rowWidthDp = 0
    attachments.forEach { attachment ->
        val widthDp = attachment.composerAttachmentPreviewWidthDp()
        val nextWidthDp =
            if (row.isEmpty()) {
                widthDp
            } else {
                rowWidthDp + ThreadComposerAttachmentRowGapDp + widthDp
            }
        if (row.isNotEmpty() && nextWidthDp > maxWidthDp) {
            rows += row
            row = mutableListOf()
            rowWidthDp = 0
        }
        row += attachment
        rowWidthDp =
            if (rowWidthDp == 0) {
                widthDp
            } else {
                rowWidthDp + ThreadComposerAttachmentRowGapDp + widthDp
            }
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

internal fun KuiklyPendingAttachmentItem.composerAttachmentPreviewWidthDp(): Int =
    if (isComposerImageAttachment()) {
        ThreadComposerAttachmentImageTileSizeDp
    } else {
        ThreadComposerAttachmentFileTileWidthDp
    }

internal fun KuiklyPendingAttachmentItem.isComposerImageAttachment(): Boolean {
    val normalizedMime = mimeType?.substringBefore(";")?.trim()?.lowercase().orEmpty()
    return normalizedMime.startsWith("image/") && normalizedMime != "image/svg+xml"
}

@Composable
private fun ComposerSuggestionPanel(
    title: String?,
    showTitleDivider: Boolean = true,
    panelHeight: Dp,
    itemCount: Int,
    itemKey: (Int) -> Any,
    itemContent: @Composable (Int) -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(panelHeight)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(panelHeight)
        ) {
            if (title != null) {
                item(key = "header-$title", contentType = "header") {
                    ComposerSuggestionHeader(
                        text = title,
                        bottomDivider = showTitleDivider
                    )
                }
            }
            items(itemCount, key = itemKey) { index ->
                itemContent(index)
            }
        }
    }
}

@Composable
private fun ComposerChannelSuggestionRow(
    channel: KuiklyComposerChannelItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    ComposerSuggestionInteractiveRow(
        selected = selected,
        muted = false,
        selectedColor = SlockColor.AccentPink,
        minHeight = ComposerChannelSuggestionRowMinHeight,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(ComposerChannelSuggestionIconSize)
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.Hash,
                    modifier = Modifier.size(ComposerChannelSuggestionGlyphSize),
                    color = SlockColor.TextBlackArgb
                )
            }
            Text(
                text = channel.name,
                style = SlockType.Button.copy(color = SlockColor.TextBlack, fontSize = 13.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            channel.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(color = SlockColor.TextBlack.copy(alpha = 0.4f)),
                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } ?: Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComposerSuggestionInteractiveRow(
    selected: Boolean,
    muted: Boolean,
    selectedColor: Color,
    minHeight: Dp,
    mutedOverlay: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val background =
        when {
            selected -> selectedColor
            muted -> SlockColor.SurfaceWhite
            else -> SlockColor.SurfaceWhite
        }
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }
            if (muted && mutedOverlay) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(SlockColor.SurfaceWhite.copy(alpha = 0.52f))
                )
            }
        }
    }
}

@Composable
private fun ComposerSuggestionDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
}

@Composable
private fun ComposerSuggestionRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ComposerMemberSuggestionRowMinHeight)
                .clickable(onClick = onClick)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SlockType.Button.copy(color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ComposerIconButton(
    icon: SlockLucideIconName,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    backgroundColor: Color = SlockColor.SurfaceWhite,
    disabledBackgroundColor: Color = SlockColor.EmptyStateGray,
    disabledAlpha: Float = 1f,
    disabledIconColor: String = "#4D000000",
    iconSpinning: Boolean = false,
    onClick: () -> Unit
) {
    SlockBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = backgroundColor,
        disabledBackgroundColor = disabledBackgroundColor,
        disabledAlpha = disabledAlpha,
        disabledBorderColor = SlockColor.BorderBlack,
        disabledShadowColor = SlockColor.ShadowBlack,
        disabledIconColor = disabledIconColor,
        size = ThreadComposerIconButtonSize,
        shadowOffset = ThreadComposerActionShadowOffset,
        iconSize = ThreadComposerIconSize,
        iconSpinning = iconSpinning
    )
}

@Composable
private fun ComposerSendButton(
    enabled: Boolean,
    sending: Boolean,
    onClick: () -> Unit
) {
    val icon = if (sending) SlockLucideIconName.RefreshCw else SlockLucideIconName.Send
    if (sending) {
        ComposerIconButton(
            icon = icon,
            enabled = enabled,
            backgroundColor = SlockColor.AccentPink,
            disabledBackgroundColor = SlockColor.AccentPink,
            disabledAlpha = 1f,
            disabledIconColor = "#FF000000",
            iconSpinning = true,
            onClick = onClick
        )
        return
    }
    SlockBrutalIconButton(
        iconName = icon,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = SlockColor.AccentPink,
        disabledBackgroundColor = ThreadComposerDisabledSendBackground,
        disabledAlpha = 1f,
        disabledBorderColor = ThreadComposerDisabledSendBorder,
        disabledShadowColor = ThreadComposerDisabledSendShadow,
        disabledIconColor = ThreadComposerDisabledSendIconArgb,
        size = ThreadComposerIconButtonSize,
        shadowOffset = ThreadComposerActionShadowOffset,
        iconSize = ThreadComposerIconSize
    )
}

internal fun threadComposerVisibleLeftActionGapDp(): Float =
    (ThreadComposerLeftActionGroupSpacing + ThreadComposerActionShadowOffset).value

private enum class ComposerTextChipVariant {
    Pink,
    White,
    Disabled,
    MutedText
}

@Composable
private fun ComposerTextChip(
    text: String,
    variant: ComposerTextChipVariant,
    enabled: Boolean = true,
    leadingIcon: SlockLucideIconName? = null,
    onClick: () -> Unit
) {
    if (variant == ComposerTextChipVariant.MutedText) {
        val mutedColor = SlockColor.TextBlack.copy(alpha = if (enabled) 0.45f else 0.3f)
        Text(
            text = text,
            modifier =
                Modifier
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            style = SlockType.Caption.copy(color = mutedColor),
            color = mutedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }

    if (variant == ComposerTextChipVariant.Disabled) {
        Row(
            modifier =
                Modifier
                    .background(SlockColor.TextBlack.copy(alpha = 0.04f))
                    .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Success-state marks are Lucide icons, not "✓" text glyphs
            // (task #494 follow-up — the glyph used to live in the string
            // resource itself).
            leadingIcon?.let {
                SlockLucideIcon(
                    name = it,
                    color = SlockColor.TextMutedGrayArgb,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = text,
                style = SlockType.Caption.copy(color = SlockColor.TextBlack.copy(alpha = 0.4f)),
                color = SlockColor.TextBlack.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        backgroundColor = if (variant == ComposerTextChipVariant.Pink) SlockColor.AccentPink else SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 26.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack)
    )
}

internal data class KuiklyComposerSuggestionEdit(
    val text: String,
    val cursor: Int
)

internal fun applyKuiklyComposerSuggestionEdit(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    triggerStart: Int,
    replacement: String
): KuiklyComposerSuggestionEdit? {
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    if (triggerStart < 0 || triggerStart > safeStart) return null
    val suffixStart =
        if (replacement.lastOrNull()?.isWhitespace() == true && safeEnd < text.length && text[safeEnd].isWhitespace()) {
            safeEnd + 1
        } else {
            safeEnd
        }
    val nextText = text.substring(0, triggerStart) + replacement + text.substring(suffixStart)
    return KuiklyComposerSuggestionEdit(
        text = nextText,
        cursor = triggerStart + replacement.length
    )
}

internal fun commitKuiklyComposerSuggestionToken(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    triggerStart: Int
): KuiklyComposerSuggestionEdit? {
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    if (triggerStart < 0 || triggerStart > safeStart) return null
    val nextText =
        if (safeEnd < text.length && text[safeEnd].isWhitespace()) {
            text
        } else {
            text.substring(0, safeEnd) + " " + text.substring(safeEnd)
        }
    return KuiklyComposerSuggestionEdit(
        text = nextText,
        cursor = (safeEnd + 1).coerceIn(0, nextText.length)
    )
}

internal fun applyKuiklyComposerDirectMentionInsert(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    handle: String
): KuiklyComposerSuggestionEdit? {
    val token = handle.toThreadMentionToken() ?: return null
    val safeStart = selectionStart.coerceIn(0, text.length)
    val safeEnd = selectionEnd.coerceIn(safeStart, text.length)
    val prefix = text.substring(0, safeStart)
    val suffix = text.substring(safeEnd)
    val leadingSpace = if (prefix.isNotEmpty() && !prefix.last().isWhitespace()) " " else ""
    val trailingSpace = if (suffix.isNotEmpty() && suffix.first().isWhitespace()) "" else " "
    val mention = "$leadingSpace@$token$trailingSpace"
    val nextText = prefix + mention + suffix
    return KuiklyComposerSuggestionEdit(
        text = nextText,
        cursor = prefix.length + mention.length
    )
}

internal fun detectKuiklyComposerSuggestionTrigger(
    text: String,
    cursor: Int = text.length
): KuiklyComposerSuggestionTrigger {
    if (text.isBlank()) {
        return KuiklyComposerSuggestionTrigger.None
    }
    val beforeCursor = text.substring(0, cursor.coerceIn(0, text.length))
    var triggerIndex = -1
    var triggerChar = ' '
    for (i in beforeCursor.indices.reversed()) {
        val current = beforeCursor[i]
        if (current.isWhitespace()) {
            break
        }
        if (current == '@' || current == '#') {
            triggerIndex = i
            triggerChar = current
            break
        }
    }
    if (triggerIndex < 0) {
        return KuiklyComposerSuggestionTrigger.None
    }
    val query = beforeCursor.substring(triggerIndex + 1)
    return when (triggerChar) {
        '@' -> KuiklyComposerSuggestionTrigger.MemberTrigger(query, triggerIndex)
        '#' -> KuiklyComposerSuggestionTrigger.ChannelTrigger(query, triggerIndex)
        else -> KuiklyComposerSuggestionTrigger.None
    }
}

internal fun threadComposerSuggestionTrigger(
    text: String,
    cursor: Int = text.length,
    suggestionsEnabled: Boolean = true
): KuiklyComposerSuggestionTrigger =
    if (suggestionsEnabled) {
        detectKuiklyComposerSuggestionTrigger(text, cursor)
    } else {
        KuiklyComposerSuggestionTrigger.None
    }
