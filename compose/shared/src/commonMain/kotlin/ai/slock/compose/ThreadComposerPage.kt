package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.markdown.traceMarkdownSection
import ai.slock.compose.markdown.traceMarkdownSectionNonComposable
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val THREAD_COMPOSER_VISUAL_PAGE = "compose_thread_composer_visual"
private const val THREAD_COMPOSER_PAGE_DATA_EVENT = "slockPageData"
private val ThreadComposerStandaloneTopPadding = 10.dp
private val ThreadComposerStandaloneBottomPadding = 8.dp
private val ThreadComposerTextFieldMinHeight = 20.dp
private val ThreadComposerToolbarMinHeight = 28.dp
private val ThreadComposerToolbarEndInset = 18.dp
private val ThreadComposerIconButtonSize = 26.dp
private val ThreadComposerIconSize = 14.dp
private val ThreadComposerDisabledSendBackground = Color(0xFFFFD8E5)
private val ThreadComposerDisabledSendBorder = Color(0xFFB3B3B3)
private val ThreadComposerDisabledSendShadow = Color(0xFFB8B8B8)
private const val ThreadComposerDisabledSendIconArgb = "#FFB3B3B3"
private val ComposerSuggestionPanelMaxHeight = 240.dp
private val ComposerSuggestionRowMinHeight = 46.dp
private val ThreadComposerVisualComponentWidth = 342.dp
private val ThreadComposerVisualComponentMinHeight = 100.dp
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
        "memberSuggestions",
        "channelSuggestions",
        "hookLabels"
    )

// Connected visual-test entry only. Product Thread renders ThreadComposerContent inside ThreadPage.
@Page(THREAD_COMPOSER_VISUAL_PAGE, supportInLocal = true)
internal class ComposeThreadComposerVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadComposerScreen() }
    }
}

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
    val executingMentionActionIds: List<String> = emptyList(),
    val memberSuggestions: List<KuiklyComposerMemberItem> = emptyList(),
    val channelSuggestions: List<KuiklyComposerChannelItem> = emptyList(),
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
    val showTaskButton: Boolean = true
) {
    val sendChannelId: String?
        get() = streamChannelId?.takeIf { it.isNotBlank() } ?: channelId.takeIf { it.isNotBlank() }

    val mentionScopeChannelId: String?
        get() =
            streamChannelId?.takeIf { it.isNotBlank() }
                ?: mentionChannelId?.takeIf { it.isNotBlank() }
                ?: parentChannelId?.takeIf { it.isNotBlank() }
                ?: channelId.takeIf { it.isNotBlank() }
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
    val executingMentionActionIds: List<String> = emptyList(),
    val memberSuggestions: List<KuiklyComposerMemberItem> = emptyList(),
    val channelSuggestions: List<KuiklyComposerChannelItem> = emptyList(),
    val hookLabels: List<String> = emptyList()
)

@Serializable
internal data class KuiklyPendingAttachmentItem(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val mimeType: String? = null,
    val status: String = "ready"
)

@Serializable
internal data class KuiklyPendingMentionActionItem(
    val resolutionId: String,
    val messageId: String? = null,
    val targetType: String = "unknown",
    val targetHandle: String = "",
    val reason: String = "",
    val availableActions: List<String> = emptyList(),
    val expiresAt: String? = null
)

@Serializable
internal data class KuiklyComposerMemberItem(
    val displayName: String,
    val handle: String,
    val inMentionChannel: Boolean = true
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

data class SharedThreadComposerAttachment(
    val id: String,
    val fileName: String,
    val sizeLabel: String = "",
    val mimeType: String? = null,
    val status: String = "ready"
)

data class SharedThreadComposerMemberSuggestion(
    val displayName: String,
    val handle: String
)

data class SharedThreadComposerChannelSuggestion(
    val name: String,
    val subtitle: String? = null
)

private sealed class KuiklyComposerSuggestionTrigger {
    data class MemberTrigger(val query: String, val triggerStart: Int) : KuiklyComposerSuggestionTrigger()
    data class ChannelTrigger(val query: String, val triggerStart: Int) : KuiklyComposerSuggestionTrigger()
    object None : KuiklyComposerSuggestionTrigger()
}

@Composable
private fun ThreadComposerScreen() {
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
    val payload =
        rememberThreadComposerPayload(
            payload = payloadText,
            fallbackPageData = params.toString(),
            instanceId = instanceId,
            visualFixture = visualFixture
        )
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
            ThreadComposerContent(payload = payload, onEvent = { action, _, _, attachmentId ->
                bridge.log(
                    "thread_composer_visual_event instanceId=${payload.instanceId} action=$action attachmentId=${attachmentId.orEmpty()}"
                )
            })
        }
    }
}

@Composable
private fun rememberThreadComposerPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String,
    visualFixture: String
): KuiklyThreadComposerPayload =
    remember(payload, fallbackPageData, instanceId, visualFixture) {
        visualFixture.takeIf { it.isNotBlank() }?.let { fixture ->
            threadComposerVisualFixture(fixture, instanceId)
        } ?: parseThreadComposerPayload(
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
            seedText = "Review the Android composer crop before release.",
            syncNonce = 1,
            focusRequestNonce = 0,
            placeholder = "Message design",
            memberSuggestions =
                listOf(
                    KuiklyComposerMemberItem(displayName = "Artin", handle = "artin", inMentionChannel = true),
                    KuiklyComposerMemberItem(
                        displayName = "Android Developer",
                        handle = "Android-Developer",
                        inMentionChannel = false
                    )
                ),
            channelSuggestions =
                listOf(
                    KuiklyComposerChannelItem(name = "design", subtitle = "Product and UI decisions"),
                    KuiklyComposerChannelItem(name = "android-artifacts", subtitle = "Builds and screenshots")
                )
        )
    return when (fixture) {
        "empty" -> base.copy(seedText = "")
        "as_task_selected" -> base.copy(seedAsTask = true, seedText = "Review the Android composer crop")
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
    parentChannelId: String? = null
): ThreadComposerSurface =
    ThreadComposerSurface(
        kind = surfaceKind,
        channelId = channelId,
        streamChannelId = streamChannelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        mentionChannelId = mentionChannelId,
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
        executingMentionActionIds = executingMentionActionIds,
        memberSuggestions = memberSuggestions,
        channelSuggestions = channelSuggestions,
        hookLabels = hookLabels
    )

internal fun KuiklyThreadScreenPayload.resolveThreadComposer(): KuiklyThreadComposerPayload {
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
                displayName = title,
                fallbackId = sourceThreadId ?: instanceId
            )
        } else {
            composer.placeholder
        }
    return composer.copy(
        instanceId = composer.instanceId.ifBlank { instanceId },
        placeholder = resolvedPlaceholder,
        enabled = canSend && composer.enabled,
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

@Composable
internal fun ThreadComposerContent(
    payload: KuiklyThreadComposerPayload,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding
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
        contentPaddingBottom = contentPaddingBottom
    )
}

@Composable
internal fun ThreadComposerContent(
    surface: ThreadComposerSurface,
    state: ThreadComposerUiState,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {},
    contentPaddingTop: Dp = ThreadComposerStandaloneTopPadding,
    contentPaddingBottom: Dp = ThreadComposerStandaloneBottomPadding
) {
    // #192 trace scope extension: wrap the full composer render. IME-lift / state
    // updates / suggestion list filtering are all under this; per-phase breakdown
    // can come later if this marker shows hot. bodyLength proxy uses seedText length.
    traceMarkdownSection(SlockComposerTraceNames.COMPOSER_RENDER, state.seedText.length) {
    var fieldValue by remember(state.instanceId) {
        mutableStateOf(state.seedText)
    }
    val text = fieldValue
    var asTask by remember(state.instanceId) { mutableStateOf(state.seedAsTask) }
    var focused by remember(state.instanceId) { mutableStateOf(false) }
    var handledFocusRequestNonce by remember(state.instanceId) { mutableStateOf(state.focusRequestNonce) }
    val shouldAutoFocus = state.focusRequestNonce > handledFocusRequestNonce
    LaunchedEffect(state.instanceId, state.syncNonce) {
        fieldValue = state.seedText
        asTask = state.seedAsTask
        focused = false
    }
    val suggestionTrigger = remember(text) { detectKuiklyComposerSuggestionTrigger(text) }
    if (text.isNotBlank()) {
        println("[composer] trigger t=" + text.take(30) + " trigger=" + suggestionTrigger.toString())
    }
    val filteredMemberSuggestions =
        remember(suggestionTrigger, state.memberSuggestions) {
            when (val trigger = suggestionTrigger) {
                is KuiklyComposerSuggestionTrigger.MemberTrigger -> {
                    val query = trigger.query.lowercase()
                    state.memberSuggestions.filter { member ->
                        query.isBlank() ||
                            member.displayName.lowercase().contains(query) ||
                            member.handle.lowercase().contains(query)
                    }
                }

                else -> emptyList()
            }
        }
    val filteredChannelSuggestions =
        remember(suggestionTrigger, state.channelSuggestions) {
            when (val trigger = suggestionTrigger) {
                is KuiklyComposerSuggestionTrigger.ChannelTrigger -> {
                    val query = trigger.query.lowercase()
                    state.channelSuggestions.filter { channel ->
                        query.isBlank() ||
                            channel.name.lowercase().contains(query) ||
                            channel.subtitle.orEmpty().lowercase().contains(query)
                    }
                }

                else -> emptyList()
            }
        }
    val showMemberSuggestions = focused && filteredMemberSuggestions.isNotEmpty()
    val showChannelSuggestions = focused && filteredChannelSuggestions.isNotEmpty()
    if (suggestionTrigger !is KuiklyComposerSuggestionTrigger.None) {
        println("[composer] suggestions focused=" + focused + " memberCount=" + filteredMemberSuggestions.size + " channelCount=" + filteredChannelSuggestions.size + " showMember=" + showMemberSuggestions + " showChannel=" + showChannelSuggestions)
    }
    val composerEnabled = state.enabled && surface.sendChannelId != null
    val canSend =
        composerEnabled &&
            state.composerMode != "sending" &&
            state.composerMode != "uploading" &&
            state.composerMode != "no_permission" &&
            (text.isNotBlank() || state.pendingAttachments.isNotEmpty())

    fun emitChange(nextText: String = text, nextAsTask: Boolean = asTask) {
        onEvent("change", nextText, nextAsTask, null)
    }

    fun applyMemberSuggestion(member: KuiklyComposerMemberItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger ?: return
        val before = fieldValue.substring(0, trigger.triggerStart)
        fieldValue = "$before@${member.handle} "
        emitChange(nextText = fieldValue)
    }

    fun applyChannelSuggestion(channel: KuiklyComposerChannelItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger ?: return
        val before = fieldValue.substring(0, trigger.triggerStart)
        fieldValue = "$before#${channel.name} "
        emitChange(nextText = fieldValue)
    }

    Box(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
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
                    text = status,
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
                Text(
                    text = notice,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .background(SlockColor.SurfaceCream)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.pendingMentionActions.isNotEmpty()) {
                PendingMentionActionBanner(
                    actions = state.pendingMentionActions,
                    channelLabel = surface.mentionScopeChannelLabel(),
                    executingIds = state.executingMentionActionIds.toSet(),
                    onInvite = { resolutionId -> onEvent("inviteMention", resolutionId, null, null) },
                    onNotify = { resolutionId -> onEvent("notifyMention", resolutionId, null, null) },
                    onDismiss = { resolutionId -> onEvent("dismissMention", resolutionId, null, null) }
                )
            }
            if (showMemberSuggestions) {
                ComposerMemberSuggestionPanel(
                    title = surface.memberSuggestionTitle(),
                    members = filteredMemberSuggestions,
                    onClick = { applyMemberSuggestion(it) }
                )
            } else if (showChannelSuggestions) {
                ComposerSuggestionPanel(
                    title = "CHANNELS",
                    itemCount = filteredChannelSuggestions.size,
                    itemKey = { index -> "channel-$index-${filteredChannelSuggestions[index].name}" }
                ) { index ->
                    val channel = filteredChannelSuggestions[index]
                    ComposerSuggestionRow(
                        leading = {
                            Box(
                                modifier = Modifier.size(28.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("#", style = SlockType.Button.copy(fontSize = 14.sp), color = SlockColor.TextBlack)
                            }
                        },
                        title = channel.name,
                        subtitle = channel.subtitle.orEmpty(),
                        onClick = { applyChannelSuggestion(channel) }
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier.matchParentSize().offset(x = 2f, y = 2f).background(SlockColor.ShadowBlack)
                )
                Column(
                    modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = fieldValue,
                        placeholder = state.placeholder,
                        autoFocus = shouldAutoFocus,
                        onValueChange = { next ->
                            fieldValue = next
                            println("[composer] onValueChange len=" + next.length + " t=" + next.take(20))
                            emitChange(nextText = next)
                        },
                        onFocus = {
                            focused = true
                            if (shouldAutoFocus) {
                                handledFocusRequestNonce = state.focusRequestNonce
                            }
                        },
                        onBlur = {
                            focused = false
                            onEvent("blur", null, null, null)
                        },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSend) {
                                    onEvent("send", null, null, null)
                                    fieldValue = ""
                                    asTask = false
                                }
                            }
                        ),
                        textStyle = SlockType.Input.copy(
                            color = if (composerEnabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                            fontSize = 16.sp,
                            lineHeight = 20.sp
                        ),
                        keyboardHeightChange = { params ->
                            onKeyboardHeightChange(params.height.coerceAtLeast(0f))
                        },
                        placeholderColor = SlockColor.TextMutedGray,
                        cursorBrush = SolidColor(SlockColor.TextBlack),
                        // React mobile uses a one-line textarea; keep the toolbar as the touch target.
                        modifier = Modifier.fillMaxWidth().heightIn(min = ThreadComposerTextFieldMinHeight)
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = ThreadComposerToolbarMinHeight).padding(end = ThreadComposerToolbarEndInset),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = ThreadComposerToolbarMinHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                ComposerAsTaskToggle(
                                    checked = asTask,
                                    enabled = composerEnabled
                                ) { checked ->
                                    asTask = checked
                                    onEvent("toggleAsTask", null, checked, null)
                                }
                            }
                            ComposerSendButton(
                                enabled = canSend,
                                sending = state.composerMode == "sending" || state.composerMode == "uploading"
                            ) {
                                onEvent("send", null, null, null)
                                fieldValue = ""
                                asTask = false
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

private fun ThreadComposerSurface.memberSuggestionTitle(): String =
    when (kind) {
        KuiklyThreadSurfaceKind.DirectMessage -> "IN THIS DM"
        KuiklyThreadSurfaceKind.ReplyThread -> "IN PARENT CHANNEL"
        else -> "IN THIS CHANNEL"
    }

internal fun ThreadComposerSurface.mentionScopeChannelLabel(): String =
    channelId
        .takeIf { it.isNotBlank() }
        ?.let { "#${it.removePrefix("#")}" }
        ?: "this channel"

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

@Composable
private fun ComposerMemberSuggestionPanel(
    title: String,
    members: List<KuiklyComposerMemberItem>,
    onClick: (KuiklyComposerMemberItem) -> Unit
) {
    val inChannel = members.filter { it.inMentionChannel }
    val notInChannel = members.filterNot { it.inMentionChannel }
    val headerIndex = inChannel.size
    val itemCount = inChannel.size + notInChannel.size + if (notInChannel.isNotEmpty()) 1 else 0
    ComposerSuggestionPanel(
        title = title,
        itemCount = itemCount,
        itemKey = { index ->
            when {
                index < inChannel.size -> "in-${inChannel[index].handle}"
                notInChannel.isNotEmpty() && index == headerIndex -> "not-in-header"
                else -> "not-in-${notInChannel[index - headerIndex - 1].handle}"
            }
        }
    ) { index ->
        when {
            index < inChannel.size -> {
                val member = inChannel[index]
                ComposerMemberSuggestionRow(member = member, inChannel = true, onClick = { onClick(member) })
            }
            notInChannel.isNotEmpty() && index == headerIndex -> {
                ComposerSuggestionSectionHeader("NOT IN THIS CHANNEL")
            }
            else -> {
                val member = notInChannel[index - headerIndex - 1]
                ComposerMemberSuggestionRow(member = member, inChannel = false, onClick = { onClick(member) })
            }
        }
    }
}

@Composable
private fun ComposerSuggestionSectionHeader(text: String) {
    Text(
        text = text,
        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
        color = SlockColor.TextMutedGray,
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun ComposerMemberSuggestionRow(
    member: KuiklyComposerMemberItem,
    inChannel: Boolean,
    onClick: () -> Unit
) {
    ComposerSuggestionRow(
        leading = {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .background(if (inChannel) SlockColor.BrandYellow else SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                if (inChannel) {
                    Text(
                        text = member.displayName.take(1).uppercase(),
                        style = SlockType.Button.copy(fontSize = 12.sp),
                        color = SlockColor.TextBlack
                    )
                } else {
                    SlockLucideIcon(SlockLucideIconName.User, modifier = Modifier.size(15.dp), color = SlockColor.TextMutedGrayArgb)
                }
            }
        },
        title = member.displayName,
        subtitle = "@${member.handle}",
        onClick = onClick
    )
}

@Composable
private fun PendingMentionActionBanner(
    actions: List<KuiklyPendingMentionActionItem>,
    channelLabel: String,
    executingIds: Set<String>,
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
            actions.forEach { action ->
                PendingMentionActionRow(
                    action = action,
                    channelLabel = channelLabel,
                    executing = action.resolutionId in executingIds,
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
    channelLabel: String,
    executing: Boolean,
    onInvite: () -> Unit,
    onNotify: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = action.targetLabel()
    val canInvite = action.supportsMentionAction("invite")
    val canNotify = action.supportsMentionAction("notify")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(SlockLucideIconName.User, modifier = Modifier.size(15.dp), color = SlockColor.TextMutedGrayArgb)
        Box(
            modifier = Modifier.size(22.dp).background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(action.targetInitial(), style = SlockType.Caption.withSlockFontWeight(FontWeight.Black), color = SlockColor.TextBlack)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = targetLabel,
                style = SlockType.Button.copy(fontSize = 12.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$targetLabel isn't in $channelLabel - won't be notified by default",
                style = SlockType.Caption.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canInvite) {
                ComposerTextChip(if (executing) "Adding" else "Add", enabled = !executing, onClick = onInvite)
            }
            if (canNotify) {
                ComposerTextChip(if (executing) "Notifying" else "Notify", enabled = !executing, onClick = onNotify)
            }
            ComposerTextChip("Dismiss", enabled = !executing, onClick = onDismiss)
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
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack).background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier.fillMaxWidth().border(SlockStroke.Thin, SlockColor.BorderBlack).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(24.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (attachment.mimeType?.startsWith("image/") == true) "IMG" else "FILE",
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 9.sp),
                        color = SlockColor.TextBlack
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = attachment.fileName,
                        style = SlockType.Button.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = attachment.statusLabel(),
                        style = SlockType.Caption.copy(
                            color = if (attachment.status == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray
                        ),
                        color = if (attachment.status == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (attachment.status == "failed" && enabled) {
                    ComposerTextChip("Retry") { onEvent("retryAttachments", attachment.id) }
                }
                if (enabled) {
                    ComposerTextChip("Remove") { onEvent("removeAttachment", attachment.id) }
                }
            }
        }
    }
}

@Composable
private fun ComposerSuggestionPanel(
    title: String,
    itemCount: Int,
    itemKey: (Int) -> Any,
    itemContent: @Composable (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack).background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = title,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = ComposerSuggestionPanelMaxHeight)
        ) {
            items(itemCount, key = itemKey) { index ->
                itemContent(index)
            }
        }
    }
}

@Composable
private fun ComposerSuggestionRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ComposerSuggestionRowMinHeight)
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
        shadowOffset = 2.dp,
        iconSize = ThreadComposerIconSize
    )
}

@Composable
private fun ComposerAsTaskToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) }.heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(14.dp)
                .background(if (checked) SlockColor.TextBlack else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(10.dp), color = "#FFFFFFFF", strokeWidth = 4f)
            }
        }
        val labelColor = if (enabled) SlockColor.TextBlack.copy(alpha = 0.6f) else SlockColor.TextMutedGray
        Text(
            text = "As Task",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(lineHeight = 18.sp, color = labelColor),
            color = labelColor
        )
    }
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
        shadowOffset = 2.dp,
        iconSize = ThreadComposerIconSize
    )
}

@Composable
private fun ComposerTextChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = { if (enabled) onClick() },
        backgroundColor = SlockColor.SurfaceWhite,
        minWidth = 0.dp,
        minHeight = 26.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Caption.copy(color = SlockColor.TextBlack)
    )
}

private fun KuiklyPendingAttachmentItem.statusLabel(): String {
    val detail = sizeLabel.takeIf { it.isNotBlank() }
    val state = when (status) {
        "uploading" -> "Uploading"
        "failed" -> "Upload failed"
        else -> "Ready"
    }
    return listOf(state, detail).filter { it != null && it.isNotBlank() }.joinToString(" • ")
}

private fun detectKuiklyComposerSuggestionTrigger(text: String): KuiklyComposerSuggestionTrigger {
    if (text.isBlank()) {
        return KuiklyComposerSuggestionTrigger.None
    }
    var triggerIndex = -1
    var triggerChar = ' '
    for (i in text.indices.reversed()) {
        val current = text[i]
        if (current.isWhitespace()) {
            break
        }
        if (current == '@' || current == '#') {
            if (i == 0 || text[i - 1].isComposerSuggestionBoundary()) {
                triggerIndex = i
                triggerChar = current
            }
            break
        }
    }
    if (triggerIndex < 0) {
        return KuiklyComposerSuggestionTrigger.None
    }
    val query = text.substring(triggerIndex + 1)
    return when (triggerChar) {
        '@' -> KuiklyComposerSuggestionTrigger.MemberTrigger(query, triggerIndex)
        '#' -> KuiklyComposerSuggestionTrigger.ChannelTrigger(query, triggerIndex)
        else -> KuiklyComposerSuggestionTrigger.None
    }
}

private fun Char.isComposerSuggestionBoundary(): Boolean = !isLetterOrDigit()
