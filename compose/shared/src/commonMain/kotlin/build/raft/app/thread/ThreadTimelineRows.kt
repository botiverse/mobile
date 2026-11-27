package build.raft.app.thread

import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.localized
import build.raft.app.localizedLabel
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.iconName
import build.raft.app.thread.localizedLabel
import build.raft.app.markdown.markdownToPreviewText
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.model.delegatedGroupKey
import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.data.WORKER_ENDED_RECORD_SUBTYPE
import build.raft.app.data.channelAnnouncementNoticeOf
import build.raft.app.fill
import build.raft.app.get
import build.raft.app.iconName
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.origin
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.designsystem.components.RaftHoverTier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.EmptyRaftAgentStatusResolver
import build.raft.app.EmptyRaftMemberAvatarResolver
import build.raft.app.EmptyRaftMemberModelLabelResolver
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftAgentStatusResolver
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftColor
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMemberAvatarResolver
import build.raft.app.RaftMemberModelLabelResolver
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftProfileDescriptionText
import build.raft.app.RaftRelativeTimestampText
import build.raft.app.RaftTimestampDisplayGroup
import build.raft.app.RaftTimestampDisplayMode
import build.raft.app.RaftType
import build.raft.app.channelAnnouncementNoticeLine
import kotlinx.coroutines.delay
import build.raft.designsystem.components.raftClickable

private val ThreadSystemMessageTimeColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.4f)
private val ThreadSystemMessageBodyColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.5f)

@Composable
internal fun ThreadSystemMessageRow(
    message: RaftThreadMessageItem,
    timestampValue: String,
    /** The conversation's messages, oldest first: a hand-off card reads who held the task before and whether it goes back. */
    timeline: List<RaftThreadMessageItem> = emptyList(),
    /** Precomputed hand-off timeline entry for [message] (avoids rescanning [timeline] per row). */
    handoffTimeline: build.raft.app.data.TaskHandoffTimeline? = null,
    /**
     * The conversation list's link opener and server: with them, the assistant DM's receipt and
     * WeChat notice are cards (ThreadAssistantDmCards.kt); without (a share image, a thread's
     * origin) they stay one-line notices.
     */
    serverSlug: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    /** The user opened the line's details (the thread list's expand reveal, ThreadExpandReveal.kt). */
    onExpand: (() -> Unit)? = null,
    /** A hand-off card's reason draws refs, @mentions and links with these; null keeps it plain text. */
    handoffReasonMarkdown: ThreadTaskHandoffReasonMarkdown? = null,
    /** The reader's message body size: a hand-off's reason reads at body size (botiverse/slock #15631). */
    messageBodyTextStyle: TextStyle = build.raft.designsystem.theme.RaftTheme.typography.body,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    onOpenMember: ((MemberDetailIdentity) -> Unit)? = null,
    isDm: Boolean = false,
) {
    // The assistant DM's delegated-send receipt and WeChat connected notice are cards (web MessageItem).
    if (onOpenUrl != null && ThreadAssistantDmCardRow(message, serverSlug, onOpenUrl)) return
    // A task handed to someone else is its actor's message with a card, not the one-line notice (web MessageItem, #15631).
    if (
        ThreadTaskHandoffRow(
            message = message,
            timeline = timeline,
            reasonMarkdown = handoffReasonMarkdown,
            handoffTimeline = handoffTimeline,
            messageBodyTextStyle = messageBodyTextStyle,
            memberAvatarResolver = memberAvatarResolver,
            memberModelLabelResolver = memberModelLabelResolver,
            onOpenMember = onOpenMember,
        )
    ) return
    // Worker v1 ended system record row (web MessageItem -> WorkerEndedRecordRow).
    if (LocalThreadWorkerV1Enabled.current && message.systemSubtype == WORKER_ENDED_RECORD_SUBTYPE) {
        ThreadWorkerEndedRecordRow(
            message = message,
            timestampValue = timestampValue,
            onExpand = onExpand,
        )
        return
    }
    var expanded by remember(message.id) { mutableStateOf(false) }
    val timestampLayout = remember { threadSystemMessageTimestampLayoutSpec() }
    // A channel or DM announcement's set, replace or remove line, in the reader's language (its
    // text is the English record; web MessageItem `channelAnnouncementNoticeLine`).
    val announcementNotice = remember(message.metadata) { channelAnnouncementNoticeOf(message.metadata) }
    val announcementLine = announcementNotice?.let { channelAnnouncementNoticeLine(it, isDm = isDm) }
    val systemBody =
        remember(message.body, message.metadata, message.systemEventType, expanded, announcementLine) {
            when {
                announcementLine != null -> announcementLine
                expanded -> message.expandedSystemContent()
                else -> message.systemContentSummary()
            }
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(RaftColor.SurfaceWhite)
                .raftClickable(
                    onClick = {
                        expanded = !expanded
                        if (expanded) onExpand?.invoke()
                    },
                    tier = RaftHoverTier.Subtle
                )
                .raftSafeAreaHorizontalPadding()
                // raft-ui SystemMessage `pt-timeline-lead pb-timeline-row`: an event line starts a
                // turn, 16 from the text on either side (spacing.md#timeline-rhythm).
                .padding(
                    start = RaftAppSpacing.Md,
                    end = RaftAppSpacing.Md,
                    top = ThreadMessageTimelineTurn + ThreadMessageTimelineRowInset,
                    bottom = ThreadMessageTimelineRowInset
                ),
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        RaftRelativeTimestampText(
            value = timestampValue,
            displayGroup = RaftTimestampDisplayGroup.Conversation,
            style = RaftType.Caption.copy(color = ThreadSystemMessageTimeColor, fontFamily = RaftMonoFamily),
            color = ThreadSystemMessageTimeColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                if (timestampLayout.reserveFullMetadataGutter) {
                    Modifier.width(timestampLayout.maxWidthDp.dp)
                } else {
                    Modifier.widthIn(max = timestampLayout.maxWidthDp.dp).wrapContentWidth()
                }
        )
        Text(
            text = systemBody,
            style = RaftType.Caption.copy(color = ThreadSystemMessageBodyColor),
            color = ThreadSystemMessageBodyColor,
            textAlign = TextAlign.Center,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            // Let short timestamp/body groups stay centered; long bodies still use the remaining width.
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
internal fun ThreadNewerMessagesBoundaryRow(
    newerMessagesStatus: String,
    onRetry: () -> Unit,
    suppressLoadingIndicator: Boolean = false,
) {
    val spec = threadNewerMessagesActionSpec(newerMessagesStatus)
    if (spec.event == "retryLoadNewer") {
        ThreadInlineActionRow(
            label = spec.label.localized(),
            leadingIcon = spec.leadingIcon,
            onClick = onRetry
        )
        return
    }
    if (newerMessagesStatus == "loading" && !suppressLoadingIndicator) {
        ThreadHistoryStatusRow(
            spec = ThreadHistoryStatusSpec(label = spec.label, loading = true),
            loadingRevealDelayMs = 0L,
        )
        return
    }
    // Keep the LazyColumn slot when idle (or suppressed while the older boundary owns the single
    // visible loading indicator, #proj-mobile:#390) at 0dp height so index arithmetic stays
    // invariant and anchored opens never show a 36dp blank white band below the last message (#proj-mobile:#386).
    Box(modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ThreadInlineActionRow(
    label: String,
    leadingIcon: RaftLucideIconName? = null,
    onClick: () -> Unit
) {
    RaftButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding(),
        variant = RaftButtonVariant.Default,
        size = RaftButtonSize.Md,
        leadingIcon = leadingIcon?.let { icon -> { RaftSlotIcon(icon) } }
    )
}

@Composable
internal fun ThreadHistoryStatusRow(
    spec: ThreadHistoryStatusSpec,
    loadingRevealDelayMs: Long = ThreadStandInLoadingRevealDelayMs,
    spinnerStillStep: Int? = null
) {
    // Collapse to 0dp while waiting out any non-zero delay so a hidden loading row never reserves
    // invisible blank height (Hands a7842ad1 / #proj-mobile:#386).
    var loadingRevealed by remember(spec.loading, loadingRevealDelayMs) {
        mutableStateOf(!spec.loading || loadingRevealDelayMs <= 0L)
    }
    if (!loadingRevealed) {
        LaunchedEffect(spec.loading, loadingRevealDelayMs) {
            delay(loadingRevealDelayMs)
            loadingRevealed = true
        }
        Box(modifier = Modifier.fillMaxWidth())
        return
    }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Md, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (spec.loading) {
                RaftSpinner(
                    size = RaftSpinnerSize.Md,
                    color = RaftColor.TextMutedGray,
                    contentDescription = spec.label.localized(),
                    stillStep = spinnerStillStep
                )
                Spacer(Modifier.width(8.dp))
            }
            spec.leadingIcon?.let { iconName ->
                RaftLucideIcon(
                    iconName,
                    modifier = Modifier.size(14.dp),
                    color = "#FF6B7280"
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = spec.label.localized(),
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
        }
        val secondaryText =
            when (val count = spec.secondaryReplyCount) {
                null -> spec.secondaryLabel
                1 -> stringResource(Res.string.thread_inline_reply_count_one)
                else -> stringResource(Res.string.thread_inline_reply_count_many, count)
            }
        if (!secondaryText.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = secondaryText,
                style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun ThreadDateDivider(label: String) {
    // raft-ui MessageListDateDivider: the rule sits a section (24) from the text on both sides
    // (spacing.md#timeline-rhythm), taking back the row above's inset (4) and the row below's
    // lead (12): pt = 24 - 4 - line/2, pb = 24 - 12 - line/2 on its 16dp line.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(
                    start = RaftAppSpacing.Md,
                    end = RaftAppSpacing.Md,
                    top = ThreadDateDividerPaddingTop,
                    bottom = ThreadDateDividerPaddingBottom
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f).height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
        Text(
            text = label,
            style = threadDateDividerTextStyle(),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(ThreadDateDividerLineHeight)
        )
        Box(Modifier.weight(1f).height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
    }
}

/** raft `timeline-gap-section`: a boundary's distance from the text on each side. */
private val ThreadTimelineSection = 24.dp
private val ThreadDateDividerLineHeight = 16.dp
internal val ThreadDateDividerPaddingTop =
    ThreadTimelineSection - ThreadMessageTimelineRowInset - ThreadDateDividerLineHeight / 2
internal val ThreadDateDividerPaddingBottom =
    ThreadTimelineSection - (ThreadMessageTimelineTurn + ThreadMessageTimelineRowInset) - ThreadDateDividerLineHeight / 2

/** raft-ui `MessageListDateDivider`: `font-mono text-mono-meta` (12/16 regular), Caption's size in Space Mono Regular. */
internal fun threadDateDividerTextStyle(): TextStyle =
    RaftType.Caption.copy(
        fontFamily = RaftMonoFamily,
        fontWeight = FontWeight.Normal,
        color = RaftColor.TextMutedGray,
        letterSpacing = 0.sp
    )

// task #800 rendering-evidence seam: fixture-only geometry tints so the
// connected pixel oracle measures EXACTLY the body / status / cap geometry
// instead of whole-card extremes. Default null = production provides no tints,
// paints nothing, and (via the helper) adds zero extra layout nodes.
internal data class ThreadMessageGeometryTints(
    val bodyTint: Color,
    val statusTint: Color,
    val capTint: Color,
    val retryTint: Color
)

internal val LocalThreadMessageGeometryTints =
    compositionLocalOf<ThreadMessageGeometryTints?> { null }

/** The message a pasted permalink points to, and whether its channel is archived (web QuoteCardContent). */
internal data class ThreadQuotedPermalinkMessage(
    val item: RaftThreadMessageItem,
    val channelArchived: Boolean = false
)

internal typealias ThreadQuotedPermalinkLoader = suspend (ParsedRaftPermalink) -> ThreadQuotedPermalinkMessage?

/** Tri-state synchronous cache lookup for quoted permalinks (zero Frame 1 loading flash). */
internal sealed interface ThreadQuotedPermalinkCacheLookup {
    data class Hit(val message: ThreadQuotedPermalinkMessage) : ThreadQuotedPermalinkCacheLookup
    data object KnownUnavailable : ThreadQuotedPermalinkCacheLookup
    data object Miss : ThreadQuotedPermalinkCacheLookup
}

/** Synchronous cache lookup for already-loaded or known-unavailable quoted permalinks. */
internal typealias ThreadQuotedPermalinkLookup = (ParsedRaftPermalink) -> ThreadQuotedPermalinkCacheLookup

/**
 * The loader behind the quoted-permalink card. SharedThreadRouteContent is at the JVM argument
 * limit, so it reaches [ThreadMessageCard] through this local instead of a parameter chain (the
 * chain had silently ended in `null` defaults, so no card ever loaded).
 */
internal val LocalThreadQuotedPermalinkLoader = compositionLocalOf<ThreadQuotedPermalinkLoader?> { null }

/** Synchronous lookup paired with [LocalThreadQuotedPermalinkLoader] for instant cached rendering. */
internal val LocalThreadQuotedPermalinkLookup = compositionLocalOf<ThreadQuotedPermalinkLookup?> { null }

internal fun ThreadQuotedPermalinkMessage.toQuotedPermalinkUiState(): ThreadQuotedPermalinkUiState =
    if (item.isRetracted) ThreadQuotedPermalinkUiState.Retracted else ThreadQuotedPermalinkUiState.Loaded(this)

internal sealed interface ThreadQuotedPermalinkUiState {
    data object Loading : ThreadQuotedPermalinkUiState
    data class Loaded(val message: ThreadQuotedPermalinkMessage) : ThreadQuotedPermalinkUiState
    data object Unavailable : ThreadQuotedPermalinkUiState
    /** RFC 077 (web QuoteCardContent `unavailable`): the quoted message was retracted. */
    data object Retracted : ThreadQuotedPermalinkUiState
}

/** The local day a thread date divider names; rendered from string resources by [localizedLabel]. */
internal data class ThreadDateDividerDay(
    val epochDay: Long,
    val todayEpochDay: Long?
)

internal fun threadDateDividerDay(
    previous: RaftThreadMessageItem?,
    next: RaftThreadMessageItem,
    timeContext: RaftTimeContext
): ThreadDateDividerDay? {
    val localOffsetMinutes = timeContext.localOffsetMinutes
    val nextEpochDay = next.threadMessageLocalEpochDayOrNull(localOffsetMinutes) ?: return null
    val previousEpochDay = previous?.threadMessageLocalEpochDayOrNull(localOffsetMinutes)
    if (previousEpochDay == nextEpochDay) return null
    val todayEpochDay =
        timeContext.todayEpochDay
            ?: timeContext.nowEpochMillis?.let { threadLocalEpochDay(it, localOffsetMinutes) }
    return ThreadDateDividerDay(epochDay = nextEpochDay, todayEpochDay = todayEpochDay)
}

/** Canonical (English) divider label; the UI renders [ThreadDateDividerDay.localizedLabel]. */
internal fun threadDateDividerLabel(
    previous: RaftThreadMessageItem?,
    next: RaftThreadMessageItem,
    timeContext: RaftTimeContext
): String? =
    threadDateDividerDay(previous, next, timeContext)?.let { day ->
        threadDateDividerLabel(day.epochDay, day.todayEpochDay)
    }

@Composable
internal fun ThreadDateDividerDay.localizedLabel(): String =
    when {
        todayEpochDay != null && epochDay == todayEpochDay -> stringResource(Res.string.thread_date_divider_today)
        todayEpochDay != null && epochDay == todayEpochDay - 1L -> stringResource(Res.string.thread_date_divider_yesterday)
        else -> {
            val date = threadCivilDateFromEpochDay(epochDay)
            stringResource(
                Res.string.thread_date_divider_date,
                stringResource(ThreadWeekdayNameResources[threadWeekdayIndex(epochDay)]),
                stringResource(ThreadMonthNameResources[date.month - 1]),
                date.day
            )
        }
    }

internal fun RaftThreadMessageItem.isCompactContinuationBefore(
    next: RaftThreadMessageItem,
    localOffsetMinutes: Int
): Boolean {
    if (isSystemMessage() || next.isSystemMessage()) return false
    val identityKey = authorIdentityKey()
    if (identityKey.isBlank() || identityKey != next.authorIdentityKey()) return false
    // Web messageGrouping `isSameSender`: a message the author's assistant sent for them never
    // continues their own run, and runs from different assistants stay apart.
    if (delegatedBy.delegatedGroupKey() != next.delegatedBy.delegatedGroupKey()) return false

    val currentEpochMillis = parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    val nextEpochMillis = next.parseThreadMessageEpochMillisOrNull(localOffsetMinutes)
    if (currentEpochMillis == null || nextEpochMillis == null) return false

    val elapsedMillis = nextEpochMillis - currentEpochMillis
    if (elapsedMillis !in 0..ThreadCompactContinuationWindowMillis) return false

    return threadLocalEpochDay(currentEpochMillis, localOffsetMinutes) ==
        threadLocalEpochDay(nextEpochMillis, localOffsetMinutes)
}

private fun RaftThreadMessageItem.parseThreadMessageEpochMillisOrNull(localOffsetMinutes: Int): Long? =
    parseRaftTimestampEpochMillisOrNull(timestampLabel, localOffsetMinutes)

private fun RaftThreadMessageItem.threadMessageLocalEpochDayOrNull(localOffsetMinutes: Int): Long? =
    parseThreadMessageEpochMillisOrNull(localOffsetMinutes)?.let { threadLocalEpochDay(it, localOffsetMinutes) }

internal fun RaftThreadMessageItem.authorIdentityKey(): String =
    authorId.takeIf { it.isNotBlank() }
        ?: authorHandle.takeIf { it.isNotBlank() }
        ?: authorName.takeIf { it.isNotBlank() }
        ?: ""

internal fun threadLocalEpochDay(epochMillis: Long, localOffsetMinutes: Int): Long =
    threadFloorDiv(epochMillis + localOffsetMinutes.toLong() * ThreadMillisPerMinute, ThreadMillisPerDay)

private fun threadFloorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && ((remainder < 0) != (divisor < 0))) quotient - 1 else quotient
}

private fun threadFloorMod(value: Long, divisor: Long): Long =
    value - threadFloorDiv(value, divisor) * divisor

private fun threadDateDividerLabel(epochDay: Long, todayEpochDay: Long?): String =
    when {
        todayEpochDay != null && epochDay == todayEpochDay -> "TODAY"
        todayEpochDay != null && epochDay == todayEpochDay - 1L -> "YESTERDAY"
        else -> {
            val date = threadCivilDateFromEpochDay(epochDay)
            "${ThreadWeekdayNames[threadWeekdayIndex(epochDay)]}, ${ThreadMonthNames[date.month - 1]} ${date.day}"
        }
    }

private data class ThreadCivilDate(
    val year: Int,
    val month: Int,
    val day: Int
)

private fun threadCivilDateFromEpochDay(epochDay: Long): ThreadCivilDate {
    val zeroDay = epochDay + 719_468L
    val era = threadFloorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    val adjustedYear = year + if (month <= 2L) 1L else 0L
    return ThreadCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

private fun threadWeekdayIndex(epochDay: Long): Int =
    threadFloorMod(epochDay + 4L, 7L).toInt()

private val ThreadWeekdayNames =
    listOf("SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")

private val ThreadMonthNames =
    listOf("JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER")

private val ThreadWeekdayNameResources =
    listOf(
        Res.string.thread_date_divider_weekday_sunday,
        Res.string.thread_date_divider_weekday_monday,
        Res.string.thread_date_divider_weekday_tuesday,
        Res.string.thread_date_divider_weekday_wednesday,
        Res.string.thread_date_divider_weekday_thursday,
        Res.string.thread_date_divider_weekday_friday,
        Res.string.thread_date_divider_weekday_saturday
    )

private val ThreadMonthNameResources =
    listOf(
        Res.string.thread_date_divider_month_january,
        Res.string.thread_date_divider_month_february,
        Res.string.thread_date_divider_month_march,
        Res.string.thread_date_divider_month_april,
        Res.string.thread_date_divider_month_may,
        Res.string.thread_date_divider_month_june,
        Res.string.thread_date_divider_month_july,
        Res.string.thread_date_divider_month_august,
        Res.string.thread_date_divider_month_september,
        Res.string.thread_date_divider_month_october,
        Res.string.thread_date_divider_month_november,
        Res.string.thread_date_divider_month_december
    )

private const val ThreadMillisPerMinute = 60_000L
private const val ThreadMillisPerDay = 86_400_000L
private const val ThreadCompactContinuationWindowMillis = 30L * ThreadMillisPerMinute

@Composable
internal fun ThreadOriginCard(
    modifier: Modifier = Modifier,
    renderConfig: ThreadTimelineRenderConfig,
    messageBodyTextStyle: TextStyle,
    timelineSlice: ThreadTimelineSlice,
    focusedMessageId: String? = null,
    searchMatchMessageId: String? = null,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection(),
    highlightedMentions: Set<String>,
    pendingActionCardIds: Set<String> = emptySet(),
    attachmentOpenStates: Map<String, ThreadAttachmentOpenUiState> = emptyMap(),
    currentServerSlug: String? = null,
    contentExtrasVisible: Boolean = true,
    isMarkdownTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultThreadMarkdownTagIsValid,
    markdownTagContextSignature: String = "",
    resolveMarkdownTagLabel: ((ThreadMarkdownTag) -> String)? = null,
    activeMarkdownTagKey: String? = null,
    diagnosticsLogger: (String) -> Unit = {},
    onThreadEvent: ThreadEventHandler,
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onExternalAuthor: (RaftThreadMessageItem) -> Unit = {},
    onShowActions: (RaftThreadMessageItem, RaftPopoverAnchor) -> Unit,
    onLongPressMenuOpened: (RaftThreadMessageItem) -> Unit = {},
    writeActionsEnabled: Boolean = false,
    onShowQuickReactions: (RaftThreadMessageItem, RaftPopoverAnchor) -> Unit = { _, _ -> },
    onOpenTaskStatusMenu: (RaftThreadMessageItem, RaftPopoverAnchor) -> Unit,
    showReactionReactors: Boolean = false,
    onShowAuthorDescription: ((String, RaftPopoverAnchor) -> Unit)? = null,
    onMentionAuthor: (RaftThreadMessageItem) -> Unit,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    memberModelLabelResolver: RaftMemberModelLabelResolver = EmptyRaftMemberModelLabelResolver,
    onOpenMarkdownTag: (ThreadMarkdownTag, String) -> Unit,
    onOpenMarkdownUrl: (String) -> Unit,
    shareSelectionActive: Boolean = false,
    selectedShareMessageIds: Set<String> = emptySet(),
    onToggleShareSelection: (String) -> Unit = {},
    longMessageCollapse: RaftLongMessageCollapseController? = null,
    onToggleLongMessage: (String) -> Unit = {},
    /** web MessageItem `data-popup-open`: the parent's menu or quick reactions are open. */
    popupOpen: Boolean = false,
    /** The parent's hover toolbar (web MessageHoverToolbar, no Reply in Thread in a thread). */
    hoverToolbar: ThreadMessageHoverToolbarActions? = null
) {
    val userFacingErrorLabels = threadUserFacingErrorLabels()
    Column(
        modifier = modifier.fillMaxWidth()
            .background(RaftColor.SurfaceWhite)
    ) {
        when (timelineSlice.originStatus) {
            "loading" -> {
                Text(
                    text = stringResource(Res.string.thread_state_refreshing_original),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    modifier = Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm)
                )
            }
            "missing" -> {
                Column(
                    modifier = Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    Text(
                        // The launch fallback preview is the root message's Markdown.
                        text = renderConfig.originPreview?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.thread_state_original_unavailable),
                        style = messageBodyTextStyle,
                        color = RaftColor.TextBlack
                    )
                    renderConfig.originErrorCode?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = stringResource(
                                Res.string.thread_error_refresh_original,
                                userFacingErrorLabels.forErrorCode(it) ?: stringResource(Res.string.common_error)
                            ),
                            style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray
                        )
                    }
                }
            }
            else -> {
                timelineSlice.originMessage?.let { origin ->
                    val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(origin)
                    // Task v2 (web ThreadPanel `parentSourceTasks`): the parent's tasks list under it here too.
                    val originSourceTasks = linkedTasksProjection.sourceTasksFor(origin)
                    val displayOrigin =
                        origin.withLinkedTaskProjection(linkedTasksProjection)
                            .let { if (originSourceTasks.isEmpty()) it else it.withoutLinkedTask() }
                    val linkedTaskAssigneeName = linkedTask?.threadLinkedTaskAssigneeDisplayName()
                    val originActionCard = if (contentExtrasVisible) origin.threadActionCard() else null
                    if (displayOrigin.isSystemMessage() && originActionCard == null) {
                        ThreadSystemMessageRow(
                            message = displayOrigin,
                            timestampValue = origin.timestampLabel,
                            messageBodyTextStyle = messageBodyTextStyle,
                            memberAvatarResolver = memberAvatarResolver,
                            memberModelLabelResolver = memberModelLabelResolver,
                            onOpenMember = onOpenMember,
                            isDm = timelineSlice.directMessageSurface,
                        )
                    } else {
                        // The root message belongs to the parent scope, not this thread's read receipts.
                        CompositionLocalProvider(LocalThreadPeerReadReceipts provides null) {
                            ThreadMessageCard(
                                message = displayOrigin,
                                messageBodyTextStyle = messageBodyTextStyle,
                                hasReplyDraft = false,
                                sourceTasks = originSourceTasks,
                                onOpenSourceTask = { onThreadEvent("openSourceTask", it, null, null) },
                                actionCard = originActionCard,
                                highlightedMentions = highlightedMentions,
                                agentStatusResolver = agentStatusResolver,
                                memberAvatarResolver = memberAvatarResolver,
                                memberModelLabelResolver = memberModelLabelResolver,
                                shareSelectionActive = shareSelectionActive,
                                selectedForShare = origin.id in selectedShareMessageIds,
                                onToggleShareSelection = { onToggleShareSelection(origin.id) },
                                showReplyAction = false,
                                resolvedTaskAssigneeName =
                                    linkedTaskAssigneeName
                                        ?: renderConfig.resolveTaskAssigneeDisplayName(
                                            value = displayOrigin.taskAssigneeName,
                                            id = displayOrigin.taskAssigneeId
                                        ),
                                resolveMarkdownTagLabel = resolveMarkdownTagLabel,
                                markdownTagContextSignature = markdownTagContextSignature,
                                activeMarkdownTagKey = activeMarkdownTagKey,
                                taskOpenEnabled = timelineSlice.showTaskOpenActions && contentExtrasVisible,
                                taskStatusActionsEnabled = timelineSlice.showTaskStatusActions && contentExtrasVisible,
                                showSaveAction = timelineSlice.showMessageSaveAction,
                                isMarkdownTagValid = isMarkdownTagValid,
                                onShowActions = { anchor -> onShowActions(origin, anchor) },
                                onLongPressMenuOpened = { onLongPressMenuOpened(origin) },
                                popupOpen = popupOpen,
                                hoverToolbar = hoverToolbar,
                                // The thread's parent takes a double tap too: its reaction bar.
                                quickReactionsEnabled = threadMessageCanQuickReact(origin, timelineSlice, writeActionsEnabled),
                                onShowQuickReactions = { anchor -> onShowQuickReactions(origin, anchor) },
                                onOpenMember = onOpenMember,
                                onExternalAuthor = onExternalAuthor,
                                onOpenThread = { onThreadEvent("openMessageThread", it, null, null) },
                                onOpenTask = { onThreadEvent("openTask", it, null, null) },
                                onOpenTaskStatusMenu = { anchor -> onOpenTaskStatusMenu(origin, anchor) },
                                onToggleReaction = { emoji -> onThreadEvent("toggleReaction", origin.id, null, emoji) },
                                onToggleSave = { onThreadEvent("toggleMessageSaved", origin.id, null, null) },
                                showReactionReactors = showReactionReactors,
                                onShowAuthorDescription = onShowAuthorDescription,
                                showReactionAddAction = timelineSlice.showReactionActions && contentExtrasVisible,
                                onOpenCommentHost = { onThreadEvent("openCommentHost", origin.id, null, null) },
                                timestampLabel = origin.timestampLabel,
                                onOpenAttachment = { onThreadEvent("openAttachment", null, it, null) },
                                onDownloadAttachment = { onThreadEvent("downloadAttachment", null, it, null) },
                                attachmentOpenStates = if (contentExtrasVisible) attachmentOpenStates else emptyMap(),
                                pendingActionCardIds = if (contentExtrasVisible) pendingActionCardIds else emptySet(),
                                onCommitActionCard = { onThreadEvent("commitActionCard", it, null, null) },
                                onOpenActionCardChannel = { onThreadEvent("openActionCardChannel", it, null, null) },
                                currentServerSlug = currentServerSlug,
                                onOpenMarkdownTag = onOpenMarkdownTag,
                                onOpenMarkdownUrl = onOpenMarkdownUrl,
                                onMentionAuthor = onMentionAuthor,
                                richContentEnabled = contentExtrasVisible,
                                focused = focusedMessageId == origin.id,
                                searchMatch = searchMatchMessageId == origin.id,
                                longMessageCollapse = longMessageCollapse,
                                onToggleLongMessage = { onToggleLongMessage(origin.id) }
                            )
                        }
                    }
                } ?: renderConfig.originPreview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        modifier = Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(horizontal = RaftAppSpacing.Md, vertical = RaftAppSpacing.Sm)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderHairline))
    }
}

// React parity: MessageItem's senderSubtitle — 12px mono at 40% black,
// truncating flexibly between the name/badge and the timestamp.
// Tapping truncated description opens Brutal Anchored Popover to display full description.
@Composable
internal fun ThreadMessageAuthorSubtitle(
    description: String,
    modifier: Modifier = Modifier,
    rootAnchor: RaftLiveRootAnchorRef? = null,
    onClick: (() -> Unit)? = null,
    enableArtifactChips: Boolean = true,
    interactive: Boolean = true,
    onBeforeOpenArtifact: (() -> Unit)? = null,
) {
    val anchoredModifier =
        if (rootAnchor != null) {
            modifier.raftCaptureLiveRootAnchor(rootAnchor)
        } else {
            modifier
        }
    // raft-ui `MessageItemMeta`: text-caption (12/16, medium) on
    // foreground-hint in the sans family; only the time is mono.
    // ProfileDescription (#14969 / #15230): Artifact links draw as compact inline chips capped at
    // 16em that keep the single-line caption baseline without wrapping in a Box.
    RaftProfileDescriptionText(
        text = description,
        style =
            RaftType.Caption.copy(
                fontWeight = FontWeight.Medium,
                color = RaftColor.ForegroundHint
            ),
        color = RaftColor.ForegroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        compact = true,
        enabled = enableArtifactChips,
        interactive = interactive,
        source = "author_subtitle",
        onTextClick = onClick,
        onBeforeOpenArtifact = onBeforeOpenArtifact,
        enableHoverCards = false,
        modifier = anchoredModifier
    )
}

@Composable
internal fun ThreadMessageTimestampLabel(
    timestampLabel: String,
    modifier: Modifier = Modifier,
    onToggle: (RaftTimestampDisplayMode) -> Unit = {}
) {
    RaftRelativeTimestampText(
        value = timestampLabel,
        displayGroup = RaftTimestampDisplayGroup.Conversation,
        style =
            RaftType.Caption.copy(
                fontFamily = RaftMonoFamily,
                color = RaftColor.ForegroundHint
            ),
        color = RaftColor.ForegroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = modifier.then(threadMessageHeaderTimestampLayoutSpec().toTimestampModifier()),
        onToggle = onToggle
    )
}

private fun ThreadSystemMessageTimestampLayoutSpec.toTimestampModifier(): Modifier =
    if (reserveFullMetadataGutter) {
        Modifier.width(maxWidthDp.dp)
    } else {
        Modifier.widthIn(max = maxWidthDp.dp).wrapContentWidth()
    }
