package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import build.raft.app.data.ProcessSlockDatetimeFoundationOwner
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.ThreadId
import build.raft.app.model.floorDiv
import build.raft.app.model.formatSlockTimestampLabel
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

private val WeChatDmRowHeight = 64.dp
private val WeChatDmRowHorizontalPadding = 16.dp
private val WeChatDmAvatarSize = 40.dp
private val WeChatDmStatusDotSize = 10.dp
private val WeChatDmContentGap = 12.dp
private val WeChatDmBadgeHeight = 18.dp
private val WeChatDmMutedDotSize = 9.dp
private val WeChatDmDividerStartPadding = 68.dp // 16.dp + 40.dp + 12.dp
private val WeChatRedBadgeColor = Color(0xFFFA5151)
private val WeChatDividerColor = SlockColor.BorderBlack.copy(alpha = 0.1f)

/**
 * WeChat-style Direct Messages page for Slack bottom bar navigation mode (Task #332).
 *
 * Displays a list of DM conversations with 48dp avatars, status indicators, unread badges,
 * contact names, relative timestamps, and last message previews or drafts.
 */
@Composable
internal fun SharedDmsScreen(
    active: Boolean = true,
    directMessages: List<SlockHomeConversationItem>,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    totalUnreadCount: Int? = null,
    scrollToTopRequest: Int = 0,
    bottomContentPadding: Dp = 0.dp,
    currentServerId: String? = null,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    timeContext: SlockTimeContext? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            listState.animateScrollToTopExpressive()
        }
    }

    val inheritedTimeContext = LocalSlockTimeContextProvider.current?.current
    val effectiveTimeContext = resolveEffectiveDmsTimeContext(timeContext, inheritedTimeContext)
    val effectiveUnreadCount = totalUnreadCount ?: remember(directMessages) { totalWeChatDmUnreadCount(directMessages) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        if (showHeader) {
            SharedDmsHeader(
                totalUnreadCount = effectiveUnreadCount,
                refreshing = refreshing
            )
        }

        if (directMessages.isEmpty()) {
            SlockEmptyState(
                title = stringResource(MR.strings.home_section_direct_messages),
                detail = stringResource(MR.strings.empty_no_messages),
                iconName = SlockLucideIconName.MessageSquare,
                modifier = Modifier.weight(1f)
            )
        } else {
            val sortedItems = remember(directMessages) { directMessages.sortedForWeChatDms() }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                items(sortedItems.size, key = { i -> sortedItems[i].id }) { index ->
                    val item = sortedItems[index]
                    SlockWeChatDirectMessageRow(
                        item = item,
                        onClick = {
                            val identity = item.toWeChatDmLaunchIdentity()
                            if (identity != null) {
                                onOpenConversation(identity)
                            }
                        },
                        currentServerId = currentServerId,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        timeContext = effectiveTimeContext,
                    )
                    if (index < sortedItems.lastIndex) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SlockStroke.Thin)
                                .background(SlockColor.SurfaceWhite)
                        ) {
                            Spacer(Modifier.width(WeChatDmDividerStartPadding))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SlockStroke.Thin)
                                    .background(WeChatDividerColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedDmsHeader(
    totalUnreadCount: Int = 0,
    refreshing: Boolean = false
) {
    val baseTitle = stringResource(MR.strings.home_section_direct_messages)
    val displayTitle = formatWeChatDmHeaderTitle(baseTitle, totalUnreadCount)
    SlockMainTabPageHeader(
        surface = SlockMainTabHeaderSurface.Dms,
        title = displayTitle,
        contentStartPadding = WeChatDmRowHorizontalPadding,
        refreshing = refreshing
    )
}

/**
 * WeChat-style single Direct Message row.
 *
 * Visual hierarchy:
 * - Left: 48dp rounded avatar with presence status dot at bottom-end and unread badge at top-end.
 * - Center/Right:
 *   - Top line: Display name (truncated) + right-aligned relative timestamp.
 *   - Bottom line: Red draft prefix (if applicable) or last message snippet + right-aligned mute icon.
 */
@Composable
internal fun SlockWeChatDirectMessageRow(
    item: SlockHomeConversationItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currentServerId: String? = null,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    timeContext: SlockTimeContext? = null,
) {
    val isAgent = item.peerType == "agent" || item.peerType == "bot"
    val memberId = item.presenceMemberId ?: item.id
    val agentStatus = rememberSlockAgentStatusIndicatorState(agentStatusResolver, memberId)
    val presenceState = projectHomeAgentStatusIndicatorState(
        showPresence = isAgent,
        memberId = memberId,
        canonicalState = agentStatus
    )
    val avatarWireValue = rememberSlockMemberAvatarWireValue(
        memberAvatarResolver = memberAvatarResolver,
        memberId = memberId
    ) ?: item.avatarWireValue

    val inheritedTimeContext = LocalSlockTimeContextProvider.current?.current
    val effectiveTimeContext = resolveEffectiveDmsTimeContext(timeContext, inheritedTimeContext)
    val timestampText = formatSlockTimestampLabel(item.lastMessageAt ?: item.createdAt, effectiveTimeContext)
    val previewText = formatWeChatConversationPreview(item)
    val draftLabel = stringResource(MR.strings.thread_inline_draft)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WeChatDmRowHeight)
            .background(SlockColor.SurfaceWhite)
            .clickable(onClick = onClick)
            .padding(horizontal = WeChatDmRowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 40dp Avatar container with status dot and unread badge
        Box(
            modifier = Modifier.size(WeChatDmAvatarSize)
        ) {
            SlockAvatarWithStatus(
                statusColor = presenceState?.color,
                avatarSize = WeChatDmAvatarSize,
                statusSize = WeChatDmStatusDotSize,
                statusPulsing = presenceState?.pulsing == true
            ) {
                SlockAvatar(
                    avatarWireValue = avatarWireValue,
                    label = item.avatarLabel,
                    avatarSize = WeChatDmAvatarSize,
                    cacheSubject = slockMemberAvatarCacheSubject(item.peerType.weChatDmPeerMemberKindOrNull(), memberId),
                    cacheServerId = currentServerId
                )
            }

            // Unread badge at TopEnd
            if (item.unreadCount > 0) {
                if (item.activityMuted) {
                    // Muted unread: small red dot
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(WeChatDmMutedDotSize)
                            .borderRadius(WeChatDmMutedDotSize / 2)
                            .background(WeChatRedBadgeColor)
                    )
                } else {
                    // Unmuted unread: numeric pill/circle
                    val countLabel = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .height(WeChatDmBadgeHeight)
                            .widthIn(min = WeChatDmBadgeHeight)
                            .borderRadius(WeChatDmBadgeHeight / 2)
                            .background(WeChatRedBadgeColor)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = countLabel,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                                fontSize = 11.sp,
                                lineHeight = 11.sp
                            ),
                            color = SlockColor.SurfaceWhite,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(WeChatDmContentGap))

        // Right-side message content column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            // Top row: Name + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.name,
                    style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp).withSlockFontWeight(FontWeight.Medium),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (timestampText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timestampText,
                        style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Bottom row: Draft + Preview + Muted Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.hasDraft) {
                        Text(
                            text = "[$draftLabel] ",
                            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            color = SlockColor.AccentRed,
                            maxLines = 1
                        )
                    }
                    if (previewText.isNotBlank()) {
                        Text(
                            text = previewText,
                            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.activityMuted) {
                    Spacer(Modifier.width(6.dp))
                    SlockLucideIcon(
                        SlockLucideIconName.BellOff,
                        modifier = Modifier.size(14.dp),
                        color = SlockColor.TextMutedGrayArgb
                    )
                }
            }
        }
    }
}

/**
 * Resolves a fully-anchored [SlockTimeContext] for Direct Message rows.
 *
 * Format requirement (task #402): [formatSlockTimestampLabel] requires a non-null [SlockTimeContext.todayEpochDay]
 * or [SlockTimeContext.nowEpochMillis] to resolve relative and calendar day boundaries; without either,
 * it falls back to the raw unformatted ISO timestamp string (e.g. "2026-09-25T05:07:23.669Z").
 *
 * Resolution precedence:
 * 1. If [explicit] already carries an anchored date/time reference (e.g. unit tests, fixed visual fixtures),
 *    trust it directly.
 * 2. If [inherited] from [LocalSlockTimeContextProvider] carries an anchored reference, use its live
 *    [todayEpochDay] and [nowEpochMillis], merging any explicit [localOffsetMinutes] or [timeFormat] capabilities.
 * 3. Fallback: anchor against the current system wall clock via [defaultNowMillis] and calculate [todayEpochDay]
 *    from local offset so DM timestamps never display raw ISO strings.
 */
internal fun resolveEffectiveDmsTimeContext(
    explicit: SlockTimeContext?,
    inherited: SlockTimeContext?
): SlockTimeContext {
    if (explicit?.todayEpochDay != null || explicit?.nowEpochMillis != null) {
        return explicit
    }
    if (inherited?.todayEpochDay != null || inherited?.nowEpochMillis != null) {
        return inherited.copy(
            localOffsetMinutes = explicit?.localOffsetMinutes ?: inherited.localOffsetMinutes,
            timeFormat = explicit?.timeFormat ?: inherited.timeFormat
        )
    }
    val base = explicit ?: inherited
    val now = defaultNowMillis()
    val offset = base?.localOffsetMinutes
        ?: ProcessSlockDatetimeFoundationOwner.localOffsetMinutesAt(now, legacyOffsetMinutes = 0)
    val timeFormat = base?.timeFormat ?: SlockTimeFormat24Hour
    val today = floorDiv(now + offset.toLong() * MillisPerMinute, MillisPerDay)
    return SlockTimeContext(
        localOffsetMinutes = offset,
        todayEpochDay = today,
        nowEpochMillis = now,
        timeFormat = timeFormat
    )
}

/**
 * Sorts direct messages by most recent activity descending (WeChat conversational recency).
 * Fallback to createdAt, then name, then ID.
 */
internal fun List<SlockHomeConversationItem>.sortedForWeChatDms(): List<SlockHomeConversationItem> =
    sortedWith(
        compareByDescending<SlockHomeConversationItem> {
            parseSlockTimestampEpochMillisOrNull(it.lastMessageAt)
                ?: parseSlockTimestampEpochMillisOrNull(it.createdAt)
                ?: Long.MIN_VALUE
        }.thenBy { it.name.trim().lowercase() }.thenBy { it.id }
    )

/**
 * Formats the conversation preview text:
 * Prefers lastMessagePreview if present; does NOT fall back to description (peer bio/subtitle).
 * If lastMessageSenderName is present and distinct from the conversation name, it prefixes the snippet.
 */
internal fun formatWeChatConversationPreview(item: SlockHomeConversationItem): String {
    val preview = item.lastMessagePreview?.takeIf { it.isNotBlank() } ?: ""
    val sender = item.lastMessageSenderName?.takeIf { it.isNotBlank() }
    return if (sender != null && preview.isNotBlank() && sender != item.name) {
        "$sender: $preview"
    } else {
        preview
    }
}

/**
 * Formats the DM page top header title:
 * Appends the total unread count when greater than 0, e.g. "私信 (32)", or plain "私信" if 0.
 */
internal fun formatWeChatDmHeaderTitle(baseTitle: String, unreadCount: Int): String {
    val safeCount = unreadCount.coerceAtLeast(0)
    return if (safeCount > 0) {
        "$baseTitle ($safeCount)"
    } else {
        baseTitle
    }
}

/**
 * Calculates the total unread count for the WeChat DM page header:
 * Muted conversations are excluded, matching WeChat's title count and Slock's unmutedUnreadTotal convention.
 */
internal fun totalWeChatDmUnreadCount(directMessages: List<SlockHomeConversationItem>): Int =
    directMessages.filterNot { it.activityMuted }.sumOf { it.unreadCount.coerceAtLeast(0) }

/**
 * Resolves a [ThreadLaunchConversationIdentity] from a DM conversation row, guaranteeing
 * [ConversationKind.DirectMessage] surface binding even when the row's kind tag is empty.
 */
internal fun SlockHomeConversationItem.toWeChatDmLaunchIdentity(): ThreadLaunchConversationIdentity? {
    val cleanId = id.takeIf { it.isNotBlank() } ?: return null
    val parsedChannelType = channelType?.takeIf { it.isNotBlank() }
    return ThreadLaunchConversationIdentity(
        threadId = ThreadId(cleanId),
        channelId = ChannelId(cleanId),
        channelType = parsedChannelType,
        threadStreamChannelIdHint = null,
        kind = ConversationKind.DirectMessage,
        dmPeerIdentity = memberDetailIdentityFromWire(
            memberId = presenceMemberId,
            kindWireValue = peerType,
            source = MemberDetailIdentitySource.DirectoryDmPeer
        )
    )
}

private fun String?.weChatDmPeerMemberKindOrNull(): MemberKind? =
    parsedMemberKindOrNull(MemberKindWireContract.InternalActor)
        ?: projectedMemberKindOrNull()?.takeIf { it != MemberKind.System }
