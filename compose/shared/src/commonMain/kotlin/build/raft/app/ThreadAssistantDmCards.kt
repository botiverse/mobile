package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DelegatedReceipt
import build.raft.app.data.DelegatedReceiptLine
import build.raft.app.data.DelegatedRetractResult
import build.raft.app.data.MyAssistantKey
import build.raft.app.data.RaftAssistantDmCardsApi
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.WECHAT_CONNECTED_NOTICE_PREFIX
import build.raft.app.data.WeChatDisconnectResult
import build.raft.app.data.WeChatStatusResult
import build.raft.app.data.delegatedReceiptOf
import build.raft.app.data.weChatNoticeBindingId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.components.RaftMessageActionCardProperties
import build.raft.designsystem.components.RaftMessageActionCardProperty
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The two cards of the owner's DM with their personal assistant (web parity, 1.25), drawn instead
 * of their one-line system notices:
 *
 * - The delegated-send receipt (RFC 077, web `DelegatedReceiptCard.tsx` / `DelegatedReceiptLines.tsx`;
 *   raft-design #680): "Sent for you in #channel", the message's first line, Open message, and
 *   Retract message while the server says it can still be retracted. Retract asks first in a small
 *   Dialog with a red Retract message. Retracted, the card folds to its header, marked Retracted.
 * - The WeChat connected notice (web `WeChatConnectedNotice.tsx`): "WeChat connected" with a
 *   one-click Disconnect WeChat (no confirmation: a protective disconnect) while this DM is the
 *   viewer's own assistant DM and the connection is still the one the notice names. Ended, the card
 *   is its header, marked Disconnected.
 *
 * Both break a run of folded system lines ([threadSystemMessageCategory]).
 */

/** Whether [this] system line is drawn as one of the assistant DM's cards (not an event line). */
internal fun RaftThreadMessageItem.isAssistantDmCardLine(): Boolean =
    isSystemMessage() &&
        (randomId?.startsWith(WECHAT_CONNECTED_NOTICE_PREFIX) == true ||
            delegatedReceiptOf(metadata)?.let { it.lines.isNotEmpty() || it.joinedChannels.isNotEmpty() || it.joinedNotForwarded.isNotEmpty() } == true)

/**
 * What the cards need from the conversation page, provided once per page
 * ([LocalThreadAssistantDmCards]): the server, the viewer, this conversation, and the requests.
 * The local state (a line retracted here, the WeChat status) lives here, not in a row, so a
 * recycled row keeps it.
 */
internal class ThreadAssistantDmCardsHost(
    val serverId: String?,
    val userId: String?,
    /** This conversation's own channel id (a reply thread: none; the notice lives in the DM). */
    val conversationChannelId: String?,
    val webBaseUrl: String?,
    val api: RaftAssistantDmCardsApi?,
    val assistantStore: SharedMyAssistantStore?,
    val bridge: BridgeModule?,
) {
    /** Receipt line message id -> `deletedAt`, retracted from this page (shown before `message:updated`). */
    val retractedHere = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The WeChat status per server, asked once (web `useWeChatStatus` cache). */
    val weChatStatus = MutableStateFlow<Map<String, WeChatStatusResult>>(emptyMap())
    private val weChatAsked = mutableSetOf<String>()

    suspend fun ensureWeChatStatus(server: String) {
        val api = api ?: return
        if (!weChatAsked.add(server)) return
        val result = api.weChatStatus(ServerId(server))
        weChatStatus.update { it + (server to result) }
    }

    suspend fun refreshWeChatStatus(server: String) {
        val api = api ?: return
        val result = api.weChatStatus(ServerId(server))
        // A failed refresh keeps what was known (web `keepOnError`).
        weChatStatus.update { current ->
            if (result is WeChatStatusResult.Error && current[server] is WeChatStatusResult.Ready) current else current + (server to result)
        }
    }

    fun clearWeChatBinding(server: String) {
        weChatStatus.update { current ->
            val known = (current[server] as? WeChatStatusResult.Ready)?.status
            current + (server to WeChatStatusResult.Ready((known ?: build.raft.app.data.WeChatStatus.NotHere).copy(binding = null)))
        }
    }
}

internal val LocalThreadAssistantDmCards = compositionLocalOf<ThreadAssistantDmCardsHost?> { null }

/** The page's [ThreadAssistantDmCardsHost], kept while its inputs stay the same. */
@Composable
internal fun rememberThreadAssistantDmCardsHost(
    sharedStores: build.raft.app.data.RaftSharedStoreProvider,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    serverId: String?,
    userId: String?,
    conversationChannelId: String?,
    bridge: BridgeModule,
): ThreadAssistantDmCardsHost =
    remember(sharedStores, storeScope, serverId, userId, conversationChannelId, bridge) {
        ThreadAssistantDmCardsHost(
            serverId = serverId,
            userId = userId,
            conversationChannelId = conversationChannelId,
            webBaseUrl = runCatching { sharedStores.apiEnvironmentStore.current().webUrl }.getOrNull(),
            api = build.raft.app.data.RaftAssistantDmCardsNetworkApi(sharedStores.networkClient(storeScope)),
            assistantStore = sharedStores.myAssistantStore(storeScope),
            bridge = bridge,
        )
    }

/**
 * A system line that is one of the assistant DM's cards, drawn as its card; false when [message]
 * is any other line (the caller draws the one-line notice). [serverSlug] and [onOpenUrl] open a
 * message or conversation as its web link would.
 */
@Composable
internal fun ThreadAssistantDmCardRow(
    message: RaftThreadMessageItem,
    serverSlug: String?,
    onOpenUrl: ((String) -> Unit)?,
): Boolean {
    if (!message.isSystemMessage()) return false
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .padding(
                // raft-ui MessageItem compact: the card on the text column (past the 35dp avatar gutter and its 12dp gap).
                start = RaftAppSpacing.Sm + 35.dp + 12.dp,
                end = RaftAppSpacing.Sm,
                top = ThreadMessageTimelineRowInset + RaftAppSpacing.Xs,
                bottom = ThreadMessageTimelineRowInset + RaftAppSpacing.Xs,
            )
    if (message.randomId?.startsWith(WECHAT_CONNECTED_NOTICE_PREFIX) == true) {
        WeChatConnectedNoticeCard(message, rowModifier)
        return true
    }
    val receipt = remember(message.metadata) { delegatedReceiptOf(message.metadata) } ?: return false
    if (receipt.lines.isEmpty() && receipt.joinedChannels.isEmpty() && receipt.joinedNotForwarded.isEmpty()) return false
    DelegatedReceiptLines(message, receipt, serverSlug, onOpenUrl, rowModifier)
    return true
}

// ---- Delegated-send receipt ---------------------------------------------------------------------

/** The server's own receipt text, read only for what older metadata doesn't carry (web `RECEIPT_TEXT`). */
private val DelegatedReceiptText = Regex("""^Sent for you (in a thread in|in) (#[^\s:]+|dm:@[^\s:]+): "([\s\S]*)"$""")

private data class DelegatedReceiptPlace(
    val dm: Boolean,
    val name: String,
    val inThread: Boolean,
    /** The conversation the place opens; null: a name without a link. */
    val channelId: String?,
)

private fun delegatedReceiptPlace(line: DelegatedReceiptLine, body: String): Pair<DelegatedReceiptPlace?, String?> {
    val parsed = DelegatedReceiptText.matchEntire(body)
    val inThread = line.parentChannelId != null || parsed?.groupValues?.get(1) == "in a thread in"
    val preview = line.preview ?: parsed?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
    val where = line.conversationRef ?: parsed?.groupValues?.get(2)
    if (where == null || !(where.startsWith("#") || where.startsWith("dm:@"))) return null to preview
    val channelId = line.parentChannelId ?: line.channelId.takeIf { !inThread }
    val place =
        if (where.startsWith("#")) {
            DelegatedReceiptPlace(dm = false, name = where.removePrefix("#"), inThread = inThread, channelId = channelId)
        } else {
            DelegatedReceiptPlace(dm = true, name = where.removePrefix("dm:@"), inThread = inThread, channelId = channelId)
        }
    return place to preview
}

@Composable
private fun DelegatedReceiptLines(
    message: RaftThreadMessageItem,
    receipt: DelegatedReceipt,
    serverSlug: String?,
    onOpenUrl: ((String) -> Unit)?,
    modifier: Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
        receipt.lines.forEach { line ->
            DelegatedReceiptCard(message, line, serverSlug, onOpenUrl)
        }
        if (receipt.joinedChannels.isNotEmpty()) {
            val channels = receipt.joinedChannels.joinToString(", ") { "#${it.name}" }
            RaftText(
                stringResource(
                    if (receipt.joinedChannels.size == 1) Res.string.assistant_receipt_joined_one else Res.string.assistant_receipt_joined_other,
                    channels,
                ),
                style = typography.footnote,
                color = colors.foregroundHint,
            )
        }
        receipt.joinedNotForwarded.forEach { channel ->
            RaftText(
                stringResource(Res.string.assistant_receipt_joined_not_forwarded, "#${channel.name}"),
                style = typography.footnote,
                color = colors.foregroundHint,
            )
        }
    }
}

@Composable
private fun DelegatedReceiptCard(
    message: RaftThreadMessageItem,
    line: DelegatedReceiptLine,
    serverSlug: String?,
    onOpenUrl: ((String) -> Unit)?,
) {
    val host = LocalThreadAssistantDmCards.current
    val retractedHere by (host?.retractedHere ?: remember { MutableStateFlow<Map<String, String>>(emptyMap()) }).collectAsState()
    val retracted = line.retracted || line.messageId in retractedHere
    val retractable = line.retractable && !retracted && host?.api != null && host.serverId != null
    val (place, preview) = remember(line, message.body) { delegatedReceiptPlace(line, message.body) }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val scope = rememberCoroutineScope()
    var confirming by remember(line.messageId) { mutableStateOf(false) }
    var retracting by remember(line.messageId) { mutableStateOf(false) }
    var retractError by remember(line.messageId) { mutableStateOf<ScreenError?>(null) }
    var expanded by remember(line.messageId, retracted) { mutableStateOf(!retracted) }
    val openFailed = stringResource(Res.string.assistant_receipt_open_failed)

    fun link(channelId: String, messageId: String?, threadParentMessageId: String?): String? {
        val slug = serverSlug?.takeIf(String::isNotBlank) ?: return null
        val kind = if (place?.dm == true) ConversationKind.DirectMessage else ConversationKind.Channel
        return buildRaftMessageLink(RaftMessageLink(slug, kind, channelId, messageId, threadParentMessageId), host?.webBaseUrl)
    }

    val openPlace: (() -> Unit)? =
        place?.channelId?.let { channelId ->
            { link(channelId, null, null)?.let { url -> onOpenUrl?.invoke(url) } ?: host?.bridge?.toast(openFailed) }
        }
    val openMessage = {
        val parentChannelId = line.parentChannelId
        val parentMessageId = line.parentMessageId
        val url =
            if (parentChannelId != null && parentMessageId != null) {
                link(parentChannelId, line.messageId, parentMessageId)
            } else {
                // A thread reply from a server that doesn't send its parent: the link resolver opens
                // the thread a channel link to a reply points into, as the web asks the server.
                link(line.channelId, line.messageId, null)
            }
        if (url != null && onOpenUrl != null) onOpenUrl(url) else host?.bridge?.toast(openFailed)
    }

    // The title as an annotated string (the place is a link when it names a conversation).
    val unknownTitle = stringResource(Res.string.assistant_receipt_title_unknown)
    val title =
        if (place == null) {
            buildAnnotatedString { append(unknownTitle) }
        } else {
            val label = if (place.dm) "@${place.name}" else "#${place.name}"
            val template =
                stringResource(
                    if (place.inThread) Res.string.assistant_receipt_title_thread else Res.string.assistant_receipt_title,
                    DelegatedPlaceMarker,
                )
            buildAnnotatedString {
                val at = template.indexOf(DelegatedPlaceMarker)
                if (at < 0) {
                    append(template)
                } else {
                    append(template.substring(0, at))
                    val styled = SpanStyle(color = colors.foregroundLink)
                    if (openPlace != null) {
                        withLink(LinkAnnotation.Clickable(tag = "delegated-receipt-channel", linkInteractionListener = LinkInteractionListener { openPlace() })) {
                            withStyle(styled) { append(label) }
                        }
                    } else {
                        withStyle(styled) { append(label) }
                    }
                    append(template.substring(at + DelegatedPlaceMarker.length))
                }
            }
        }
    val time = formatThreadTimeRevealLabel(message.timestampLabel, currentRaftTimeContextProvider?.current)
    val iconColor = raftArgbHex(colors.foregroundMuted)
    RaftMessageActionCard(
        title = "",
        modifier = Modifier.testTag(if (retracted) DelegatedReceiptRetractedTag else DelegatedReceiptTag),
        icon = {
            RaftLucideIcon(
                name = RaftLucideIconName.Send,
                color = iconColor,
                modifier = Modifier.size(RaftMessageActionCardDefaults.HeaderGlyphSize),
            )
        },
        description = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(title, style = typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.foreground)
                if (time.isNotBlank()) RaftMessageActionCardHeaderDescription(time)
            }
        },
        status = if (retracted) stringResource(Res.string.assistant_receipt_retracted) else null,
        statusTone = RaftMessageActionCardStatusTone.Neutral,
        expanded = expanded,
        onExpandedChange = if (retracted) ({ expanded = it }) else null,
        toggleLabel = stringResource(Res.string.thread_action_card_toggle_details),
    ) {
        if (preview != null) {
            RaftMessageActionCardProperties {
                RaftMessageActionCardProperty(
                    label = stringResource(Res.string.assistant_receipt_message_label),
                    value = preview,
                    valueColor = if (retracted) colors.foregroundHint else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        }
        if (!retracted) {
            RaftMessageActionCardActions {
                RaftButton(
                    text = stringResource(Res.string.assistant_receipt_open),
                    onClick = openMessage,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(DelegatedReceiptOpenTag),
                    leadingIcon = {
                        RaftLucideIcon(name = RaftLucideIconName.MessageSquare, color = raftArgbHex(colors.foreground), modifier = Modifier.size(16.dp))
                    },
                )
                if (retractable) {
                    RaftButton(
                        text = stringResource(Res.string.assistant_receipt_retract),
                        onClick = {
                            retractError = null
                            confirming = true
                        },
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(DelegatedReceiptRetractTag),
                    )
                }
            }
        }
    }

    if (confirming && host != null) {
        val dismiss = { if (!retracting) confirming = false }
        RaftDesignSystemTheme {
            RaftDialog(
                onDismissRequest = dismiss,
                title = stringResource(Res.string.assistant_receipt_retract_title),
                size = RaftDialogSize.Sm,
                dismissOnClickOutside = !retracting,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        onClick = dismiss,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = !retracting,
                    )
                    RaftButton(
                        text = stringResource(Res.string.assistant_receipt_confirm_retract),
                        onClick = {
                            val server = host.serverId
                            val api = host.api
                            if (server != null && api != null && !retracting) {
                                retracting = true
                                retractError = null
                                scope.launch {
                                    when (val result = api.retractDelegated(ServerId(server), line.messageId)) {
                                        is DelegatedRetractResult.Retracted -> {
                                            // Shown at once; `message:updated` brings the same state. Only this line.
                                            host.retractedHere.update { it + (line.messageId to (result.deletedAt ?: "")) }
                                            confirming = false
                                        }
                                        // Said in the dialog, which stays open to try again; the card is unchanged.
                                        is DelegatedRetractResult.Failed -> retractError = result.error
                                    }
                                    retracting = false
                                }
                            }
                        },
                        variant = RaftButtonVariant.Danger,
                        size = RaftButtonSize.Lg,
                        loading = retracting,
                        loadingLabel = stringResource(Res.string.assistant_receipt_retracting),
                        modifier = Modifier.testTag(DelegatedReceiptRetractConfirmTag),
                    )
                },
            ) {
                val error = retractError
                if (error != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBanner(
                            description = stringResource(Res.string.assistant_receipt_retract_failed),
                            status = RaftBannerStatus.Warning,
                            size = RaftBannerSize.Sm,
                        )
                        RaftTraceIdNote(error.traceId)
                    }
                }
                RaftBanner(
                    description = stringResource(Res.string.assistant_receipt_retract_body),
                    status = RaftBannerStatus.Destructive,
                    size = RaftBannerSize.Sm,
                )
            }
        }
    }
}

// A private-use character, never in a catalog string (as ThreadDelegatedMessage's marker).
private const val DelegatedPlaceMarker = ""

internal const val DelegatedReceiptTag = "delegated-receipt"
internal const val DelegatedReceiptRetractedTag = "delegated-receipt-retracted"
internal const val DelegatedReceiptOpenTag = "delegated-receipt-open"
internal const val DelegatedReceiptRetractTag = "delegated-receipt-retract"
internal const val DelegatedReceiptRetractConfirmTag = "delegated-receipt-retract-confirm"

// ---- WeChat connected notice --------------------------------------------------------------------

@Composable
private fun WeChatConnectedNoticeCard(message: RaftThreadMessageItem, modifier: Modifier) {
    val host = LocalThreadAssistantDmCards.current
    val colors = RaftTheme.colors
    val server = host?.serverId
    val user = host?.userId
    val assistantStore = host?.assistantStore
    val key = if (server != null && user != null) MyAssistantKey(server, user) else null
    LaunchedEffect(assistantStore, key) {
        if (assistantStore != null && key != null) assistantStore.ensureLoaded(key)
    }
    val answers by (assistantStore?.answers ?: remember { MutableStateFlow<Map<MyAssistantKey, build.raft.app.data.MyAssistantAnswer>>(emptyMap()) }).collectAsState()
    val answer = key?.let { answers[it] }
    val dmChannelId = answer?.assistant?.dmChannelId
    val ownDm = answer != null && answer.enabled && dmChannelId != null && dmChannelId == host?.conversationChannelId
    // Known not to be the viewer's own assistant DM: a record, nothing to do.
    val notMine = host == null || key == null || (answer != null && !ownDm)
    LaunchedEffect(host, server, ownDm) {
        if (host != null && server != null && ownDm) host.ensureWeChatStatus(server)
    }
    val statuses by (host?.weChatStatus ?: remember { MutableStateFlow<Map<String, WeChatStatusResult>>(emptyMap()) }).collectAsState()
    val status = server?.let { statuses[it] }
    val noticeBinding = weChatNoticeBindingId(message.randomId)
    val live =
        ownDm && noticeBinding != null &&
            (status is WeChatStatusResult.Error ||
                (status is WeChatStatusResult.Ready && status.status.binding?.bindingId == noticeBinding))
    val ended = ownDm && status is WeChatStatusResult.Ready && !live
    val pending = noticeBinding != null && !notMine && !live && !ended
    var busy by remember(message.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val disconnectedText = stringResource(Res.string.assistant_wechat_disconnected)
    val endedText = stringResource(Res.string.assistant_wechat_notice_ended_body)
    val failedText = stringResource(Res.string.assistant_wechat_disconnect_failed)
    val time = formatThreadTimeRevealLabel(message.timestampLabel, currentRaftTimeContextProvider?.current)
    val iconColor = raftArgbHex(colors.foregroundMuted)
    RaftMessageActionCard(
        title = stringResource(Res.string.assistant_wechat_notice_title),
        modifier =
            modifier.testTag(
                when {
                    live -> WeChatNoticeLiveTag
                    ended -> WeChatNoticeEndedTag
                    pending -> WeChatNoticePendingTag
                    else -> WeChatNoticeRecordTag
                }
            ),
        icon = {
            RaftLucideIcon(
                name = RaftLucideIconName.Smartphone,
                color = iconColor,
                modifier = Modifier.size(RaftMessageActionCardDefaults.HeaderGlyphSize),
            )
        },
        description = time.takeIf { it.isNotBlank() }?.let { line -> { RaftMessageActionCardHeaderDescription(line) } },
        status = if (ended) stringResource(Res.string.assistant_wechat_notice_disconnected) else null,
        statusTone = RaftMessageActionCardStatusTone.Neutral,
        // Ended or a record: the card is its header.
        expanded = live || pending,
        onExpandedChange = null,
    ) {
        if (live || pending) {
            RaftMessageActionCardActions(note = stringResource(Res.string.assistant_wechat_notice_note)) {
                RaftButton(
                    text = stringResource(Res.string.assistant_wechat_notice_disconnect),
                    onClick = {
                        val h = host
                        val api = h?.api
                        if (h != null && noticeBinding != null && server != null && api != null && !busy) {
                            busy = true
                            scope.launch {
                                when (val result = api.disconnectWeChat(ServerId(server), noticeBinding)) {
                                    WeChatDisconnectResult.Disconnected -> {
                                        h.clearWeChatBinding(server)
                                        h.bridge?.toastWithIntent(disconnectedText, RaftToastIntent.Success)
                                    }
                                    is WeChatDisconnectResult.AlreadyEnded -> {
                                        // A newer connection was made since: it stays, and the status shows it.
                                        if (result.bindingChanged) h.refreshWeChatStatus(server) else h.clearWeChatBinding(server)
                                        h.bridge?.toastWithIntent(endedText, RaftToastIntent.Info)
                                    }
                                    // A failed action is a toast (with its trace id); the card stays as it was.
                                    is WeChatDisconnectResult.Failed -> h.bridge?.errorToast(failedText, result.error)
                                }
                                busy = false
                            }
                        }
                    },
                    size = RaftButtonSize.Md,
                    enabled = !pending,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.assistant_wechat_disconnecting),
                    modifier = Modifier.testTag(WeChatNoticeDisconnectTag),
                )
            }
        }
    }
}

internal const val WeChatNoticeLiveTag = "wechat-connected-notice-live"
internal const val WeChatNoticeEndedTag = "wechat-connected-notice-ended"
internal const val WeChatNoticePendingTag = "wechat-connected-notice-pending"
internal const val WeChatNoticeRecordTag = "wechat-connected-notice-record"
internal const val WeChatNoticeDisconnectTag = "wechat-notice-disconnect"
