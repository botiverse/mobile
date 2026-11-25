package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import build.raft.app.components.rememberRaftTextareaEditorModifier
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextarea
import build.raft.app.components.raftCardSurface
import build.raft.app.base.Utils
import build.raft.app.data.ForwardMessageBatchExecuteRequest
import build.raft.app.data.ForwardMessageBatchResult
import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.data.isForwardConversationTarget
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.DataResult
import build.raft.app.data.failureTraceIdOrNull
import build.raft.app.data.map
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftTimeFormatRuntime
import build.raft.app.data.RaftTimeZoneRuntime
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.model.ConversationKind
import build.raft.app.model.ForwardMessageAttachment
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.ForwardMessageSource
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageConversationContext
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import build.raft.designsystem.components.RaftMessageImageFit
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel

// web ForwardedBundleCard attachments (artin #20 gap 18): projected images as the bundle's
// gallery (raft-ui MessageForwardedBundleGallery, loaded by snapshot id), then the other files
// as raft-ui's 22dp chips ("release-notes.md · markdown"). Legacy payloads without snapshots
// fall back to the muted count label. Projected files open with [onOpenAttachment] (by snapshot
// id; web handleOpenForwardedAttachment).
@Composable
private fun ThreadForwardAttachments(
    item: ForwardMessageItem,
    modifier: Modifier = Modifier,
    onOpenAttachment: ((String) -> Unit)? = null
) {
    if (item.attachments.isNotEmpty()) {
        // Without the thread's inline-image host (e.g. a page that doesn't provide one) the
        // images could never load, so they stay file chips.
        val imageHostAvailable = LocalThreadInlineImageFallbackHost.current != null
        val images =
            if (item.attachmentsProjected && imageHostAvailable) {
                item.attachments.filter { it.id != null && it.isForwardImagePreview() }
            } else {
                emptyList()
            }
        val files = item.attachments.filterNot { it in images }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (images.isNotEmpty()) {
                val openLabels = images.map { stringResource(Res.string.thread_forward_open_attachment, it.filename) }
                build.raft.designsystem.components.RaftMessageForwardedBundleGallery(
                    count = images.size,
                    onClick =
                        if (onOpenAttachment != null) {
                            { index: Int -> images[index].id?.let { onOpenAttachment(it) } }
                        } else {
                            null
                        },
                    onClickLabel = { index -> openLabels[index] }
                ) { index ->
                    ThreadImageAttachmentContent(
                        attachment = images[index].toThreadAttachmentItem(),
                        fit = RaftMessageImageFit.Cover
                    )
                }
            }
            if (files.isNotEmpty()) {
                build.raft.designsystem.components.RaftMessageForwardedBundleAttachments {
                    files.forEach { attachment ->
                        val attachmentId = attachment.id?.takeIf { item.attachmentsProjected }
                        build.raft.designsystem.components.RaftMessageForwardedBundleAttachment(
                            name = attachment.filename,
                            meta = "· ${forwardAttachmentKindLabel(attachment.mimeType)}",
                            onClick =
                                if (attachmentId != null && onOpenAttachment != null) {
                                    { onOpenAttachment(attachmentId) }
                                } else {
                                    null
                                },
                            onClickLabel = stringResource(Res.string.thread_forward_open_attachment, attachment.filename)
                        )
                    }
                }
            }
        }
    } else if (item.attachmentCount > 0) {
        RaftText(
            text = item.localizedForwardAttachmentLabel(),
            style = RaftTheme.typography.caption,
            color = RaftTheme.colors.foregroundHint,
            modifier = modifier
        )
    }
}

/**
 * web `isForwardedImagePreview`: an image the gallery can draw. SVG (active content) and HEIC
 * stay file chips.
 */
internal fun ForwardMessageAttachment.isForwardImagePreview(): Boolean {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (mime == "image/svg+xml" || mime == "image/heic" || mime == "image/heif") return false
    if (mime?.startsWith("image/") == true) return true
    return Regex("""\.(avif|gif|jpe?g|png|webp)$""", RegexOption.IGNORE_CASE).containsMatchIn(filename)
}

/** The chip's type: the mime subtype in lower case ("markdown", "pdf"), or "file". */
private fun forwardAttachmentKindLabel(mimeType: String?): String {
    if (mimeType.isNullOrBlank()) return "file"
    val subtype = mimeType.substringAfter('/', mimeType)
    return subtype.split('+', '.', '-', ';').firstOrNull()?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "file"
}

internal const val ThreadForwardBundleInlinePreviewLimit = 3

/** The link to a forwarded bundle's source message (`/s/<server>/channel/<id>?msg=<message>`). */
internal fun ForwardMessageSource.forwardSourcePermalink(serverSlug: String?, webBaseUrl: String? = null): String? {
    val slug = serverSlug?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val channel = channelId?.takeIf { it.isNotBlank() } ?: return null
    val message = messageId?.takeIf { it.isNotBlank() } ?: return null
    return "${sharedSettingsResolvedWebBaseUrl(webBaseUrl)}/s/$slug/channel/$channel?msg=$message"
}

/**
 * The forwarded bundle under a message (artin #20 gap 18): raft-ui MessageForwardedBundle as the
 * web's ForwardedBundleCard draws it. A `forward` icon and "Forwarded", the count in Space Mono
 * and the public source ("from #launch-week"); each message's author in footnote bold, its time
 * in Space Mono, its Markdown text and attachments; past 8 lines of text the list folds with a
 * fade. Mobile keeps its first [ThreadForwardBundleInlinePreviewLimit] messages inline and the
 * footer as the entry to the whole bundle on its own page (the web's phone layout hands the
 * footer to the same detail view), so the footer always shows.
 */
@Composable
internal fun ThreadForwardBundleCard(
    bundle: ForwardMessageBundle,
    messageBodyTextStyle: TextStyle,
    modifier: Modifier = Modifier,
    onOpenUrl: (String) -> Unit = {},
    onOpenAll: (() -> Unit)? = null,
    onOpenSource: ((ForwardMessageSource) -> Unit)? = null,
    onOpenAttachment: ((String) -> Unit)? = null
) {
    val count = bundle.displayCount()
    val lineHeight =
        with(LocalDensity.current) {
            messageBodyTextStyle.lineHeight.takeIf { it.isSp }?.toDp()
        }
    val foldHeight =
        lineHeight?.times(build.raft.designsystem.components.RaftMessageForwardedBundleDefaults.FoldLineLimit)
            ?: build.raft.designsystem.components.RaftMessageForwardedBundleDefaults.FallbackFoldHeight
    val source =
        bundle.visibleSource?.let { visible ->
            val taskTitle = visible.taskTitle
            if (taskTitle != null) {
                stringResource(Res.string.thread_forward_from_task, visible.label, taskTitle)
            } else if (visible.thread && !visible.label.endsWith(" · thread")) {
                stringResource(Res.string.thread_forward_from_thread, visible.label)
            } else {
                stringResource(Res.string.thread_forward_from_source, visible.label)
            }
        }
    val openableSource = bundle.visibleSource?.takeIf { it.channelId != null && it.messageId != null }
    val openSource: (() -> Unit)? =
        if (openableSource != null && onOpenSource != null) {
            { onOpenSource(openableSource) }
        } else {
            null
        }
    build.raft.designsystem.components.RaftMessageForwardedBundle(
        label = stringResource(Res.string.thread_forward_badge),
        countLabel =
            if (count == 1) {
                stringResource(Res.string.thread_message_count_one)
            } else {
                stringResource(Res.string.thread_message_count_many, count)
            },
        // raft-ui `mt-1`: the message column already spaces its parts 4dp apart.
        modifier = modifier,
        source = source,
        // web `canOpenSourceLabel`: only a source with a conversation and message opens.
        onOpenSource = openSource,
        onOpenSourceLabel = stringResource(Res.string.thread_forward_open_source),
        footerLabel =
            onOpenAll?.let {
                if (count == 1) {
                    stringResource(Res.string.thread_forward_view_message)
                } else {
                    stringResource(Res.string.thread_forward_view_all, count)
                }
            },
        onFooterClick = onOpenAll,
        footerMode = build.raft.designsystem.components.RaftMessageForwardedBundleFooterMode.Always,
        foldHeight = foldHeight
    ) {
        bundle.inlinePreviewMessages().forEach { preview ->
            val item = preview.item
            val hasAttachments = item.attachments.isNotEmpty() || item.attachmentCount > 0
            build.raft.designsystem.components.RaftMessageForwardedBundleItem(
                author = item.authorName.ifBlank { stringResource(Res.string.thread_comments_unknown_author) },
                time =
                    if (item.timestampLabel.isNotBlank()) {
                        {
                            RaftRelativeTimestampText(
                                value = item.timestampLabel,
                                displayGroup = RaftTimestampDisplayGroup.Conversation,
                                style = build.raft.designsystem.components.RaftMessageForwardedBundleDefaults.TimeStyle,
                                color = build.raft.designsystem.components.RaftMessageForwardedBundleDefaults.TimeInk,
                                maxLines = 1,
                                toggleEnabled = false
                            )
                        }
                    } else {
                        null
                    },
                attachments =
                    if (hasAttachments) ({ ThreadForwardAttachments(item, onOpenAttachment = onOpenAttachment) }) else null
            ) {
                if (preview.body.isNotBlank()) {
                    // Web's ForwardedBundleCard renders each forwarded body
                    // through MarkdownContent — plain Text left **bold**,
                    // [links](…) and `code` raw (task #63).
                    if (preview.truncated) {
                        Text(
                            text = preview.body,
                            style = messageBodyTextStyle,
                            color = RaftColor.TextBlack,
                            maxLines = ThreadForwardBundlePreviewLineLimit,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        RaftMarkdownBody(
                            body = preview.body,
                            textStyle = messageBodyTextStyle,
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardMessageBundle.localizedForwardTitle(): String =
    title.takeIf { it.isNotBlank() }
        ?: sourceTitle.takeIf { it.isNotBlank() }?.let {
            stringResource(Res.string.thread_forward_chat_history_named, it)
        }
        ?: stringResource(Res.string.thread_forward_chat_history)

internal fun ForwardMessageBundle.displayCount(): Int =
    totalCount?.takeIf { it > 0 } ?: messages.size

// Mirrors web MessageItem: the server stores the forward note as the message
// content and falls back to this generated placeholder when no note was given.
// The placeholder is card chrome, not body text, so it is never rendered as
// the note above the bundle card.
private fun threadForwardGeneratedPlaceholder(count: Int): String =
    "Forwarded $count message${if (count == 1) "" else "s"}"

// Web forwardedBundleBodyContent parity: the note is the bundle message's body
// unless the body is exactly the generated placeholder.
internal fun threadForwardNoteBody(body: String, displayCount: Int): String? =
    body
        .trim()
        .takeUnless { it == threadForwardGeneratedPlaceholder(displayCount) }
        ?.takeIf { it.isNotEmpty() }

private fun ForwardMessageItem.forwardPreviewLine(): String {
    val text = body.trim().ifBlank { forwardAttachmentLabel() }
    return "${authorName.ifBlank { "Unknown" }}: $text"
}

private fun ForwardMessageItem.forwardAttachmentLabel(): String =
    when (attachmentCount) {
        0 -> ""
        1 -> "[1 attachment]"
        else -> "[$attachmentCount attachments]"
    }

@Composable
private fun ForwardMessageItem.localizedForwardAttachmentLabel(): String =
    when (attachmentCount) {
        0 -> ""
        1 -> stringResource(Res.string.thread_forward_attachment_one)
        else -> stringResource(Res.string.thread_forward_attachment_many, attachmentCount)
    }

private fun ForwardMessageItem.memberDetailIdentity(): MemberDetailIdentity? =
    memberDetailIdentityFromWire(
        memberId = authorId,
        kindWireValue = authorKind,
        source = MemberDetailIdentitySource.ThreadForwardAuthor
    )

internal enum class ThreadForwardSelectionMode(val wireValue: String, val label: String) {
    Merged("merged", "Merged"),
    Individual("individual", "Individual")
}

internal data class ThreadForwardSuccessBanner(
    val target: ForwardMessageTarget,
    val destination: ForwardMessageDestination,
    val messageId: MessageId,
    /** The posted message's conversation as the server answered it (slock #12620): a thread's or a task's. */
    val conversationContext: MessageConversationContext? = null
)

internal fun ForwardMessageTarget.forwardSuccessDisplayTitle(): String =
    when {
        // web `forwardDeliveryLabel`: a thread / task row's own title (`#ch › first line`).
        isForwardConversationTarget() -> title.trim()
        channelKind == ConversationKind.DirectMessage -> title.trim().ifBlank { "DM" }
        else -> title.trim().removePrefix("#").takeIf { it.isNotBlank() }?.let { "#$it" } ?: "#channel"
    }

internal fun ThreadForwardSuccessBanner.forwardSuccessConversationIdentity(): ThreadLaunchConversationIdentity? {
    val resolvedChannelId = destination.channelId.value.trim().takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
    if (target.isForwardConversationTarget()) return forwardConversationDestinationIdentity(resolvedChannelId)
    val resolvedKind = destination.channelKind.takeUnless { it == ConversationKind.Thread } ?: ConversationKind.Channel
    if (resolvedKind == ConversationKind.DirectMessage && target.memberId == null) return null
    return buildThreadLaunchConversationIdentity(
        kind = resolvedKind,
        channelId = resolvedChannelId,
        channelType = target.channelType,
        dmPeerIdentity =
            target.memberId?.let { member ->
                MemberDetailIdentity(
                    memberId = member,
                    kind =
                        when (target.kind) {
                            ForwardMessageTargetKind.AgentDirectMessageCandidate -> MemberKind.Agent
                            else -> MemberKind.Human
                        },
                    sourceServerId = target.serverId
                )
            }
    )
}

/**
 * web `forwardDeliveryOpener` (slock #12621 forwardViewInChat.ts @ b088f8467e): a thread / task
 * destination is located ONLY by the conversation the server answered for the posted message
 * (a row's own id is its conversation channel's, not a parent message's or a task's). Without that
 * context there is no "View in chat" rather than a guessed one.
 */
private fun ThreadForwardSuccessBanner.forwardConversationDestinationIdentity(
    conversationChannelId: ChannelId
): ThreadLaunchConversationIdentity? {
    val context = conversationContext ?: return null
    return when (context.channelKind) {
        ConversationKind.Task -> {
            if (context.parentChannelId == null || context.taskId == null) return null
            taskConversationLaunchIdentity(conversationChannelId)
        }
        ConversationKind.Thread -> {
            val parentChannelId = context.parentChannelId ?: return null
            val parentMessageId = context.parentMessageId ?: return null
            val parentKind = context.parentChannelKind
            buildThreadLaunchConversationIdentity(
                kind = ConversationKind.Thread,
                channelId = conversationChannelId,
                threadChannelId = conversationChannelId,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                parentSurfaceKind =
                    if (parentKind == ConversationKind.DirectMessage) {
                        RaftThreadSurfaceKind.DirectMessage
                    } else {
                        RaftThreadSurfaceKind.Channel
                    },
                parentChannelKind = parentKind?.toWireChannelKindOrNull()
            )
        }
        else -> null
    }
}

/** web: a thread / task row whose answer carried no conversation gets a toast without an action. */
internal fun ThreadForwardSuccessBanner.forwardSuccessViewInChatAvailable(): Boolean =
    !target.isForwardConversationTarget() || forwardSuccessConversationIdentity() != null

internal data class ThreadForwardBatchOutcome(
    val failureCode: String? = null,
    val result: ForwardMessageBatchResult? = null,
    val failedReceipts: List<ThreadForwardDestinationReceipt> = emptyList(),
    val traceId: String? = null
)

internal fun forwardRequestIdAfterSelectionChange(
    previousKeys: Set<String>,
    nextKeys: Set<String>,
    currentRequestId: String?
): String? =
    currentRequestId.takeIf { previousKeys == nextKeys }

internal fun applyForwardMessageDeliveries(
    result: ForwardMessageBatchResult,
    messageStore: SharedThreadMessageStore,
    onHomeRefresh: () -> Unit
) {
    result.deliveries.forEach { delivery ->
        messageStore.upsert(
            serverId = delivery.destination.serverId,
            channelId = delivery.destination.channelId,
            message = delivery.message
        )
    }
    if (result.deliveries.isNotEmpty()) onHomeRefresh()
}

internal suspend fun executeThreadForwardBatch(
    repository: ForwardMessageTargetRepository,
    liveSourceServerId: ServerId?,
    sourceChannelId: ChannelId,
    receipts: List<ThreadForwardDestinationReceipt>,
    currentDirectoryGeneration: Int,
    messageIds: List<String>,
    mode: ForwardMessageSendMode,
    note: String,
    requestId: String
): ThreadForwardBatchOutcome {
    if (receipts.isEmpty()) return ThreadForwardBatchOutcome(failureCode = "forward_target_unavailable")
    val preResolveFailure = receipts
        .mapNotNull {
            it.preResolveFailureCode(
                liveSourceServerId = liveSourceServerId,
                currentDirectoryGeneration = currentDirectoryGeneration
            )
        }
        .firstOrNull()
    if (preResolveFailure != null) return ThreadForwardBatchOutcome(failureCode = preResolveFailure)
    val frozenSourceServerId = receipts.first().sourceServerId
    val resolved = receipts.map { receipt ->
        receipt to repository.resolveTargetChannel(frozenSourceServerId, receipt.target)
    }
    val destinations = mutableListOf<ForwardMessageDestination>()
    resolved.forEach { (receipt, result) ->
        val destination = (result as? DataResult.Content)?.data
            ?: return ThreadForwardBatchOutcome(
                failureCode = result.threadForwardFailureCode("forward_target_unavailable"),
                traceId = result.failureTraceIdOrNull()
            )
        receipt.postResolveFailureCode(destination)?.let {
            return ThreadForwardBatchOutcome(failureCode = it)
        }
        if (
            destination.channelId == sourceChannelId &&
            destination.serverId == frozenSourceServerId &&
            !receipt.allowsForwardIntoSource()
        ) {
            return ThreadForwardBatchOutcome(failureCode = "forward_source_target_forbidden")
        }
        destinations += destination
    }
    val destinationServer = destinations.first().serverId
    if (destinations.any { it.serverId != destinationServer }) {
        return ThreadForwardBatchOutcome(failureCode = "forward_cross_server_batch_unsupported")
    }
    if (messageIds.isEmpty()) return ThreadForwardBatchOutcome(failureCode = "forward_no_selected_messages")
    return when (val result = repository.forwardMessagesBatch(
        ForwardMessageBatchExecuteRequest(
            sourceServerId = frozenSourceServerId,
            sourceChannelId = sourceChannelId,
            destinations = destinations,
            messageIds = messageIds,
            mode = mode,
            note = note,
            requestId = requestId
        )
    )) {
        is DataResult.Content -> {
            val batch = result.data
            val failedKeys = batch.failures.map { it.destination.channelId }.toSet()
            ThreadForwardBatchOutcome(
                result = batch,
                failedReceipts = receipts.zip(destinations).mapNotNull { (receipt, destination) ->
                    receipt.takeIf { destination.channelId in failedKeys }
                }
            )
        }
        else -> ThreadForwardBatchOutcome(
            failureCode = result.threadForwardFailureCode("forward_failed"),
            traceId = result.failureTraceIdOrNull()
        )
    }
}

internal fun ForwardMessageTarget.threadForwardSelectionKey(): String = "${serverId.value}:${kind.name}:$id"

internal fun List<ForwardMessageTarget>.threadForwardPickerTargets(
    sourceServerId: ServerId?,
    sourceChannelId: ChannelId?
): List<ForwardMessageTarget> =
    filterNot { target ->
        sourceServerId != null &&
            sourceChannelId != null &&
            target.serverId == sourceServerId &&
            target.channelId == sourceChannelId
    }

private fun DataResult<*>.threadForwardFailureCode(fallback: String): String =
    when (this) {
        is DataResult.Content -> fallback
        is DataResult.Empty -> "forward_no_selected_messages"
        is DataResult.Error -> error.code
        is DataResult.NoPermission -> blocker.code
    }

private fun String?.threadForwardSelectionModeLabel(): String =
    when (this?.trim()?.lowercase()) {
        ThreadForwardSelectionMode.Individual.wireValue -> ThreadForwardSelectionMode.Individual.label
        else -> ThreadForwardSelectionMode.Merged.label
    }

internal fun ForwardMessageTarget.toRaftForwardTargetPickerItem(): RaftForwardTargetPickerItem<ForwardMessageTarget> =
    RaftForwardTargetPickerItem(
        stableKey = threadForwardSelectionKey(),
        title = title,
        subtitle = threadForwardTargetSubtitle(),
        avatarWireValue = avatarWireValue,
        avatarLabel = avatarLabel,
        cacheMemberKind =
            when (kind) {
                ForwardMessageTargetKind.HumanDirectMessageCandidate -> MemberKind.Human
                ForwardMessageTargetKind.AgentDirectMessageCandidate -> MemberKind.Agent
                else -> null
            },
        cacheMemberId = memberId?.value,
        cacheServerId = serverId.value,
        visualKind = forwardTargetVisualKind(),
        current = current && isForwardConversationTarget(),
        payload = this,
        channelId = channelId?.value?.takeIf { forwardTargetVisualKind().channelType() != null },
    )

internal fun ForwardMessageTarget.forwardTargetVisualKind(): RaftForwardTargetVisualKind =
    when {
        kind == ForwardMessageTargetKind.ConversationThread -> RaftForwardTargetVisualKind.Thread
        kind == ForwardMessageTargetKind.ConversationTask -> RaftForwardTargetVisualKind.Task
        kind == ForwardMessageTargetKind.RecentDirectMessage ||
            kind == ForwardMessageTargetKind.HumanDirectMessageCandidate ||
            kind == ForwardMessageTargetKind.AgentDirectMessageCandidate ->
            RaftForwardTargetVisualKind.DirectMessage
        channelKind == ConversationKind.Joint || channelType?.trim()?.lowercase() == "joint" ->
            RaftForwardTargetVisualKind.JointChannel
        channelType?.trim()?.lowercase() == "private" ->
            RaftForwardTargetVisualKind.PrivateChannel
        else -> RaftForwardTargetVisualKind.PublicChannel
    }

private fun ForwardMessageTarget.threadForwardTargetSubtitle(): String =
    when (requiredAction) {
        ForwardMessageTargetRequiredAction.JoinChannel ->
            subtitle.ifBlank { "Join channel before forwarding" }
        ForwardMessageTargetRequiredAction.CreateDirectMessage ->
            subtitle.ifBlank { "Start DM before forwarding" }
        ForwardMessageTargetRequiredAction.None ->
            subtitle.ifBlank {
                when (kind) {
                    ForwardMessageTargetKind.RecentChannel -> "Recent channel"
                    ForwardMessageTargetKind.RecentDirectMessage -> "Recent DM"
                    ForwardMessageTargetKind.PublicChannelUnjoined -> "Public channel"
                    ForwardMessageTargetKind.HumanDirectMessageCandidate -> "Member"
                    ForwardMessageTargetKind.AgentDirectMessageCandidate -> "Agent"
                    // The row reads its one-line title only (web `ForwardConversationTargetTitle`).
                    ForwardMessageTargetKind.ConversationThread,
                    ForwardMessageTargetKind.ConversationTask -> ""
                }
            }
    }

const val THREAD_FORWARD_BUNDLE_PAGE = "compose_thread_forward_bundle_page"
private const val ThreadForwardBundleLocalOffsetMinutesKey = "localOffsetMinutes"

internal fun ThreadForwardBundleRoute.toThreadForwardBundlePageData():
    build.raft.app.compat.json.JSONObject =
    build.raft.app.compat.json.JSONObject().apply {
        put("pageName", THREAD_FORWARD_BUNDLE_PAGE)
        put("serverId", serverId.value)
        put("channelId", channelId.value)
        put("messageId", messageId.value)
    }

internal fun build.raft.app.compat.json.JSONObject
    .threadForwardBundleTimeCapabilitiesOrNull(): RaftTimeCapabilities? {
    val fallbackOffsetMinutes =
        optString(ThreadForwardBundleLocalOffsetMinutesKey)
            .toIntOrNull()
            ?.takeIf { it in -24 * 60..24 * 60 }
            ?: return null
    return RaftTimeCapabilities(
        localOffsetMinutes = fallbackOffsetMinutes,
        timeFormat = optString("timeFormat").takeIf { it.isNotBlank() } ?: RaftTimeFormat24Hour
    )
}

// Full forwarded-bundle list as its own single-page pager (task #64): the
// inline card previews at most three messages; every bundle can route here. Same
// standalone-pager architecture as Billing/Administration so the list gets a
// full-height viewport and a clean lifecycle. Replaces the old in-thread modal.
internal fun Pager.openThreadForwardBundlePage(route: ThreadForwardBundleRoute) {
    openRaftPage(
        pageName = THREAD_FORWARD_BUNDLE_PAGE,
        // Product state crosses this boundary by identity only. Platform hosts
        // inject the legacy offset as a rollback fallback; the live provider
        // refreshes it through the KuiklyBase datetime foundation.
        pageData = route.toThreadForwardBundlePageData(),
        authenticatedRouteAuthorityRequired = true,
    )
}

@Page(THREAD_FORWARD_BUNDLE_PAGE, supportInLocal = true)
internal class ComposeThreadForwardBundlePage : AuthenticatedProductPager(AuthenticatedProductRoute.ThreadForwardBundle) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { ThreadForwardBundleScreen() }
    }
}

@Composable
private fun ThreadForwardBundleScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val linkNavigator = rememberRaftLinkNavigator(pager, bridge)
    val params = pager.pageData.params
    val visualFixture = remember(params.toString()) { params.optString("visualFixture") == "forward_bundle" }
    val route = remember(params.toString()) { params.toThreadForwardBundleRouteOrNull() }
    val baseTimeCapabilities = remember(params.toString()) { params.threadForwardBundleTimeCapabilitiesOrNull() }
    val runtimeTimeFormat =
        RaftTimeFormatRuntime.observe(fallback = baseTimeCapabilities?.timeFormat ?: RaftTimeFormat24Hour)
    val runtimeTimeZone = RaftTimeZoneRuntime.observe()
    val timeCapabilities =
        remember(baseTimeCapabilities, runtimeTimeFormat, runtimeTimeZone) {
            baseTimeCapabilities?.withRuntimePreferences(
                timeFormat = runtimeTimeFormat,
                preferredTimeZoneId = runtimeTimeZone,
            )
        }
    val inheritedTimeContextProvider = LocalRaftTimeContextProvider.current
    val fallbackTimeContextProvider = currentRaftTimeContextProvider
    val ownTimeContextProvider =
        rememberLiveRaftTimeContext(
            capabilities = timeCapabilities,
            active = inheritedTimeContextProvider == null && timeCapabilities != null,
        )
    val timeContextProvider =
        inheritedTimeContextProvider
            ?: ownTimeContextProvider.takeIf { timeCapabilities != null }
            ?: fallbackTimeContextProvider
            ?: ownTimeContextProvider
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val messageBodyFontSizeState by sharedStores.messageBodyFontSizeStore.state.collectAsState()
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val routeOwner = remember(params.toString()) { params.authenticatedRouteOwnerOrNull() }
    val authorityGateway = remember(sharedStores, storeScope) {
        sharedStores.authenticatedRouteAuthorityGateway(storeScope)
    }
    val liveOwner by
        remember(sharedStores, storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val productionAuthorityValid =
        route != null &&
            routeOwner != null &&
            routeOwner.serverId == route.serverId.value &&
            liveOwner == routeOwner
    val messageBodyOwnerKey =
        remember(routeOwner) {
            routeOwner?.let { owner ->
                messageBodyFontSizeOwnerKey(owner.environmentId, owner.accountId)
            }
        }
    LaunchedEffect(routeOwner, productionAuthorityValid) {
        if (!productionAuthorityValid) return@LaunchedEffect
        val owner = routeOwner ?: return@LaunchedEffect
        val receipt = authorityGateway.capture(owner) ?: return@LaunchedEffect
        val messageBodySettingsRepository =
            RealSettingsRepository(
                sessionStore = authStore,
                networkClient = authorityGateway.networkClient(receipt),
                logger = { message -> bridge.log("forward_bundle_message_body_font_repository $message") },
                warningLogger = { message -> bridge.logWarn("forward_bundle_message_body_font_repository $message") },
            )
        val ownerKey = messageBodyOwnerKey ?: return@LaunchedEffect
        sharedStores.messageBodyFontSizeStore.ensureLoaded(ownerKey) {
            val result = messageBodySettingsRepository.getAppearanceSettings()
            if (authorityGateway.isCurrent(receipt)) {
                result
            } else {
                build.raft.app.data.authenticatedRouteAuthorityChanged()
            }
        }
    }
    val selectedMessageBodyFontSize = messageBodyFontSizeState.selectedKey(messageBodyOwnerKey)
    val resolvedMessageBodyTextStyle = rememberRaftMessageBodyTextStyle(selectedMessageBodyFontSize)
    val repository =
        if (routeOwner != null && route != null && routeOwner.serverId == route.serverId.value) {
            remember(sharedStores, storeScope, authStore, routeOwner, authorityGateway) {
                AuthenticatedThreadForwardBundleDetailRepository(
                    messageStore = sharedStores.threadMessageStore,
                    authStore = authStore,
                    owner = routeOwner,
                    gateway = authorityGateway,
                    candidateCacheScopeIdProvider = {
                        sharedStores.currentAuthenticatedRouteCandidateCacheScopeId(storeScope, routeOwner)
                    },
                    logger = { message -> bridge.log("thread_forward_bundle_repository $message") },
                )
            }
        } else {
            null
        }
    val detailViewModel: ThreadForwardBundleDetailViewModel? =
        route?.takeIf { repository != null }?.let { resolvedRoute ->
            viewModel(
                key =
                    "thread-forward-bundle-${resolvedRoute.serverId.value}-" +
                        "${resolvedRoute.channelId.value}-${resolvedRoute.messageId.value}"
            ) {
                ThreadForwardBundleDetailViewModel(route = resolvedRoute, repository = requireNotNull(repository))
            }
        }
    val detailState by
        detailViewModel?.state?.collectAsState()
            ?: remember { mutableStateOf(ThreadForwardBundleDetailState(status = ThreadForwardBundleDetailStatus.Empty)) }
    LaunchedEffect(detailViewModel, visualFixture, productionAuthorityValid) {
        if (!visualFixture && productionAuthorityValid) {
            // Retry is intentional: if this retained page was hidden while a
            // different authority owned the shell, the prior Content state is
            // only an old candidate and must be revalidated before display.
            detailViewModel?.dispatch(ThreadForwardBundleDetailIntent.Retry)
        }
    }
    val bundle = if (visualFixture) threadForwardBundleVisualFixtureBundle() else detailState.bundle
    CompositionLocalProvider(LocalRaftTimeContextProvider provides timeContextProvider) {
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = bundle?.localizedForwardTitle() ?: stringResource(Res.string.thread_forward_bundle_fallback_title),
            iconName = RaftLucideIconName.Share2,
            backgroundColor = RaftColor.Canvas,
            onBack = {
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            }
        )
        when {
            !visualFixture && !productionAuthorityValid ->
                ThreadForwardBundleLoadFailure(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRetry = {},
                )
            threadForwardBundleContentAdmitted(
                visualFixture = visualFixture,
                productionAuthorityValid = productionAuthorityValid,
                status = detailState.status,
            ) -> {
                val messages = bundle?.messages.orEmpty()
                // Rows reuse the STANDARD chat message renderer (artin, task #64
                // follow-up): same avatar/author/timestamp/markdown chrome and spacing
                // as the thread flow, no bespoke dividers. Action callbacks are no-ops
                // — this is a read-only list; only markdown links stay live.
                // task #177: standalone page; the last message clears the navigation bar.
                val bundleBottomSafeArea = raftBottomSafeAreaPadding()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = RaftAppSpacing.Sm, bottom = RaftAppSpacing.Sm + bundleBottomSafeArea),
                ) {
                    items(messages.size, key = { messages[it].id }) { index ->
                        val item = messages[index]
                        val timestampLabel =
                            item.formatThreadForwardedTimestampLabel(timeContextProvider.dateContext)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ThreadMessageCard(
                                message = item.toThreadMessageItemForBundlePage(),
                                messageBodyTextStyle = resolvedMessageBodyTextStyle,
                                timestampLabel = timestampLabel,
                                compactTimestampLabel = timestampLabel,
                                onShowActions = {},
                                onOpenMember = {},
                                onOpenThread = {},
                                onOpenTask = {},
                                onOpenTaskStatusMenu = {},
                                onToggleReaction = {},
                                onOpenAttachment = {},
                                onOpenMarkdownTag = { _, _ -> },
                                onOpenMarkdownUrl = { url -> linkNavigator.open(url) },
                                showReplyAction = false,
                                showSaveAction = false,
                                taskOpenEnabled = false,
                                taskStatusActionsEnabled = false
                            )
                            if (item.attachments.isNotEmpty() || item.attachmentCount > 0) {
                                ThreadForwardAttachments(
                                    item = item,
                                    modifier = Modifier.padding(start = 60.dp, end = RaftAppSpacing.Md, bottom = RaftAppSpacing.Sm)
                                )
                            }
                        }
                    }
                }
            }
            detailState.status == ThreadForwardBundleDetailStatus.Loading ->
                RaftCenteredLoading(
                    message = stringResource(Res.string.common_loading),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            else ->
                ThreadForwardBundleLoadFailure(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRetry = { detailViewModel?.dispatch(ThreadForwardBundleDetailIntent.Retry) }
                )
        }
    }
    }
}

internal fun threadForwardBundleContentAdmitted(
    visualFixture: Boolean,
    productionAuthorityValid: Boolean,
    status: ThreadForwardBundleDetailStatus,
): Boolean =
    visualFixture ||
        (productionAuthorityValid && status == ThreadForwardBundleDetailStatus.Content)

@Composable
private fun ThreadForwardBundleLoadFailure(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Res.string.thread_forward_bundle_unavailable),
            style = RaftType.Body.withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.TextBlack
        )
        Spacer(Modifier.height(RaftAppSpacing.Md))
        RaftButton(
            text = stringResource(Res.string.common_retry),
            onClick = onRetry,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md
        )
    }
}

// The bundle page renders read-only standard rows; forwarded items carry only
// a subset of message fields, so map what exists and let the card's defaults
// cover the rest.
private fun ForwardMessageItem.toThreadMessageItemForBundlePage(): RaftThreadMessageItem =
    RaftThreadMessageItem(
        id = id,
        authorId = authorId,
        authorName = authorName,
        authorKind = authorKind,
        avatarLabel = avatarLabel.ifBlank { authorName.toRaftAvatarLabel() },
        avatarWireValue = avatarWireValue,
        timestampLabel = timestampLabel,
        body = body
    )


internal fun threadForwardBundleVisualFixtureBundle(): ForwardMessageBundle =
    ForwardMessageBundle(
        id = "bundle-visual-1",
        title = "",
        sourceTitle = "#design",
        totalCount = 5,
        messages =
            listOf(
                ForwardMessageItem(
                    id = "fwd-1",
                    authorId = "user-cindy",
                    authorName = "Cindy",
                    authorKind = "agent",
                    avatarLabel = "C",
                    timestampLabel = "10:38",
                    // Markdown on purpose: forwarded bodies render through
                    // RaftMarkdownBody (task #63) — captures must show bold
                    // ink + code chip, not literal ** and backticks.
                    body = "Captured the **Android visual artifact** and queued `React parity`.",
                    attachmentCount = 0
                ),
                ForwardMessageItem(
                    id = "fwd-2",
                    authorId = "user-artin",
                    authorName = "artin",
                    authorKind = "human",
                    avatarLabel = "a",
                    timestampLabel = "10:31",
                    body = "",
                    attachmentCount = 2,
                    attachments =
                        listOf(
                            ForwardMessageAttachment("visual-parity-notes.pdf", "application/pdf"),
                            ForwardMessageAttachment("home-rows-android.png", "image/png")
                        )
                ),
                ForwardMessageItem(
                    id = "fwd-3",
                    authorId = "user-ad",
                    authorName = "Android Developer",
                    authorKind = "agent",
                    avatarLabel = "A",
                    timestampLabel = "09:58",
                    body = "Search parity now covers sender filters and time ranges.",
                    attachmentCount = 1,
                    attachments = listOf(ForwardMessageAttachment("capture-timing.log", "text/plain"))
                ),
                ForwardMessageItem(
                    id = "fwd-4",
                    authorId = "user-qa",
                    authorName = "QA Tester",
                    authorKind = "agent",
                    avatarLabel = "Q",
                    timestampLabel = "09:45",
                    body = "Fourth message exists to overflow the inline three-message preview.",
                    attachmentCount = 0
                ),
                ForwardMessageItem(
                    id = "fwd-5",
                    authorId = "user-libai",
                    authorName = "LiBai",
                    authorKind = "agent",
                    avatarLabel = "L",
                    timestampLabel = "09:30",
                    body = "Fifth message keeps the full-page list scrollable in the pager fixture.",
                    attachmentCount = 0
                )
            )
    )

@Composable
internal fun <T> RaftForwardTargetPicker(
    messageCount: Int,
    skippedCount: Int,
    nestedForwardCount: Int,
    state: RaftForwardTargetPickerUiState<T>,
    onIntent: (RaftForwardTargetPickerIntent<T>) -> Unit,
    topContent: (@Composable () -> Unit)? = null,
    searchInputInteractive: Boolean = state.interactive,
    bottomSafeArea: Dp = 0.dp,
    // web ForwardComposerMobile: the source the messages come from ("#design"); the header
    // reads "2 selected from #design". Null keeps the plain message count.
    sourceLabel: String? = null,
    // web mobile picker: a tap forwards to that one place; "Select Multiple" switches to
    // checkboxes and the Continue footer. False keeps the checkbox mode only.
    singleTapContinues: Boolean = false
) {
    var multiSelect by remember(state.sessionKey) { mutableStateOf(!singleTapContinues) }
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    val loading = state.loadState == RaftForwardPickerLoadState.Loading
    // A search refinement keeps the current rows on screen (they stay non-interactive until
    // the new generation lands) instead of veiling them with the skeleton per keystroke.
    val refiningSearch = loading && state.query.isNotBlank() && !state.appending
    val listState = key(state.sessionKey) { rememberLazyListState() }
    RaftBackHandler(enabled = state.dismissEnabled, label = "thread_forward_targets") {
        onIntent(RaftForwardTargetPickerIntent.Dismiss)
    }
    // The "Threads & tasks" group (its rows plus two section titles) sits before the Recent rows.
    val recentListOffset = state.visibleConversationItems.size.let { if (it > 0) it + 2 else 0 }
    LaunchedEffect(state.sessionKey, state.canLoadMore, state.appending, state.items.size, state.interactive, recentListOffset) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.canLoadMore && state.interactive && !state.appending &&
                lastVisible >= state.items.lastIndex + recentListOffset - 2
        }.distinctUntilChanged().collect { shouldLoadMore ->
            if (shouldLoadMore) onIntent(RaftForwardTargetPickerIntent.LoadMore)
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(100f)
    ) {
        // Keep the native-dispatch barrier behind the interactive content.
        // Kuikly LazyColumn is backed by a native ScrollerView; placing capture
        // on this parent consumes ACTION_DOWN before that child can scroll.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(RaftColor.SurfaceWhite)
                    .raftNativeDispatchCapture()
        )
        Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
            RaftForwardPickerHeader(
                subtitle =
                    sourceLabel?.takeIf { it.isNotBlank() }?.let { source ->
                        stringResource(Res.string.thread_forward_selected_from, messageCount, source)
                    } ?: stringResource(Res.string.thread_forward_messages_selected, messageCount),
                multiSelect = multiSelect,
                multiSelectAvailable = singleTapContinues,
                dismissEnabled = state.dismissEnabled,
                onBack = {
                    if (singleTapContinues && multiSelect) {
                        state.selectedKeys.forEach { key ->
                            onIntent(RaftForwardTargetPickerIntent.RemoveSelection(key))
                        }
                        multiSelect = false
                    } else if (state.dismissEnabled) {
                        onIntent(RaftForwardTargetPickerIntent.Dismiss)
                    }
                },
                onStartMultiSelect = {
                    state.selectedKeys.forEach { key ->
                        onIntent(RaftForwardTargetPickerIntent.RemoveSelection(key))
                    }
                    multiSelect = true
                }
            )
            topContent?.invoke()
            threadForwardSkippedWarningRes(skippedCount)?.let { warning ->
                RaftBanner(
                    description = stringResource(warning, skippedCount),
                    status = RaftBannerStatus.Warning,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    size = RaftBannerSize.Sm,
                )
            }
            // web `InputGroup` with the inline-start Search addon, inside `main p-3`, `mb-3`.
            // web ForwardComposerMobile `autoFocusSearch` (slock #12083): a touch phone keeps the
            // keyboard closed when the picker opens, so the recent destinations stay in view; it
            // opens when people tap the field. Only a mouse-and-keyboard window focuses it.
            RaftForwardPickerSearchField(
                query = state.query,
                enabled = searchInputInteractive,
                autoFocus = singleTapContinues && raftPlatformHasFinePointer(),
                focusKey = state.sessionKey,
                onQueryChange = { onIntent(RaftForwardTargetPickerIntent.QueryChanged(it)) }
            )
            val conversationItems = state.visibleConversationItems
            if (state.forwardPickerTopEyebrowVisible(multiSelect && singleTapContinues)) {
                RaftForwardPickerEyebrow(
                    text =
                        when {
                            multiSelect && singleTapContinues && state.selectedKeys.isNotEmpty() ->
                                stringResource(Res.string.thread_forward_selected, state.selectedKeys.size)
                            state.query.isBlank() -> stringResource(Res.string.thread_forward_recent)
                            else -> stringResource(Res.string.thread_forward_search_results)
                        },
                    modifier = Modifier.padding(top = ForwardPickerBodyPadding)
                )
            } else {
                Spacer(modifier = Modifier.height(ForwardPickerBodyPadding))
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (state.items.isEmpty() && conversationItems.isEmpty() && loading) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        repeat(4) { ThreadForwardSearchingSkeletonRow() }
                    }
                } else if (state.items.isEmpty() && conversationItems.isEmpty()) {
                    ThreadModalEmptyState(
                        title =
                            when {
                                state.errorCode != null -> stringResource(Res.string.thread_forward_targets_unavailable)
                                state.query.isBlank() -> stringResource(Res.string.thread_forward_no_recent_targets)
                                else -> stringResource(Res.string.thread_forward_no_targets)
                            },
                        detail =
                            when {
                                state.errorCode != null ->
                                    userFacingErrorLabels.forErrorCode(state.errorCode)
                                        ?: stringResource(Res.string.common_error)
                                state.query.isBlank() -> stringResource(Res.string.thread_forward_recent_targets_hint)
                                else -> stringResource(Res.string.thread_add_member_try_different_search)
                            }
                    )
                    if (state.errorCode != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            RaftModalActionButton(
                                label = stringResource(Res.string.common_retry),
                                icon = RaftLucideIconName.RefreshCw,
                                onClick = { onIntent(RaftForwardTargetPickerIntent.Retry) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().background(RaftColor.Canvas),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(ForwardPickerListRowGap)
                    ) {
                        // slock #12621 layout A: "Threads & tasks" above "Recent", each under its own title.
                        if (conversationItems.isNotEmpty()) {
                            item(key = "forward-conversation-group-title") {
                                RaftForwardPickerEyebrow(
                                    text = stringResource(Res.string.thread_forward_conversations),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(conversationItems.size, key = { "conversation:" + conversationItems[it].rowKey }) { index ->
                                val rowTarget = conversationItems[index]
                                val rowGeneration = state.requestGeneration
                                RaftForwardPickerTargetRow(
                                    // Only the group's first row can be the conversation it was opened from.
                                    target = if (index == 0) rowTarget else rowTarget.copy(current = false),
                                    selected = rowTarget.stableKey in state.selectedKeys,
                                    showCheckbox = multiSelect,
                                    interactive = state.interactive,
                                    onClick = {
                                        raftForwardPickerRowTap(
                                            state = state,
                                            rowTarget = rowTarget,
                                            rowGeneration = rowGeneration,
                                            multiSelect = multiSelect,
                                            onIntent = onIntent
                                        )
                                    }
                                )
                            }
                            item(key = "forward-recent-group-title") {
                                RaftForwardPickerEyebrow(
                                    text = stringResource(Res.string.thread_forward_recent),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        items(state.items.size, key = { state.items[it].rowKey }) { index ->
                            // task #803: freeze the row identity at RENDER time.
                            // Re-reading targets[index] inside onClick raced list
                            // refreshes — displayed A could select B (feedback #152).
                            val rowTarget = state.items[index]
                            val rowGeneration = state.requestGeneration
                            RaftForwardPickerTargetRow(
                                target = rowTarget,
                                selected = rowTarget.stableKey in state.selectedKeys,
                                showCheckbox = multiSelect,
                                interactive = state.interactive,
                                onClick = {
                                    raftForwardPickerRowTap(
                                        state = state,
                                        rowTarget = rowTarget,
                                        rowGeneration = rowGeneration,
                                        multiSelect = multiSelect,
                                        onIntent = onIntent
                                    )
                                }
                            )
                        }
                    }
                    if ((loading && !refiningSearch) || state.errorCode != null) {
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .background(RaftColor.SurfaceWhite.copy(alpha = 0.82f))
                                    .raftNativeDispatchCapture(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (loading) {
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    repeat(4) { ThreadForwardSearchingSkeletonRow() }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(Res.string.thread_forward_targets_unavailable),
                                        style = RaftType.Body,
                                        color = RaftColor.TextBlack
                                    )
                                    RaftModalActionButton(
                                        label = stringResource(Res.string.common_retry),
                                        icon = RaftLucideIconName.RefreshCw,
                                        onClick = { onIntent(RaftForwardTargetPickerIntent.Retry) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (multiSelect && state.selectedItems.isNotEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(RaftColor.Canvas)
                            .padding(
                                start = 12.dp,
                                top = 12.dp,
                                end = 12.dp,
                                bottom = raftForwardPickerActionBottomPadding(bottomSafeArea)
                            ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.thread_forward_destinations_selected, state.selectedItems.size),
                        // Web ForwardComposerMobile SectionEyebrow: text-footnote font-bold (13/18 700).
                        style = RaftType.SectionLabel,
                        color = RaftColor.TextMutedGray
                    )
                    val backSwipeRowState = rememberLazyListState()
                    val backSwipeRow = rememberRaftBackSwipeScroll(backSwipeRowState)
                    LazyRow(state = backSwipeRowState, userScrollEnabled = backSwipeRow.userScrollEnabled, modifier = backSwipeRow.modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.selectedItems.size, key = { state.selectedItems[it].rowKey }) { index ->
                            val selected = state.selectedItems[index]
                            val removeLabel = stringResource(Res.string.thread_forward_remove_destination, selected.title)
                            RaftModalActionButton(
                                label = selected.title,
                                trailingIcon = RaftLucideIconName.X,
                                modifier = Modifier.semantics { contentDescription = removeLabel },
                                onClick = {
                                    onIntent(RaftForwardTargetPickerIntent.RemoveSelection(selected.stableKey))
                                },
                                enabled = state.interactive
                            )
                        }
                    }
                    RaftModalFullWidthActionButton(
                        label = stringResource(Res.string.common_continue),
                        icon = RaftLucideIconName.ChevronRight,
                        onClick = { onIntent(RaftForwardTargetPickerIntent.Continue) },
                        enabled = state.interactive
                    )
                }
            }
        }
    }
}

/** One row tap of the forward picker (a Recent / search row or a "Threads & tasks" row). */
private fun <T> raftForwardPickerRowTap(
    state: RaftForwardTargetPickerUiState<T>,
    rowTarget: RaftForwardTargetPickerItem<T>,
    rowGeneration: Int,
    multiSelect: Boolean,
    onIntent: (RaftForwardTargetPickerIntent<T>) -> Unit
) {
    if (!state.interactive) return
    if (multiSelect) {
        onIntent(RaftForwardTargetPickerIntent.TargetSelected(item = rowTarget, renderGeneration = rowGeneration))
        return
    }
    // One tap is one destination: drop any earlier pick (back from the note step), pick this, go on.
    state.selectedKeys
        .filter { it != rowTarget.stableKey }
        .forEach { key -> onIntent(RaftForwardTargetPickerIntent.RemoveSelection(key)) }
    if (rowTarget.stableKey !in state.selectedKeys) {
        onIntent(RaftForwardTargetPickerIntent.TargetSelected(item = rowTarget, renderGeneration = rowGeneration))
    }
    onIntent(RaftForwardTargetPickerIntent.Continue)
}

@Composable
internal fun ThreadForwardNoteDialog(
    messageCount: Int,
    selectedTargetLabel: String,
    note: TextFieldValue,
    sending: Boolean,
    onNoteChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.thread_forward_add_note),
        uppercaseTitle = false,
        onDismiss = onDismiss,
        dismissEnabled = !sending,
        dismissOnOutsideTap = false,
        keyboardAvoidanceEnabled = true,
        minWidth = 320.dp,
        maxWidth = 430.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = stringResource(Res.string.thread_forward_destination),
                    style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = RaftColor.TextMutedGray
                )
            }
            item {
                Text(
                    text = selectedTargetLabel,
                    style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            item {
                RaftField(
                    label = stringResource(Res.string.thread_state_note),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Three 24dp field lines inside the 10dp padding, then it scrolls.
                    RaftTextarea(
                        value = note,
                        onValueChange = onNoteChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder =
                            if (messageCount == 1) {
                                stringResource(Res.string.thread_forward_placeholder_one)
                            } else {
                                stringResource(Res.string.thread_forward_placeholder_many, messageCount)
                            },
                        minHeight = 76.dp,
                        maxHeight = 92.dp,
                        inputModifier = rememberRaftTextareaEditorModifier()
                    )
                }
            }
        }
        RaftDialogFooter {
            RaftModalActionButton(
                label = stringResource(Res.string.common_back),
                icon = RaftLucideIconName.ArrowLeft,
                onClick = onDismiss,
                enabled = !sending
            )
            RaftModalActionButton(
                label =
                    if (sending) {
                        stringResource(Res.string.thread_forward_sending)
                    } else {
                        stringResource(Res.string.common_send)
                    },
                icon = RaftLucideIconName.Send,
                onClick = onSend,
                color = RaftColor.AccentPink,
                enabled = selectedTargetLabel.isNotBlank(),
                loading = sending
            )
        }
    }
}

@Composable
private fun ThreadForwardSearchingSkeletonRow() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .height(60.dp)
                .raftCardSurface()
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(38.dp).background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Sticker)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.width(144.dp).height(12.dp).background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Xs)))
                Box(modifier = Modifier.width(92.dp).height(9.dp).background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Xs)))
            }
        }
    }
}
