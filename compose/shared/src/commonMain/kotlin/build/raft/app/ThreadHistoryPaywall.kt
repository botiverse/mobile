package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.raftBillingLeavesRaftTrailingIcon
import build.raft.app.components.RaftViewPlanButton
import build.raft.app.data.HiddenHistory
import build.raft.app.data.HiddenHistoryMessage
import build.raft.app.data.RaftHiddenHistoryStore
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

/*
 * The history paywall (botiverse/slock staging e99c01c4df, web
 * `packages/web/src/components/message/HistoryPaywall.tsx`; raft-design
 * `design/products/raft-chat/channel.md` "History behind the plan's window"): where a Free server's
 * 30-day window starts in a channel, DM, thread or task conversation, a faded glimpse of the newest
 * hidden messages, then a card that says how many the window hides, with Upgrade to Pro. It takes
 * the place of "Beginning of conversation" at the top of the list, and of the empty state when
 * every message is hidden. Not ported yet: a jump to a message behind the window (web mode
 * `target`) and paging back past the old window right after an upgrade.
 */

/** Free's message history (shared PLAN_CONFIG.free.limits.messageHistoryDays). */
internal const val ThreadHistoryPaywallDays = SettingsBillingFreeHistoryDays

/** Where the paywall sits; the titles differ by scope (web `HistoryPaywallScope`). */
internal enum class ThreadHistoryPaywallScope { Channel, Dm, Thread, Task }

internal fun threadHistoryPaywallScope(surfaceKind: String, channelKind: String?): ThreadHistoryPaywallScope =
    when {
        surfaceKind == RaftThreadSurfaceKind.ReplyThread -> ThreadHistoryPaywallScope.Thread
        channelKind.normalizedThreadChannelKind() == RaftTaskConversationChannelKind -> ThreadHistoryPaywallScope.Task
        surfaceKind == RaftThreadSurfaceKind.DirectMessage -> ThreadHistoryPaywallScope.Dm
        else -> ThreadHistoryPaywallScope.Channel
    }

internal fun ThreadTimelineSlice.historyPaywallScope(): ThreadHistoryPaywallScope =
    threadHistoryPaywallScope(surfaceKind, channelKind)

/** A title resource and the count it carries (null: the title has only the days). */
internal data class ThreadHistoryPaywallTitle(val resource: StringResource, val count: Int? = null)

private fun countedTitle(count: Int, capped: Boolean, one: StringResource, other: StringResource, cappedResource: StringResource) =
    ThreadHistoryPaywallTitle(
        resource = when {
            capped -> cappedResource
            count == 1 -> one
            else -> other
        },
        count = count,
    )

/**
 * The card's title (web `titleFor`): it names where the hidden messages are and how many, capped
 * ("Over 1,000"); without a count ("Messages older than 30 days are hidden") when the page gave none.
 * [allHidden]: nothing is left in the window ("All 13 messages in this channel…").
 */
internal fun threadHistoryPaywallTitle(
    scope: ThreadHistoryPaywallScope,
    hidden: HiddenHistory?,
    allHidden: Boolean,
): ThreadHistoryPaywallTitle {
    val replies = scope == ThreadHistoryPaywallScope.Thread
    if (hidden == null || (hidden.hiddenCount == 0 && !hidden.parentHidden)) {
        return ThreadHistoryPaywallTitle(
            if (replies) Res.string.thread_history_paywall_replies_title_uncounted else Res.string.thread_history_paywall_messages_title_uncounted
        )
    }
    val count = hidden.hiddenCount
    val capped = hidden.hiddenCountCapped
    if (replies && hidden.parentHidden) {
        if (count == 0) return ThreadHistoryPaywallTitle(Res.string.thread_history_paywall_thread_parent_only_title)
        return if (allHidden) {
            countedTitle(
                count, capped,
                Res.string.thread_history_paywall_thread_parent_all_title_one,
                Res.string.thread_history_paywall_thread_parent_all_title_other,
                Res.string.thread_history_paywall_thread_parent_all_title_capped,
            )
        } else {
            countedTitle(
                count, capped,
                Res.string.thread_history_paywall_thread_parent_title_one,
                Res.string.thread_history_paywall_thread_parent_title_other,
                Res.string.thread_history_paywall_thread_parent_title_capped,
            )
        }
    }
    if (replies) {
        return countedTitle(
            count, capped,
            Res.string.thread_history_paywall_replies_title_one,
            Res.string.thread_history_paywall_replies_title_other,
            Res.string.thread_history_paywall_replies_title_capped,
        )
    }
    if (allHidden) {
        return when (scope) {
            ThreadHistoryPaywallScope.Dm ->
                countedTitle(
                    count, capped,
                    Res.string.thread_history_paywall_dm_all_title_one,
                    Res.string.thread_history_paywall_dm_all_title_other,
                    Res.string.thread_history_paywall_dm_all_title_capped,
                )
            ThreadHistoryPaywallScope.Task ->
                countedTitle(
                    count, capped,
                    Res.string.thread_history_paywall_task_all_title_one,
                    Res.string.thread_history_paywall_task_all_title_other,
                    Res.string.thread_history_paywall_task_all_title_capped,
                )
            else ->
                countedTitle(
                    count, capped,
                    Res.string.thread_history_paywall_channel_all_title_one,
                    Res.string.thread_history_paywall_channel_all_title_other,
                    Res.string.thread_history_paywall_channel_all_title_capped,
                )
        }
    }
    return countedTitle(
        count, capped,
        Res.string.thread_history_paywall_messages_title_one,
        Res.string.thread_history_paywall_messages_title_other,
        Res.string.thread_history_paywall_messages_title_capped,
    )
}

/** `{count, number}`: digits grouped by thousands ("1,000"), as the web's en/zh-CN/ja formats show. */
internal fun threadHistoryPaywallCount(count: Int): String {
    val digits = count.toString()
    if (count < 0 || digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

/** What the window hides in [channelId], live ([RaftHiddenHistoryStore]); null when nothing. */
@Composable
internal fun rememberThreadHiddenHistory(channelId: String?): HiddenHistory? {
    val byChannel by RaftHiddenHistoryStore.byChannel.collectAsState()
    return channelId?.let { byChannel[it] }
}

/**
 * The paywall action: on the thread page it opens the upgrade dialog for the 30-day window
 * ([openRaftUpgradeDialog] `history_30d`), or the view-only Plan & Billing page on iOS.
 */
internal const val ThreadOpenPlanBillingEvent = "openPlanBilling"

/** A limit banner's Upgrade on the thread page: the upgrade dialog for the gate in `itemId` ([RaftUpgradeGate.wire]). */
internal const val ThreadOpenUpgradeEvent = "openUpgrade"

/**
 * The history paywall (see the file comment): [hidden]'s glimpse fading toward the top, the card,
 * and, when [allHidden], "No new messages in the last 30 days" under it. The card's action opens
 * Plan & Billing ([onUpgrade]): "Upgrade to Pro" where the app offers upgrades, "View your plan"
 * where it doesn't (iOS view-only Billing, App Store guideline 3.1.1).
 */
@Composable
internal fun ThreadHistoryPaywall(
    scope: ThreadHistoryPaywallScope,
    hidden: HiddenHistory,
    allHidden: Boolean,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
) {
    val days = ThreadHistoryPaywallDays
    val title = threadHistoryPaywallTitle(scope, hidden, allHidden)
    val titleText =
        if (title.count != null) {
            stringResource(title.resource, threadHistoryPaywallCount(title.count), days)
        } else {
            stringResource(title.resource, days)
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // As wide as the message rows (their px-2 inside the safe area); web `mb-3 gap-2`.
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = RaftAppSpacing.Sm)
                .padding(top = RaftAppSpacing.Sm, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
    ) {
        if (hidden.latest.isNotEmpty()) {
            ThreadHiddenHistoryGlimpse(hidden.latest, timeContext)
        }
        RaftCard(
            modifier = Modifier.fillMaxWidth(),
            title = titleText,
            description = stringResource(Res.string.thread_history_paywall_description, days),
            // History, not a lock: in the Raft DS a lock is a private channel.
            leading = { RaftContentLucideIcon(RaftLucideIconName.History, size = 20.dp) },
        ) {
            if (offersUpgrade) {
                RaftButton(
                    text = stringResource(Res.string.thread_history_paywall_upgrade),
                    onClick = onUpgrade,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Md,
                    trailingIcon = raftBillingLeavesRaftTrailingIcon(),
                )
            } else {
                RaftViewPlanButton(onClick = onUpgrade)
            }
        }
        if (allHidden) {
            // raft-ui MessageListNotice `muted`, pt-3 under the card.
            RaftText(
                text =
                    stringResource(
                        if (scope == ThreadHistoryPaywallScope.Thread) {
                            Res.string.thread_history_paywall_no_recent_replies
                        } else {
                            Res.string.thread_history_paywall_no_recent_messages
                        },
                        days,
                    ),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = RaftAppSpacing.Xs),
            )
        }
    }
}

/**
 * Every message is behind the window (web `channel-history-limited-empty`): the paywall sits on
 * the composer, where the newest messages would be, instead of "No messages yet".
 */
@Composable
internal fun ThreadHistoryPaywallEmptyState(
    scope: ThreadHistoryPaywallScope,
    hidden: HiddenHistory,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ThreadHistoryPaywall(
            scope = scope,
            hidden = hidden,
            allHidden = true,
            onUpgrade = onUpgrade,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            timeContext = timeContext,
        )
    }
}

/** raft-ui `mask-fade-t` (48dp): the glimpse fades out toward the top. */
private val ThreadHiddenHistoryFade = 48.dp

/**
 * The newest hidden messages as message rows (sender, time, at most two lines of the server's
 * 40-character cut), at 70%, fading out toward the top. A glimpse, not a read: not interactive and
 * hidden from screen readers (the card says what is hidden).
 */
@Composable
private fun ThreadHiddenHistoryGlimpse(messages: List<HiddenHistoryMessage>, timeContext: RaftTimeContext?) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
                .alpha(0.7f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = ThreadHiddenHistoryFade.toPx().coerceAtMost(size.height)
                    if (fade > 0f) {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    fade / size.height to Color.Black, // raft-color: DstIn alpha mask, not a visible colour
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
    ) {
        messages.forEach { message -> ThreadHiddenHistoryRow(message, timeContext) }
    }
}

private fun HiddenHistoryMessage.memberKind(): MemberKind? =
    when (senderType) {
        "agent" -> MemberKind.Agent
        "user" -> MemberKind.Human
        else -> null
    }

@Composable
private fun ThreadHiddenHistoryRow(message: HiddenHistoryMessage, timeContext: RaftTimeContext?) {
    val name = message.senderName?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.thread_history_paywall_unknown_sender)
    val kind = message.memberKind()
    // Only what the page carries: the server already left out faces the viewer may not see.
    val avatarWireValue =
        kind?.let {
            avatarWireValueForAvatarSlot(
                kind = it,
                id = message.senderId,
                avatarWireValue = message.senderAvatarUrl,
                gravatarHash = message.senderGravatarHash,
            )
        }
    // The message row's rhythm: a turn's 8 on top, 4 inside, the 35dp gutter and its 12 gap.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ThreadMessageTimelineTurn).padding(vertical = ThreadMessageTimelineRowInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(35.dp).padding(top = 2.dp)) {
            RaftAppAvatar(
                avatarWireValue = avatarWireValue,
                label = name,
                avatarSize = 35.dp,
                fallbackFill = raftAvatarFallbackFill(kind),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = name,
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                RaftRelativeTimestampText(
                    value = message.createdAt,
                    timeContext = timeContext,
                    displayGroup = RaftTimestampDisplayGroup.Conversation,
                    style = RaftTheme.typography.monoMeta,
                    color = RaftTheme.colors.foregroundHint,
                    maxLines = 1,
                    toggleEnabled = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            RaftText(
                text = message.preview?.let(::markdownToPreviewText)?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.thread_history_paywall_no_text),
                style = raftMessageBodyTextStyle(null),
                color = RaftTheme.colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}
