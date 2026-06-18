package ai.slock.compose

import ai.slock.compose.base.Utils
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
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
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

private const val THREAD_COMPOSER_VISUAL_PAGE = "compose_thread_composer_visual"
private const val THREAD_COMPOSER_PAGE_DATA_EVENT = "slockPageData"
private val ThreadComposerStandaloneTopPadding = 10.dp
private val ThreadComposerStandaloneBottomPadding = 8.dp
private val ThreadComposerTextFieldMinHeight = 20.dp
private val ThreadComposerVisualComponentWidth = 342.dp
private val ThreadComposerVisualComponentMinHeight = 100.dp
private val threadComposerJson = Json { ignoreUnknownKeys = true }

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
    val memberSuggestions: List<KuiklyComposerMemberItem> = emptyList(),
    val channelSuggestions: List<KuiklyComposerChannelItem> = emptyList(),
    val surfaceKind: String = KuiklyThreadSurfaceKind.Channel,
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
    val hookLabels: List<String> = emptyList()
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
            mentionChannelId?.takeIf { it.isNotBlank() }
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
internal data class KuiklyComposerMemberItem(
    val displayName: String,
    val handle: String
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
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload =
        rememberThreadComposerPayload(
            payload = payloadText,
            fallbackPageData = params.toString(),
            instanceId = instanceId
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
    instanceId: String
): KuiklyThreadComposerPayload =
    remember(payload, fallbackPageData, instanceId) {
        parseThreadComposerPayload(
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
    val source =
        when {
            primaryPayload.isNotEmpty() -> primaryPayload
            fallbackPageData.trim().isNotEmpty() -> fallbackPageData.trim()
            else -> "{}"
        }
    return runCatching {
        threadComposerJson
            .decodeFromString(KuiklyThreadComposerPayload.serializer(), source)
            .withInstanceIdFallback(instanceId)
    }.getOrElse {
        if (primaryPayload.isEmpty()) {
            KuiklyThreadComposerPayload(instanceId = instanceId)
        } else {
            KuiklyThreadComposerPayload(
                instanceId = instanceId,
                composerMode = "failed",
                statusLabel = "Composer payload parse failed"
            )
        }
    }
}

private fun KuiklyThreadComposerPayload.withInstanceIdFallback(instanceId: String): KuiklyThreadComposerPayload =
    if (this.instanceId.isBlank() && instanceId.isNotBlank()) {
        copy(instanceId = instanceId)
    } else {
        this
    }

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
    val resolvedStreamChannelId = composer.streamChannelId ?: streamChannelId ?: sourceThreadId
    val resolvedParentMessageId = composer.parentMessageId ?: parentMessageId ?: originParentMessageId
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
                channelId = payload.streamChannelId ?: payload.instanceId
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
        println("[composer] trigger text=\"\${text.take(30)}\" trigger=\${suggestionTrigger}")
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
            if (showMemberSuggestions) {
                ComposerSuggestionPanel(title = surface.memberSuggestionTitle()) {
                    filteredMemberSuggestions.forEach { member ->
                        ComposerSuggestionRow(
                            leading = {
                                Box(
                                    modifier = Modifier.size(28.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = member.displayName.take(1).uppercase(),
                                        style = SlockType.Button.copy(fontSize = 12.sp),
                                        color = SlockColor.TextBlack
                                    )
                                }
                            },
                            title = member.displayName,
                            subtitle = "@${member.handle}",
                            onClick = { applyMemberSuggestion(member) }
                        )
                    }
                }
            } else if (showChannelSuggestions) {
                ComposerSuggestionPanel(title = "CHANNELS") {
                    filteredChannelSuggestions.forEach { channel ->
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
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier.matchParentSize().offset(x = 2f, y = 2f).background(SlockColor.ShadowBlack)
                )
                Column(
                    modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = fieldValue,
                        placeholder = state.placeholder,
                        autoFocus = shouldAutoFocus,
                        onValueChange = { next ->
                            fieldValue = next
                            println("[composer] onValueChange text=\"\${next.take(20)}\" len=\${next.length}")
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).heightIn(min = 30.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (state.attachmentActionsEnabled) {
                                ComposerIconButton("image-plus", composerEnabled) {
                                    onEvent("attachImage", null, null, null)
                                }
                                ComposerIconButton("paperclip", composerEnabled) {
                                    onEvent("attachFile", null, null, null)
                                }
                            }
                        }
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

private fun ThreadComposerSurface.memberSuggestionTitle(): String =
    when (kind) {
        KuiklyThreadSurfaceKind.DirectMessage -> "IN THIS DM"
        KuiklyThreadSurfaceKind.ReplyThread -> "IN PARENT CHANNEL"
        else -> "IN THIS CHANNEL"
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
    content: @Composable () -> Unit
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
        content()
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).border(SlockStroke.Thin, SlockColor.BorderBlack).padding(horizontal = 10.dp, vertical = 8.dp),
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
    icon: String,
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
        size = 28.dp,
        shadowOffset = 2.dp
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
                SlockLucideIcon("check", modifier = Modifier.size(10.dp), color = "#FFFFFFFF", strokeWidth = 4f)
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
    val disabledOpacity = if (enabled || sending) 1f else 0.3f
    ComposerIconButton(
        icon = if (sending) "refresh-cw" else "send",
        enabled = enabled,
        modifier = Modifier.alpha(disabledOpacity),
        backgroundColor = SlockColor.AccentPink,
        disabledBackgroundColor = SlockColor.AccentPink,
        disabledAlpha = 1f,
        disabledIconColor = "#FF000000",
        onClick = onClick
    )
}

@Composable
private fun ComposerTextChip(text: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
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


