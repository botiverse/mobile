package build.raft.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftPullToRefreshBox
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedToAnswerState
import build.raft.app.data.ToAnswerAddressee
import build.raft.app.data.ToAnswerCardKind
import build.raft.app.data.ToAnswerConversation
import build.raft.app.data.ToAnswerEnded
import build.raft.app.data.ToAnswerItem
import build.raft.app.data.ToAnswerRequesterType
import build.raft.app.data.ToAnswerStatus
import build.raft.app.data.renderActionCardMsgText
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MillisPerDay
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftEmptyState
import build.raft.designsystem.components.RaftEmptyStateDefaults
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftInboxItemLeading
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftPointerHoverPosition
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.time.Clock

/*
 * Activity's "To answer" list (botiverse/slock #17252 / #17261, #triage:#654 phase 2 placement A,
 * `features/thread/ToAnswerList.tsx`, behind `to_answer_list`): open question, confirm, and
 * request cards waiting on the signed-in reader, grouped by day, with live ended-row fade and
 * tap-to-open navigation at the card's message.
 */

internal val ActivityToAnswerFilterIcon: RaftLucideIconName = RaftLucideIconName.CircleQuestionMark

private val ToAnswerLeadingAvatar: Dp = 35.dp

/** UI wiring passed into [SharedActivityPanelContent] for Activity's "To answer" filter choice. */
@Immutable
internal data class ActivityToAnswerUiState(
    val enabled: Boolean = false,
    val selected: Boolean = false,
    val state: SharedToAnswerState = SharedToAnswerState(),
    val pullRefreshing: Boolean = false,
    val onSelect: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPullRefresh: () -> Unit = {},
    val onOpenItem: (ToAnswerItem) -> Unit = {},
) {
    val showing: Boolean get() = enabled && selected
    val count: Int get() = if (enabled) state.count else 0

    companion object {
        val Disabled = ActivityToAnswerUiState()
    }
}

internal enum class ToAnswerPluralCategory { One, Few, Many, Other }

/** CLDR cardinal plural category for the 8 supported app locales (Russian uses one/few/many; others one/other). */
internal fun toAnswerPluralCategory(count: Int, displayLocale: String): ToAnswerPluralCategory {
    val absCount = kotlin.math.abs(count)
    if (displayLocale.equals("ru", ignoreCase = true) || displayLocale.startsWith("ru-", ignoreCase = true)) {
        val mod10 = absCount % 10
        val mod100 = absCount % 100
        return when {
            mod10 == 1 && mod100 != 11 -> ToAnswerPluralCategory.One
            mod10 in 2..4 && mod100 !in 12..14 -> ToAnswerPluralCategory.Few
            else -> ToAnswerPluralCategory.Many
        }
    }
    return if (absCount == 1) ToAnswerPluralCategory.One else ToAnswerPluralCategory.Other
}

/** Whole days left until [expiresAt], rounded up (`Math.ceil(left / 86_400_000)`); null without one or once past. */
internal fun toAnswerDaysLeft(expiresAt: String?, nowEpochMillis: Long): Int? {
    val raw = expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val at = parseRaftTimestampEpochMillisOrNull(raw) ?: return null
    val left = at - nowEpochMillis
    if (left <= 0L) return null
    return ((left + MillisPerDay - 1L) / MillisPerDay).toInt()
}

private fun toAnswerConversationKind(wireType: String): ConversationKind =
    when (wireType.trim().lowercase()) {
        "dm" -> ConversationKind.DirectMessage
        "thread" -> ConversationKind.Thread
        "task" -> ConversationKind.Task
        "joint" -> ConversationKind.Joint
        "channel", "private" -> ConversationKind.Channel
        else -> parseConversationKindOrNull(wireType) ?: ConversationKind.Channel
    }

/**
 * Resolves a tap on a "To answer" row into a [ConversationOpenResolution] focused at
 * [ToAnswerItem.messageId] (reusing Activity's canonical conversation open resolver and task
 * conversation launch path).
 */
internal fun ToAnswerItem.toHomeMessageOpenRequest(
    serverId: String,
    conversationDirectory: SharedConversationDirectoryState,
    diagnosticsLogger: (String) -> Unit = {},
): ConversationOpenResolution {
    val convId = conversation.id.trim()
    if (conversation.type.equals("task", ignoreCase = true) && convId.isNotEmpty()) {
        return ConversationOpenResolution.Resolved(
            HomeMessageOpenRequest(
                conversationIdentity = taskConversationLaunchIdentity(ChannelId(convId)),
                anchorMessageId = messageId.takeIf { it.isNotBlank() },
            )
        )
    }
    val isThread =
        conversation.type.equals("thread", ignoreCase = true) ||
            (conversation.parent != null && !conversation.parentMessageId.isNullOrBlank())
    val sourceIdentity =
        if (isThread) {
            ConversationOpenSourceMessageIdentity(
                source = ConversationOpenSource.ActivityRow,
                messageId = messageId.takeIf { it.isNotBlank() },
                channelId = convId.takeIf { it.isNotEmpty() },
                threadChannelId = convId.takeIf { it.isNotEmpty() },
                parentChannelId = conversation.parent?.id?.takeIf { it.isNotBlank() },
                parentMessageId = conversation.parentMessageId?.takeIf { it.isNotBlank() },
                conversationKind = ConversationKind.Thread,
                parentConversationKind = conversation.parent?.type?.let(::toAnswerConversationKind),
            )
        } else {
            ConversationOpenSourceMessageIdentity(
                source = ConversationOpenSource.ActivityRow,
                messageId = messageId.takeIf { it.isNotBlank() },
                channelId = convId.takeIf { it.isNotEmpty() },
                conversationKind = toAnswerConversationKind(conversation.type),
            )
        }
    return resolveOpenRequest(
        serverId = serverId,
        sourceMessageIdentity = sourceIdentity,
        conversationDirectory = conversationDirectory,
        diagnosticsLogger = diagnosticsLogger,
    )
}

@Composable
internal fun toAnswerHeaderSubtitle(count: Int): String {
    val locale = rememberActionCardDisplayLocale()
    val res =
        when (toAnswerPluralCategory(count, locale)) {
            ToAnswerPluralCategory.One -> Res.string.activity_to_answer_subtitle_one
            ToAnswerPluralCategory.Few -> Res.string.activity_to_answer_subtitle_few
            ToAnswerPluralCategory.Many -> Res.string.activity_to_answer_subtitle_many
            ToAnswerPluralCategory.Other -> Res.string.activity_to_answer_subtitle_other
        }
    return stringResource(res, count)
}

@Composable
private fun toAnswerExpiresInText(days: Int, displayLocale: String): String {
    val res =
        when (toAnswerPluralCategory(days, displayLocale)) {
            ToAnswerPluralCategory.One -> Res.string.activity_to_answer_expires_in_one
            ToAnswerPluralCategory.Few -> Res.string.activity_to_answer_expires_in_few
            ToAnswerPluralCategory.Many -> Res.string.activity_to_answer_expires_in_many
            ToAnswerPluralCategory.Other -> Res.string.activity_to_answer_expires_in_other
        }
    return stringResource(res, days)
}

@Composable
private fun toAnswerEndedLine(ended: ToAnswerEnded): String {
    val byName = ended.byName?.trim()?.takeIf { it.isNotEmpty() }
    return when (ended.state) {
        "executed" ->
            if (byName != null) {
                stringResource(Res.string.activity_to_answer_ended_answered_by, byName)
            } else {
                stringResource(Res.string.activity_to_answer_ended_answered)
            }
        "withdrawn" -> stringResource(Res.string.activity_to_answer_ended_withdrawn)
        "expired" -> stringResource(Res.string.activity_to_answer_ended_expired)
        "closed" ->
            if (byName != null) {
                stringResource(Res.string.activity_to_answer_ended_closed_by, byName)
            } else {
                stringResource(Res.string.activity_to_answer_ended_closed)
            }
        else -> stringResource(Res.string.activity_to_answer_ended_other)
    }
}

@Composable
private fun toAnswerWhereLabel(conversation: ToAnswerConversation): String {
    val dmLabel = stringResource(Res.string.activity_to_answer_where_dm)
    fun channelName(name: String, type: String): String =
        if (type.equals("dm", ignoreCase = true)) dmLabel else "#$name"
    return when (conversation.type.lowercase()) {
        "dm" -> dmLabel
        "thread" ->
            conversation.parent?.let { parent ->
                stringResource(Res.string.activity_to_answer_where_thread, channelName(parent.name, parent.type))
            } ?: stringResource(Res.string.activity_to_answer_where_thread_alone)
        "task" ->
            conversation.parent?.let { parent ->
                stringResource(Res.string.activity_to_answer_where_task, channelName(parent.name, parent.type))
            } ?: stringResource(Res.string.activity_to_answer_where_task_alone)
        else -> conversation.name
    }
}

@Composable
private fun ToAnswerWhereIcon(conversationType: String) {
    when (conversationType.lowercase()) {
        "dm" -> RaftConversationKindIcon(RaftConversationKindGlyph.DirectMessage, size = 12.dp)
        "thread" -> RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = 12.dp)
        "task" -> RaftContentLucideIcon(RaftLucideIconName.ListTodo, size = 12.dp)
        "private" -> RaftContentLucideIcon(RaftLucideIconName.Lock, size = 12.dp)
        else -> RaftContentLucideIcon(RaftLucideIconName.Hash, size = 12.dp)
    }
}

private fun ToAnswerCardKind.iconName(): RaftLucideIconName =
    when (this) {
        ToAnswerCardKind.Question -> RaftLucideIconName.CircleQuestionMark
        ToAnswerCardKind.Confirm -> RaftLucideIconName.CircleCheckBig
        ToAnswerCardKind.Request -> RaftLucideIconName.KeyRound
    }

@Composable
private fun ToAnswerCardKind.localizedLabel(): String =
    when (this) {
        ToAnswerCardKind.Question -> stringResource(Res.string.activity_to_answer_kind_question)
        ToAnswerCardKind.Confirm -> stringResource(Res.string.activity_to_answer_kind_confirm)
        ToAnswerCardKind.Request -> stringResource(Res.string.activity_to_answer_kind_request)
    }

/**
 * The "To answer" list under Activity's toolbar (web `ToAnswerList`): open cards grouped by day,
 * or loading / error+retry / empty state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActivityToAnswerList(
    state: SharedToAnswerState,
    pullRefreshing: Boolean,
    timeContext: RaftTimeContext?,
    listState: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onOpenItem: (ToAnswerItem) -> Unit,
    onRetry: () -> Unit,
    onPullRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = activityRowLayout
    val edge = activityColumnMargin(RaftTheme.scale, RaftTheme.pointerInput)
    val initialLoading = state.items.isEmpty() && (state.status == ToAnswerStatus.Loading || state.status == ToAnswerStatus.Idle)
    if (initialLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RaftCenteredLoading()
        }
        return
    }
    if (state.items.isEmpty() && state.status == ToAnswerStatus.Failed) {
        RaftPullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = onPullRefresh,
            modifier = modifier.fillMaxSize(),
            enabled = !initialLoading,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .raftSafeAreaHorizontalPadding()
                        .padding(horizontal = edge, vertical = 12.dp),
            ) {
                RaftBanner(
                    description = stringResource(Res.string.inbox_error_load_activity),
                    status = RaftBannerStatus.Warning,
                    modifier = Modifier.testTag("to-answer-load-failed"),
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = onRetry,
                            size = RaftButtonSize.Md,
                            variant = RaftButtonVariant.Default,
                        )
                    },
                )
            }
        }
        return
    }
    if (state.items.isEmpty()) {
        RaftPullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = onPullRefresh,
            modifier = modifier.fillMaxSize(),
            enabled = !initialLoading,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = bottomContentPadding),
                contentAlignment = Alignment.Center,
            ) {
                RaftEmptyState(
                    title = stringResource(Res.string.activity_to_answer_empty_title),
                    description = stringResource(Res.string.activity_to_answer_empty_description),
                    icon = { RaftContentLucideIcon(ActivityToAnswerFilterIcon, size = RaftEmptyStateDefaults.IconSize) },
                    modifier = Modifier.testTag("to-answer-empty"),
                )
            }
        }
        return
    }
    val dayGroups = remember(state.items, timeContext) { groupItemsByActivityDay(state.items, timeContext) { it.createdAt } }
    val nowEpochMillis = remember(timeContext?.nowEpochMillis, state.items) {
        timeContext?.nowEpochMillis ?: Clock.System.now().toEpochMilliseconds()
    }
    val displayLocale = rememberActionCardDisplayLocale()
    val listBackground = RaftColor.SurfaceWhite
    RaftPullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = onPullRefresh,
        modifier = modifier.fillMaxSize(),
        enabled = !initialLoading,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("to-answer-list"),
            contentPadding = PaddingValues(top = layout.listTop, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(layout.rowGap),
        ) {
            dayGroups.forEachIndexed { groupIndex, group ->
                val label = group.label
                if (label != null) {
                    stickyHeader(key = "to_answer_${group.key}", contentType = "to_answer_day_heading") {
                        ActivityDayHeading(
                            label = label,
                            first = groupIndex == 0,
                            background = listBackground,
                            modifier = Modifier.testTag("inbox-day-heading"),
                            horizontalPadding = layout.contentEdge,
                        )
                    }
                }
                items(
                    items = group.rows,
                    key = { row -> "to_answer:${row.messageId}" },
                    contentType = { "to_answer_row" },
                ) { item ->
                    ActivityToAnswerRow(
                        item = item,
                        ended = state.ended[item.messageId],
                        timeContext = timeContext,
                        nowEpochMillis = nowEpochMillis,
                        displayLocale = displayLocale,
                        layout = layout,
                        onOpen = onOpenItem,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityToAnswerRow(
    item: ToAnswerItem,
    ended: ToAnswerEnded?,
    timeContext: RaftTimeContext?,
    nowEpochMillis: Long,
    displayLocale: String,
    layout: ActivityRowLayout = activityRowLayout,
    onOpen: (ToAnswerItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val rowAlpha by animateFloatAsState(
        targetValue = if (ended != null) 0.55f else 1f,
        animationSpec = tween(durationMillis = 700),
        label = "to_answer_row_alpha",
    )
    var pointerHovering by remember(item.messageId) { mutableStateOf(false) }
    val requesterName = item.requester?.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.activity_to_answer_requester_gone)
    val whereText = toAnswerWhereLabel(item.conversation)
    val kindLabel = item.kind.localizedLabel()
    val addresseeLabel =
        when (item.addressee) {
            ToAnswerAddressee.You -> stringResource(Res.string.activity_to_answer_asks_you)
            ToAnswerAddressee.Anyone -> stringResource(Res.string.activity_to_answer_anyone)
        }
    val moreQuestionsLabel =
        if (item.moreQuestions > 0) {
            stringResource(Res.string.activity_to_answer_more_questions, item.moreQuestions)
        } else {
            null
        }
    val daysLeft = remember(item.expiresAt, nowEpochMillis) { toAnswerDaysLeft(item.expiresAt, nowEpochMillis) }
    val expiresLabel =
        if (item.kind == ToAnswerCardKind.Request && daysLeft != null && daysLeft in 1..30) {
            toAnswerExpiresInText(daysLeft, displayLocale)
        } else {
            null
        }
    val endedText = ended?.let { toAnswerEndedLine(it) }
    val requestTitle = item.requestTitle
    val bodyText =
        if (endedText != null) {
            endedText
        } else if (item.kind == ToAnswerCardKind.Request && requestTitle != null) {
            val localizedTitle = renderActionCardMsgText(requestTitle, displayLocale)
            val note = item.text?.takeIf { it.isNotBlank() }
            if (note != null) {
                stringResource(Res.string.activity_to_answer_request_with_note, localizedTitle, note)
            } else {
                localizedTitle
            }
        } else {
            item.text.orEmpty()
        }
    val rowTime = remember(item.createdAt, timeContext) { activityRowTime(item.createdAt, timeContext) }
    val timeWords = rowTime?.words()
    val accessibleName =
        listOfNotNull(
            requesterName,
            whereText.takeIf { it.isNotBlank() },
            bodyText.takeIf { it.isNotBlank() },
            kindLabel,
            addresseeLabel,
            moreQuestionsLabel,
            expiresLabel,
            timeWords,
        ).joinToString(", ")

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(rowAlpha)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = layout.shapeInset)
                .raftPointerHoverPosition { position -> pointerHovering = position != null }
                .raftClickable(
                    onClick = { onOpen(item) },
                    shape = layout.shape,
                    tier = RaftHoverTier.Cell,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibleName
                }
                .testTag("to-answer-row"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = layout.minHeight)
                    .padding(horizontal = layout.contentPadX, vertical = layout.padY),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = layout.leadingTopPadding)) {
                RaftInboxItemLeading {
                    ActivityMemberFace(
                        identity =
                            ActivitySenderAvatarIdentity(
                                senderKind =
                                    when (item.requester?.type) {
                                        ToAnswerRequesterType.Agent -> MemberKind.Agent
                                        ToAnswerRequesterType.User, null -> MemberKind.Human
                                    },
                                avatarWireValue = null,
                                label = "",
                                senderId = item.requester?.id,
                            ),
                        avatarSize = ToAnswerLeadingAvatar,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.testTag("to-answer-where"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundHint) {
                        ToAnswerWhereIcon(item.conversation.type)
                    }
                    RaftText(
                        text = whereText,
                        style = typography.caption,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        text = requesterName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rowTime != null && rowTime.shown(RaftTheme.pointerInput, pointerHovering = pointerHovering)) {
                        Spacer(Modifier.width(8.dp))
                        RaftText(
                            text = rowTime.shownLabel(),
                            style = typography.caption,
                            color = colors.foregroundHint,
                            maxLines = 1,
                        )
                    }
                }
                if (endedText != null) {
                    Row(
                        modifier = Modifier.testTag("to-answer-body"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundMuted) {
                            RaftContentLucideIcon(RaftLucideIconName.Check, size = 14.dp)
                        }
                        RaftText(
                            text = endedText,
                            modifier = Modifier.testTag("to-answer-ended"),
                            style = typography.callout,
                            color = colors.foregroundMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (bodyText.isNotEmpty()) {
                    RaftText(
                        text = bodyText,
                        modifier = Modifier.testTag("to-answer-body"),
                        style = typography.callout,
                        color = colors.foregroundMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RaftBadge(
                        text = kindLabel,
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Default,
                        icon = { RaftContentLucideIcon(item.kind.iconName(), size = 14.dp) },
                    )
                    if (item.addressee == ToAnswerAddressee.You) {
                        ToAnswerYouBadge(
                            text = addresseeLabel,
                            modifier = Modifier.testTag("to-answer-you"),
                        )
                    } else {
                        RaftBadge(
                            text = addresseeLabel,
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Muted,
                            icon = { RaftContentLucideIcon(RaftLucideIconName.Users, size = 14.dp) },
                            modifier = Modifier.testTag("to-answer-anyone"),
                        )
                    }
                    if (moreQuestionsLabel != null) {
                        RaftBadge(
                            text = moreQuestionsLabel,
                            appearance = RaftBadgeAppearance.Outline,
                        )
                    }
                    if (expiresLabel != null) {
                        Box(
                            modifier = Modifier.heightIn(min = 20.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            RaftText(
                                text = expiresLabel,
                                style = typography.caption,
                                color = colors.foregroundHint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Solid yellow "Asks you" sticker badge matching raft-ui `<Badge variant="you">` using public design
 * tokens without extending `compose/designsystem/`.
 */
@Composable
private fun ToAnswerYouBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    val outerRingColor = colors.stickerEdge
    Row(
        modifier =
            modifier
                .height(20.dp)
                .drawBehind {
                    if (outerRingColor.alpha > 0f) {
                        val ringPx = RaftStroke.Control.toPx()
                        val radiusPx = RaftRadius.Sticker.toPx()
                        drawRoundRect(
                            color = outerRingColor,
                            topLeft = Offset(-ringPx / 2f, -ringPx / 2f),
                            size = Size(size.width + ringPx, size.height + ringPx),
                            cornerRadius = CornerRadius(radiusPx + ringPx / 2f),
                            style = Stroke(width = ringPx),
                        )
                    }
                }
                .background(RaftColor.You, shape)
                .border(RaftStroke.Control, RaftColor.StickerInk, shape)
                .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides RaftColor.StickerInk) {
            RaftContentLucideIcon(RaftLucideIconName.AtSign, size = 13.dp)
        }
        RaftText(
            text = text,
            style = typography.caption.copy(fontWeight = FontWeight.Bold),
            color = RaftColor.StickerInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
