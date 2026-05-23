package ai.slock.compose

import ai.slock.compose.base.Utils
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
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.SolidColor
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.ImeAction
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val THREAD_COMPOSER_PAGE = "compose_thread_composer"
private const val THREAD_COMPOSER_PAGE_DATA_EVENT = "slockPageData"
private val threadComposerJson = Json { ignoreUnknownKeys = true }

@Page(THREAD_COMPOSER_PAGE, supportInLocal = true)
internal class ComposeThreadComposerPage : ai.slock.compose.base.BasePager() {
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
    val showAsTaskToggle: Boolean = true,
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

data class SharedThreadComposerPayloadInput(
    val seedText: String,
    val seedAsTask: Boolean,
    val syncNonce: Int,
    val focusRequestNonce: Int,
    val placeholder: String,
    val enabled: Boolean,
    val showAsTaskToggle: Boolean,
    val composerMode: String,
    val statusLabel: String? = null,
    val pendingAttachments: List<SharedThreadComposerAttachment> = emptyList(),
    val memberSuggestions: List<SharedThreadComposerMemberSuggestion> = emptyList(),
    val channelSuggestions: List<SharedThreadComposerChannelSuggestion> = emptyList()
)

fun buildSharedThreadComposerPayloadJson(input: SharedThreadComposerPayloadInput): String =
    threadComposerJson.encodeToString(
        KuiklyThreadComposerPayload.serializer(),
        KuiklyThreadComposerPayload(
            seedText = input.seedText,
            seedAsTask = input.seedAsTask,
            syncNonce = input.syncNonce,
            focusRequestNonce = input.focusRequestNonce,
            placeholder = input.placeholder,
            enabled = input.enabled,
            showAsTaskToggle = input.showAsTaskToggle,
            composerMode = input.composerMode,
            statusLabel = input.statusLabel,
            pendingAttachments =
                input.pendingAttachments.map { attachment ->
                    KuiklyPendingAttachmentItem(
                        id = attachment.id,
                        fileName = attachment.fileName,
                        sizeLabel = attachment.sizeLabel,
                        mimeType = attachment.mimeType,
                        status = attachment.status
                    )
                },
            memberSuggestions =
                input.memberSuggestions.map { member ->
                    KuiklyComposerMemberItem(
                        displayName = member.displayName,
                        handle = member.handle
                    )
                },
            channelSuggestions =
                input.channelSuggestions.map { channel ->
                    KuiklyComposerChannelItem(
                        name = channel.name,
                        subtitle = channel.subtitle
                    )
                }
        )
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
    ThreadComposerContent(payload = payload, onEvent = { action, value, asTask, attachmentId ->
        bridge.composerEvent(
            instanceId = payload.instanceId,
            action = action,
            value = value,
            asTask = asTask,
            attachmentId = attachmentId
        )
    })
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

@Composable
internal fun ThreadComposerContent(
    payload: KuiklyThreadComposerPayload,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {}
) {
    var fieldValue by remember(payload.instanceId) {
        mutableStateOf(payload.seedText.toComposerFieldValueAtEnd())
    }
    val text = fieldValue.text
    var asTask by remember(payload.instanceId) { mutableStateOf(payload.seedAsTask) }
    var focused by remember(payload.instanceId) { mutableStateOf(false) }
    var handledFocusRequestNonce by remember(payload.instanceId) { mutableStateOf(payload.focusRequestNonce) }
    val shouldAutoFocus = payload.focusRequestNonce > handledFocusRequestNonce
    LaunchedEffect(payload.instanceId, payload.syncNonce) {
        fieldValue = payload.seedText.toComposerFieldValueAtEnd()
        asTask = payload.seedAsTask
        focused = false
    }
    val suggestionTrigger = remember(text) { detectKuiklyComposerSuggestionTrigger(text) }
    val filteredMemberSuggestions =
        remember(suggestionTrigger, payload.memberSuggestions) {
            when (val trigger = suggestionTrigger) {
                is KuiklyComposerSuggestionTrigger.MemberTrigger -> {
                    val query = trigger.query.lowercase()
                    payload.memberSuggestions.filter { member ->
                        query.isBlank() ||
                            member.displayName.lowercase().contains(query) ||
                            member.handle.lowercase().contains(query)
                    }
                }

                else -> emptyList()
            }
        }
    val filteredChannelSuggestions =
        remember(suggestionTrigger, payload.channelSuggestions) {
            when (val trigger = suggestionTrigger) {
                is KuiklyComposerSuggestionTrigger.ChannelTrigger -> {
                    val query = trigger.query.lowercase()
                    payload.channelSuggestions.filter { channel ->
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
    val canSend =
        payload.enabled &&
            payload.composerMode != "sending" &&
            payload.composerMode != "uploading" &&
            payload.composerMode != "no_permission" &&
            (text.isNotBlank() || payload.pendingAttachments.isNotEmpty())

    fun emitChange(nextText: String = text, nextAsTask: Boolean = asTask) {
        onEvent("change", nextText, nextAsTask, null)
    }

    fun applyMemberSuggestion(member: KuiklyComposerMemberItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.MemberTrigger ?: return
        val next = fieldValue.replaceComposerTrigger(trigger.triggerStart, "@${member.handle} ")
        fieldValue = next
        emitChange(nextText = next.text)
    }

    fun applyChannelSuggestion(channel: KuiklyComposerChannelItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger ?: return
        val next = fieldValue.replaceComposerTrigger(trigger.triggerStart, "#${channel.name} ")
        fieldValue = next
        emitChange(nextText = next.text)
    }

    Box(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .width(342.dp)
                .padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            payload.statusLabel?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    style = SlockType.Caption.copy(
                        color = if (payload.composerMode == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray
                    ),
                    color = if (payload.composerMode == "failed") SlockColor.AccentRed else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (payload.pendingAttachments.isNotEmpty()) {
                ComposerAttachmentTray(
                    attachments = payload.pendingAttachments,
                    enabled = payload.enabled
                ) { action, attachmentId ->
                    onEvent(action, null, null, attachmentId)
                }
            }
            if (showMemberSuggestions) {
                ComposerSuggestionPanel(title = "IN THIS CHANNEL") {
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
                        placeholder = payload.placeholder,
                        autoFocus = shouldAutoFocus,
                        onValueChange = { next ->
                            fieldValue = next
                            emitChange(nextText = next.text)
                        },
                        onFocus = {
                            focused = true
                            if (shouldAutoFocus) {
                                handledFocusRequestNonce = payload.focusRequestNonce
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
                                    fieldValue = "".toComposerFieldValueAtEnd()
                                    asTask = false
                                }
                            }
                        ),
                        textStyle = SlockType.Input.copy(
                            color = if (payload.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        ),
                        keyboardHeightChange = { params ->
                            onKeyboardHeightChange(params.height.coerceAtLeast(0f))
                        },
                        placeholderColor = SlockColor.TextMutedGray,
                        cursorBrush = SolidColor(SlockColor.TextBlack),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    )
                    if (payload.hookLabels.isNotEmpty()) {
                        Text(
                            text = payload.hookLabels.joinToString(" / "),
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
                            ComposerIconButton("image-plus", payload.enabled) {
                                onEvent("attachImage", null, null, null)
                            }
                            ComposerIconButton("paperclip", payload.enabled) {
                                onEvent("attachFile", null, null, null)
                            }
                        }
                        if (payload.showAsTaskToggle) {
                            ComposerAsTaskToggle(
                                checked = asTask,
                                enabled = payload.enabled
                            ) { checked ->
                                asTask = checked
                                onEvent("toggleAsTask", null, checked, null)
                            }
                        }
                        ComposerSendButton(
                            enabled = canSend,
                            sending = payload.composerMode == "sending" || payload.composerMode == "uploading"
                        ) {
                            onEvent("send", null, null, null)
                            fieldValue = "".toComposerFieldValueAtEnd()
                            asTask = false
                        }
                    }
                }
            }
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
                        style = SlockType.Caption.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
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
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, color = SlockColor.TextMutedGray),
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
    backgroundColor: Color = SlockColor.SurfaceWhite,
    disabledBackgroundColor: Color = SlockColor.EmptyStateGray,
    disabledAlpha: Float = 1f,
    onClick: () -> Unit
) {
    val activeColor = if (enabled) backgroundColor else disabledBackgroundColor.copy(alpha = disabledAlpha)
    val chromeColor = SlockColor.BorderBlack.copy(alpha = if (enabled) 1f else disabledAlpha)
    val shadowColor = SlockColor.ShadowBlack.copy(alpha = if (enabled) 1f else disabledAlpha)
    val iconColor = if (enabled) "#FF000000" else "#4D000000"
    Box(
        modifier = Modifier.size(30.dp)
    ) {
        Box(
            modifier = Modifier.offset(x = 2f, y = 2f).size(28.dp).background(shadowColor)
        )
        Button(
            onClick = if (enabled) onClick else ({}),
            modifier = Modifier
                .size(28.dp)
                .background(activeColor)
                .border(SlockStroke.Default, chromeColor)
        ) {
            SlockLucideIcon(icon, modifier = Modifier.size(16.dp), color = iconColor)
        }
    }
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
                .background(if (checked) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
        val labelColor = if (enabled) SlockColor.TextBlack.copy(alpha = 0.6f) else SlockColor.TextMutedGray
        Text(
            text = "As Task",
            style = SlockType.Caption.copy(fontWeight = FontWeight.Bold, lineHeight = 18.sp, color = labelColor),
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
    ComposerIconButton(
        icon = "send",
        enabled = enabled,
        backgroundColor = SlockColor.AccentPink,
        disabledBackgroundColor = SlockColor.AccentPink,
        disabledAlpha = 0.3f,
        onClick = onClick
    )
}

@Composable
private fun ComposerTextChip(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
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

private fun String.toComposerFieldValueAtEnd(): TextFieldValue =
    TextFieldValue(text = this, selection = TextRange(length))

private fun TextFieldValue.replaceComposerTrigger(triggerStart: Int, replacement: String): TextFieldValue {
    val end = selection.max.coerceIn(0, text.length)
    val safeStart = triggerStart.coerceIn(0, end)
    val nextText = text.replaceRange(safeStart, end, replacement)
    val nextCursor = safeStart + replacement.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}

private fun TextFieldValue.insertTextAtSelection(textToInsert: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val nextText = text.replaceRange(start, end, textToInsert)
    val nextCursor = start + textToInsert.length
    return copy(text = nextText, selection = TextRange(nextCursor))
}
