package build.raft.app.thread

import build.raft.app.data.memberOrigin
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ConversationKind
import build.raft.app.model.ExternalMessageAuthor
import build.raft.app.model.MessageDelegatedBy
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.MemberServerRef
import build.raft.app.model.TaskSummary
import build.raft.app.model.formatRaftTimestampEpochMillis
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.resolveOpenRequest
import build.raft.app.thread.toRaftTimeContext
import build.raft.app.toRaftAvatarLabel
import build.raft.app.toRaftTimeContext
import build.raft.app.traceId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.RaftTimeContext
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.ChannelMemberRoleAction
import build.raft.app.ConversationOpenResolution
import build.raft.app.ConversationOpenSource
import build.raft.app.ConversationOpenSourceMessageIdentity
import build.raft.app.HomeMessageOpenRequest
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMessageTimeFormat
import build.raft.app.RaftPendingAttachmentItem
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.SharedThreadComposerAttachment
import build.raft.app.SharedThreadComposerExternalShareSource
import build.raft.app.ThreadComposerClipboardImageSource
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.random.Random

/**
 * Mints optimistic local-send ids: `local-send-<scope>-<processNonce>-<counter>`.
 *
 * The counter restarts in every process, but pending sends are journaled and restored after a
 * restart, so the counter alone would hand a new send the id of a restored row (two rows with
 * `…-1` crashed the message list with a duplicate LazyColumn key). The per-process nonce keeps
 * ids unique across restarts. The counter stays the last `-` segment because
 * [RaftThreadMessageItem.localSendOrder] parses it from there; the nonce is base36 (no `-`).
 */
internal class ThreadLocalSendIdGenerator(
    private val processNonce: String = newThreadLocalSendProcessNonce()
) {
    private var counter: Int = 0

    fun next(instanceId: String): String {
        counter += 1
        val scope = instanceId.takeIf { it.isNotBlank() } ?: "thread"
        return "$ThreadTransientLocalSendIdPrefix$scope-$processNonce-$counter"
    }
}

internal fun newThreadLocalSendProcessNonce(): String = Random.nextLong().toULong().toString(36)

internal fun newThreadSendRandomId(): String =
    "mobile-${Random.nextLong().toULong().toString(36)}-${Random.nextLong().toULong().toString(36)}"

@Serializable
internal data class RaftThreadMessageItem(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String = "",
    // The message's own sender description (`senderDescription`; an external sender's provider).
    // The author row prefers the directory's (threadAuthorRowDescription); never a role.
    val authorDescription: String? = null,
    val authorKind: String = "",
    val isOwn: Boolean = false,
    val avatarLabel: String = "",
    val avatarWireValue: String? = null,
    /** Slack-bridge external sender attribution (senderType=external_projection). */
    val externalAuthor: ExternalMessageAuthor? = null,
    /** RFC 077: the assistant that sent this message for its (human) author. */
    val delegatedBy: MessageDelegatedBy? = null,
    /** RFC 077 PR 2b: set when the owner retracted this message (wire `deletedAt`); see [isRetracted]. */
    val deletedAt: String? = null,
    val senderMembershipStatus: String? = null,
    val authorDeletedAt: String? = null,
    val timestampLabel: String = "",
    val seq: Long? = null,
    val messageType: String = "Chat",
    val systemEventType: String? = null,
    /** Server-authored system row subtype (`worker.ended`, etc.); null for chat or untyped system rows. */
    val systemSubtype: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val body: String = "",
    /**
     * artin 10-26: this is a stand-in seeded from an Activity row whose server preview was cut
     * ([activitySeedPreviewIsTruncated]); [body] holds the cut text with its "…", and the card
     * shows a loading indicator below it until the canonical load replaces the message. Only the
     * Activity open seed sets it; a message read from the server or a cache never does.
     */
    val activityPreviewTruncated: Boolean = false,
    /**
     * A cut Activity stand-in ([activityPreviewTruncated]) whose canonical load failed (offline, an
     * error): the card says the full message couldn't load instead of showing the cut text as the
     * message. Only [withActivityPreviewSettled] sets it; the canonical message replaces it.
     */
    val activityPreviewLoadFailed: Boolean = false,
    val randomId: String? = null,
    val threadChannelId: String? = null,
    val replyCount: Int = 0,
    val unreadReplyCount: Int = 0,
    val firstUnreadReplyMessageId: String? = null,
    val isThreadFollowed: Boolean = false,
    /**
     * Bounded derived latest-replies projection. It is deliberately transient:
     * production route/pageData remains identity+intent only, and canonical
     * summary loading rehydrates this projection after restore.
     */
    @Transient
    val inlineReplies: ThreadRepliesReadModel? = null,
    val isSaved: Boolean = false,
    val taskId: String? = null,
    val taskNumber: Int? = null,
    val taskStatus: String? = null,
    val taskAssigneeKind: String? = null,
    val taskAssigneeId: String? = null,
    val taskAssigneeName: String? = null,
    val reactions: List<RaftThreadReactionItem> = emptyList(),
    val attachments: List<RaftThreadAttachmentItem> = emptyList(),
    val commentRef: RaftThreadMessageCommentRef? = null,
    val forwardBundle: ForwardMessageBundle? = null,
    /** Resolved `mentions[]` of the message (name -> type/id); web `buildStructuredMentionMap`. */
    val mentions: List<build.raft.app.model.MessageMention> = emptyList(),
    /**
     * Quote replies the server verified for this viewer, by quote block index
     * ([build.raft.app.model.Message.resolvedQuotes]): a card for each ([ThreadQuoteReplyBody]). Null
     * for a copy that never carries them (realtime, the sync log): the row reads them on its own.
     */
    val resolvedQuotes: Map<String, build.raft.app.model.ResolvedInlineQuote>? = null,
    val deliveryState: String = ThreadMessageDeliverySent,
    val pendingSendContext: RaftThreadPendingSendContext? = null,
    val pendingSendErrorCode: String? = null,
    val pendingSendErrorMessage: String? = null,
    /** Canonical numeric creation time. Sorting must not parse the localized timestamp label. */
    val createdAtEpochMillis: Long? = null,
    /** Stable order for same-instant optimistic sends; null for canonical server messages. */
    val localSendOrder: Long? = null,
)

internal const val ThreadMessageDeliverySending = "sending"
internal const val ThreadMessageDeliverySent = "sent"
internal const val ThreadMessageDeliveryFailed = "failed"

@Serializable
internal data class RaftThreadPendingSendContext(
    val channelId: String,
    val body: String,
    val asTask: Boolean = false,
    val attachmentIds: List<String> = emptyList(),
    val attachments: List<RaftPendingAttachmentItem> = emptyList(),
    val ownedTemporaryFileRefs: List<String> = emptyList(),
    val externalShareSources: List<SharedThreadComposerExternalShareSource> = emptyList(),
    val mentionChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
    val memberScopedMentions: Boolean = false,
    val randomId: String? = null,
    /**
     * Structured `mentions` picked in the composer and still visible in [body] at send time. Journaled
     * with the send so every retry resends this exact list (never re-resolved by name).
     */
    val mentions: List<build.raft.app.data.ThreadSendMessageMention> = emptyList(),
)

/**
 * Process-local files which the composer is allowed to retire after a pending send reaches a
 * terminal state.  Editor and compressor outputs are included alongside picker/camera results;
 * remote attachments and external-share handles are intentionally excluded.
 */
internal val ThreadComposerOwnedTemporarySources =
    setOf(
        "platform_picker",
        "platform_camera",
        "composer_image_editor",
        "composer_image_compressor",
        ThreadComposerClipboardImageSource
    )

internal fun threadPendingSendOwnedTemporaryFileRefs(
    attachments: List<SharedThreadComposerAttachment>,
    attachmentIds: Collection<String>
): List<String> {
    val acceptedIds = attachmentIds.toSet()
    return attachments
        .asSequence()
        .filter { attachment -> attachment.id in acceptedIds }
        // This is the generic pending-send ownership boundary, not the image-compression
        // classifier.  Videos and ordinary files also receive process-local picker/camera/editor
        // handles and must be retired after a terminal send; media-type exclusion belongs only in
        // image admission helpers such as `isComposerImageOwnedTemporary`.
        .filter { attachment -> attachment.source in ThreadComposerOwnedTemporarySources }
        .mapNotNull { attachment -> attachment.fileRef?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()
}

internal const val ThreadSentImageLocalSeedRetireWaitMs = 5_000L

/** Local image sources of the attachments in this send, keyed by their server attachment id. */
internal fun threadSentAttachmentLocalImageSources(
    attachments: List<SharedThreadComposerAttachment>,
    attachmentIds: Collection<String>
): List<build.raft.app.image.RaftSentAttachmentLocalSource> {
    val acceptedIds = attachmentIds.toSet()
    return attachments
        .asSequence()
        .filter { attachment -> attachment.id in acceptedIds }
        .mapNotNull { attachment ->
            val fileRef =
                attachment.fileRef?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
            build.raft.app.image.RaftSentAttachmentLocalSource(
                attachmentId = attachment.id,
                fileRef = fileRef,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes
            )
        }
        .distinctBy { source -> source.attachmentId }
        .toList()
}

internal fun threadComposerMessageBodyForSend(draftText: String, attachmentCount: Int): String {
    val cleanDraft = draftText.trim()
    if (cleanDraft.isNotBlank() || attachmentCount <= 0) return cleanDraft
    val suffix = if (attachmentCount == 1) "attachment" else "attachments"
    return "[$attachmentCount $suffix]"
}

internal fun RaftThreadMessageItem.hasLocalSendState(): Boolean =
    deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) ||
        pendingSendContext != null

/**
 * A failed send whose attachment id the server refused as dead (expired, canceled, already linked…)
 * can never succeed with the same ids, so it offers no Retry: its line says to upload the file
 * again (web `message.composer.attachmentsNeedReupload`, botiverse/slock #15130).
 */
internal fun RaftThreadMessageItem.canRetryLocalSend(): Boolean =
    isFailedLocalSend() && !threadSendFailureNeedsAttachmentReupload(pendingSendErrorCode)

internal fun RaftThreadMessageItem.isFailedLocalSend(): Boolean =
    deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) && pendingSendContext != null

internal fun RaftThreadMessageItem.isSendingLocalSend(): Boolean =
    deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) && pendingSendContext != null

/**
 * Retry sends the message again as a new send (web: a failed send goes back to the composer and is
 * sent anew): it takes the retry's time, so it moves to the bottom instead of staying where the
 * failed attempt sits in the timeline.
 */
internal fun RaftThreadMessageItem.restampedForLocalSendRetry(timestampLabel: String): RaftThreadMessageItem {
    val retriedAt = parseRaftTimestampEpochMillisOrNull(timestampLabel) ?: return this
    return copy(timestampLabel = timestampLabel, createdAtEpochMillis = retriedAt)
}

@Serializable
internal data class RaftThreadMessageCommentRef(
    val attachmentId: String,
    val filename: String,
    val hostMessageId: String? = null,
    val hostSource: RaftThreadMessageCommentHostSource? = null,
    val anchorLabel: String? = null
)

@Serializable
internal data class RaftThreadMessageCommentHostSource(
    val type: String,
    val routeKind: String,
    val channelId: String,
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null
)

@Serializable
internal data class RaftThreadReactionItem(
    val emoji: String,
    val count: Int = 0,
    val selected: Boolean = false,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList()
)

@Serializable
internal data class RaftThreadAttachmentItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val sizeLabel: String = "",
    val sizeBytes: Long = 0L,
    val thumbnailUrl: String? = null,
    val commentCount: Int = 0,
    /** Intrinsic pixel size when the server knows it: reserves the inline image's box before it loads. */
    val width: Int? = null,
    val height: Int? = null
)

internal data class ThreadAttachmentCommentsDialogState(
    val attachment: RaftThreadAttachmentItem,
    val status: String = "loading",
    val page: ThreadAttachmentCommentsPage? = null,
    val filter: String = "all",
    val draft: TextFieldValue = TextFieldValue(),
    val sending: Boolean = false,
    val resolvingCommentIds: Set<String> = emptySet(),
    val pendingAnchorLabel: String? = null,
    val pendingQuoteLabel: String? = null,
    /** The structural anchor the next comment is sent with (a video timestamp); its chip is [pendingAnchorLabel]. */
    val pendingAnchor: build.raft.app.data.ThreadAttachmentCommentAnchor? = null,
    val errorCode: String? = null,
    val errorServerMessage: String? = null,
    val errorTraceId: String? = null
)

internal fun ThreadAttachmentCommentsDialogState.withAttachmentCommentEditorValue(
    value: TextFieldValue
): ThreadAttachmentCommentsDialogState =
    copy(draft = value).clearAttachmentCommentsError()

/**
 * Reconciles an intentional external draft change without disturbing active editor metadata when
 * the product projection is text-identical. Native input instead goes through
 * [withAttachmentCommentEditorValue], which stores the exact callback [TextFieldValue].
 */
internal fun ThreadAttachmentCommentsDialogState.withAttachmentCommentDraftProjection(
    projectedText: String
): ThreadAttachmentCommentsDialogState {
    if (draft.text == projectedText) return this
    return copy(
        draft = TextFieldValue(projectedText, selection = TextRange(projectedText.length))
    )
}

internal fun ThreadAttachmentCommentsDialogState.withQuotedAttachmentComment(
    comment: ThreadAttachmentCommentItem
): ThreadAttachmentCommentsDialogState {
    val nextText = appendThreadQuoteToComposer(draft.text, formatThreadMessageQuote(comment.content))
    return withAttachmentCommentDraftProjection(nextText)
        .copy(
            pendingQuoteLabel = comment.senderName,
            pendingAnchorLabel = comment.anchorLabel ?: attachment.name
        ).clearAttachmentCommentsError()
}

internal fun ThreadAttachmentCommentsDialogState.clearAttachmentCommentsError():
    ThreadAttachmentCommentsDialogState =
    copy(errorCode = null, errorServerMessage = null, errorTraceId = null)

internal fun ThreadAttachmentCommentsDialogState.withAttachmentCommentsError(
    errorCode: String?,
    serverMessage: String?,
    traceId: String? = null
): ThreadAttachmentCommentsDialogState =
    copy(
        errorCode = errorCode?.trim()?.takeIf { it.isNotBlank() },
        errorServerMessage = serverMessage?.takeIf { it.isNotBlank() },
        errorTraceId = traceId?.trim()?.takeIf { it.isNotBlank() }
    )

internal fun attachmentCommentsVisibleErrorMessage(
    errorCode: String?,
    serverMessage: String?,
    labels: ThreadUserFacingErrorLabels,
    genericMessage: String
): String =
    threadUserFacingErrorCodeReason(errorCode)
        ?.takeUnless { it == ThreadUserFacingErrorReason.Generic }
        ?.let(labels::forReason)
        ?: serverMessage?.takeIf { it.isNotBlank() }
        ?: genericMessage

internal data class ThreadCreateAgentActionDialogState(
    val messageId: String,
    val card: ThreadActionCard,
    val submitting: Boolean = false,
    val errorCode: String? = null,
    // task #193 honest-states: raw server error text for the unmapped-code
    // fallback so the dialog never shows a bare code.
    val errorServerMessage: String? = null
)

// task #193: single-source state transitions so a previous request's raw
// server message can never leak into a later request's error display —
// every transition that sets or clears errorCode settles errorServerMessage
// in the same step.
internal fun ThreadCreateAgentActionDialogState.submittingWith(
    card: ThreadActionCard
): ThreadCreateAgentActionDialogState =
    copy(card = card, submitting = true, errorCode = null, errorServerMessage = null)

internal fun ThreadCreateAgentActionDialogState.failedWith(
    errorCode: String?,
    errorServerMessage: String? = null
): ThreadCreateAgentActionDialogState =
    copy(submitting = false, errorCode = errorCode, errorServerMessage = errorServerMessage)

internal data class ThreadImageAttachmentGroup(
    val images: List<RaftThreadAttachmentItem>,
    val currentIndex: Int
)

internal data class ThreadAttachmentCommentTarget(
    val attachment: RaftThreadAttachmentItem,
    val parentMessage: RaftThreadMessageItem
)

internal fun raftThreadAttachmentOpenStatus(nativeStatus: String): String =
    when (nativeStatus) {
        "opened", "cancelled", "save_cancelled", "missing", "no_app", "storage_failed", "download_failed" -> nativeStatus
        // APK install handed to the settings switch or the file manager: that screen is the feedback.
        "install_permission_required", "install_via_files" -> "opened"
        else -> "failed"
    }

internal data class RaftThreadWebLinkTarget(
    val channelId: String,
    val messageId: String? = null,
    val routeKind: String = RaftThreadSurfaceKind.Channel,
    val threadParentMessageId: String? = null
)

internal fun RaftThreadWebLinkTarget.resolveMarkdownOpenRequest(
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState
): HomeMessageOpenRequest? {
    val parentMessage = threadParentMessageId?.takeIf { it.isNotBlank() }
    val routeConversationKind =
        when (routeKind) {
            RaftThreadSurfaceKind.Channel -> ConversationKind.Channel
            RaftThreadSurfaceKind.DirectMessage -> ConversationKind.DirectMessage
            else -> null
        }
    val kind =
        ConversationKind.Thread.takeIf { parentMessage != null }
            ?: routeConversationKind
    val sourceIdentity =
        ConversationOpenSourceMessageIdentity(
            source = ConversationOpenSource.Markdown,
            messageId = messageId?.takeIf { it.isNotBlank() },
            channelId = channelId.takeIf { it.isNotBlank() },
            parentChannelId = channelId.takeIf { parentMessage != null && it.isNotBlank() },
            parentMessageId = parentMessage,
            conversationKind = kind,
            // The canonical permalink path is typed identity, not a guessed fallback.
            // Preserve its channel/dm kind so #855 can rebuild safely when the local
            // conversation directory has not hydrated this target yet.
            parentConversationKind = routeConversationKind.takeIf { parentMessage != null }
        )
    return (resolveOpenRequest(serverId, sourceIdentity, conversationDirectory)
        as? ConversationOpenResolution.Resolved)
        ?.request
}

@Serializable
internal data class RaftThreadTaskItem(
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
internal data class RaftThreadMemberItem(
    val id: String,
    val displayName: String,
    val handle: String = "",
    val roleLabel: String = "",
    val description: String = "",
    val avatarUrl: String? = null,
    val avatarLabel: String = displayName.toRaftAvatarLabel(),
    val kind: String = "human",
    val sourceServerId: String? = null,
    /**
     * The roster row's own `serverId` (the projection it came from; on a computed joint row the
     * person's first source), when it differs from [sourceServerId]'s reading. Only for "already
     * joined here" (web `isLocalProjectionMember`); null falls back to [sourceServerId].
     */
    val rosterServerId: String? = null,
    val sourceServerLabel: String? = null,
    val canRemove: Boolean = true,
    val effectiveChannelRole: String? = null,
    /** The row's channel role action ([channelMemberRoleAction]); null offers none. */
    val roleAction: ChannelMemberRoleAction? = null,
    /**
     * Someone from another server ([memberOrigin], web `jointMemberOrigin`): every server they are
     * from, the viewed one left out. Their row carries the first one's tile on the avatar and the
     * names after the name, in place of the server chip. Empty for the viewed server's members.
     */
    val originServers: List<MemberServerRef> = emptyList(),
    /**
     * A member of the viewed server who is also in another (web `memberServerBadgeLabels`): every
     * server they take part through, in roster order. Their row reads "Also in Raft, Acme Corp"
     * after the name in quiet text (web #14731). Empty for anyone else.
     */
    val alsoInServerLabels: List<String> = emptyList(),
    /**
     * A computed joint roster row's `inViewerServer` (web `ChannelHuman` / `ChannelAgent`): whether
     * it is one of the viewed server's members (any role, guests too; an agent whose one server is
     * the viewed one). A joint channel's members page groups by it ([threadMembersRosterSections],
     * web `jointMemberGroups`). Null on every other roster and from older servers.
     */
    val inViewerServer: Boolean? = null
)

@Serializable
internal data class RaftThreadSettingsAction(
    val id: String,
    val enabled: Boolean = true,
    val permission: String? = null
)

internal data class ThreadSettingsActionUi(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val icon: RaftLucideIconName,
    val destructive: Boolean
)

internal data class ThreadTimelineRenderConfig(
    val backgroundRefreshing: Boolean,
    val errorCode: String?,
    val errorServerMessage: String? = null,
    val errorTraceId: String? = null,
    val originPreview: String?,
    val originErrorCode: String?,
    val timeContext: RaftTimeContext,
    val members: List<RaftThreadMemberItem>
)

internal fun RaftThreadScreenPayload.toThreadTimelineRenderConfig(): ThreadTimelineRenderConfig =
    ThreadTimelineRenderConfig(
        backgroundRefreshing = backgroundRefreshing,
        errorCode = errorCode,
        errorServerMessage = errorServerMessage,
        errorTraceId = errorTraceId,
        originPreview = originPreview,
        originErrorCode = originErrorCode,
        timeContext = toRaftTimeContext(),
        members = members
    )

internal fun ThreadTimelineRenderConfig.formatTimestampLabel(value: String): String =
    formatRaftTimestampLabel(value, timeContext)

internal fun ThreadTimelineRenderConfig.dateDividerTimeContext(liveTodayEpochDay: Long?): RaftTimeContext =
    timeContext.copy(
        todayEpochDay = liveTodayEpochDay ?: timeContext.todayEpochDay,
        nowEpochMillis = null
    )

/**
 * The time on an action card's done line (web `CommittedByLine`: `formatMessageTime(executedAt)`),
 * as the message rows write it ([messageTime]). The thread payload carries no "today", so the page's
 * live day ([LocalRaftTimeContextProvider]) supplies it, as for the date dividers. Without a day, or
 * a time that can't be read, there is no time rather than the raw wire timestamp.
 */
internal fun ThreadTimelineRenderConfig.formatActionCardExecutedTimeLabel(
    executedAt: String?,
    liveTodayEpochDay: Long?,
    messageTime: RaftMessageTimeFormat,
): String? {
    val value = executedAt?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val context = dateDividerTimeContext(liveTodayEpochDay)
    if (context.todayEpochDay == null) return null
    return messageTime.format(value, context)?.takeIf { it.isNotBlank() }
}

/**
 * A row's time as its header shows it in absolute mode ([messageTime]), or [fallback] (the compact
 * HH:mm) without a live time context. Never the raw wire timestamp.
 */
internal fun threadRowTimeAccessibilityLabel(
    timestampLabel: String,
    fallback: String,
    timeContext: RaftTimeContext?,
    messageTime: RaftMessageTimeFormat,
): String? =
    messageTime.format(timestampLabel, timeContext)?.takeIf { it.isNotBlank() }
        ?: fallback.takeIf { it.isNotBlank() }

internal fun formatThreadCompactTimestampLabel(
    value: String,
    timeContext: RaftTimeContext?
): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseRaftTimestampEpochMillisOrNull(trimmed, timeContext?.localOffsetMinutes ?: 0)
            ?: return trimmed
    // Parseable wire timestamps must never leak when a standalone surface has
    // not established the shared time authority yet. Display-clock literals
    // remain safe for fixtures and legacy cached rows.
    val context = timeContext ?: return ""
    val localEpochDay = threadLocalEpochDay(epochMillis, context.localOffsetMinutes)
    return formatRaftTimestampEpochMillis(
        epochMillis = epochMillis,
        localOffsetMinutes = context.localOffsetMinutes,
        todayEpochDay = localEpochDay,
        timeFormat = RaftTimeFormat24Hour
    )
}

internal fun ThreadTimelineRenderConfig.formatCompactTimestampLabel(message: RaftThreadMessageItem): String =
    formatThreadCompactTimestampLabel(message.timestampLabel, timeContext)

internal fun ForwardMessageItem.formatThreadForwardedTimestampLabel(timeContext: RaftTimeContext?): String =
    formatThreadCompactTimestampLabel(timestampLabel, timeContext)

internal fun ThreadTimelineRenderConfig.resolveTaskAssigneeDisplayName(
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

internal fun RaftThreadScreenPayload.supportsChannelTasks(): Boolean =
    composer.showAsTaskToggle || showTaskOpenActions || showTaskStatusActions
