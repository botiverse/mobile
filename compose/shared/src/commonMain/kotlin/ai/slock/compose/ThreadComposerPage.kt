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
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.text.KeyboardActions
import com.tencent.kuikly.compose.foundation.text.KeyboardOptions
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
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
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
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
    ThreadComposerContent(payload = payload) { action, value, asTask, attachmentId ->
        bridge.composerEvent(
            instanceId = payload.instanceId,
            action = action,
            value = value,
            asTask = asTask,
            attachmentId = attachmentId
        )
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

@Composable
internal fun ThreadComposerContent(
    payload: KuiklyThreadComposerPayload,
    onEvent: (action: String, value: String?, asTask: Boolean?, attachmentId: String?) -> Unit
) {
    var text by remember(payload.seedText, payload.syncNonce) { mutableStateOf(payload.seedText) }
    var asTask by remember(payload.seedAsTask, payload.syncNonce) { mutableStateOf(payload.seedAsTask) }
    var focused by remember(payload.focusRequestNonce, payload.syncNonce) { mutableStateOf(false) }
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
        text = text.replaceComposerTrigger(trigger.triggerStart, "@${member.handle} ")
        emitChange()
    }

    fun applyChannelSuggestion(channel: KuiklyComposerChannelItem) {
        val trigger = suggestionTrigger as? KuiklyComposerSuggestionTrigger.ChannelTrigger ?: return
        text = text.replaceComposerTrigger(trigger.triggerStart, "#${channel.name} ")
        emitChange()
    }

    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).padding(horizontal = 16.dp, vertical = 8.dp),
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
        Column(
            modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite)
        ) {
            Spacer(
                modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextField(
                    value = text,
                    placeholder = payload.placeholder,
                    autoFocus = payload.focusRequestNonce > 0,
                    onValueChange = {
                        text = it
                        emitChange()
                    },
                    onFocus = { focused = true },
                    onBlur = { focused = false },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) {
                                onEvent("send", null, null, null)
                                text = ""
                                asTask = false
                            }
                        }
                    ),
                    textStyle = SlockType.Input.copy(
                        color = if (payload.enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
                        lineHeight = 20.sp
                    ),
                    placeholderColor = SlockColor.TextMutedGray,
                    cursorBrush = SolidColor(SlockColor.TextBlack),
                    modifier = Modifier.fillMaxWidth()
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
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
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
                        text = ""
                        asTask = false
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                if (enabled) backgroundColor else SlockColor.EmptyStateGray,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .border(
                SlockStroke.Default,
                SlockColor.BorderBlack,
                RoundedCornerShape(SlockRadius.Xs)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(icon, modifier = Modifier.size(16.dp), color = "#FF000000")
    }
}

@Composable
private fun ComposerAsTaskToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(18.dp).background(if (checked) SlockColor.BrandYellow else SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", style = SlockType.Caption.copy(fontWeight = FontWeight.Bold), color = SlockColor.TextBlack)
            }
        }
        Text(
            text = "As Task",
            style = SlockType.Caption.copy(color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
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
        onClick = onClick
    )
}

@Composable
private fun ComposerTextChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
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

private fun String.replaceComposerTrigger(triggerStart: Int, replacement: String): String =
    replaceRange(triggerStart, length, replacement)
