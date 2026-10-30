package build.raft.app.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.RaftThreadMessageItem
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.resources.*
import build.raft.app.threadActionCard
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDrawer
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource

// The assistant opened from its otter (web assistant/AssistantOtterPanel.tsx, in AssistantOtter's
// phone `Drawer`): its head and the owner's DM with it, over the page.

/** The meta line under the name (web AssistantOtterPanel `metaId`): what it is doing, else what it can read. */
internal fun assistantPanelMeta(mood: RaftOtterMood, readsDms: Boolean): StringResource =
    when {
        mood == RaftOtterMood.Working -> Res.string.assistant_otter_meta_working
        mood == RaftOtterMood.Confirm -> Res.string.assistant_otter_meta_confirm
        readsDms -> Res.string.assistant_header_meta_with_dms
        else -> Res.string.assistant_header_meta
    }

private val WaitingActionCardStates = setOf("prepared", "reconfirm_required")

/**
 * Whether the assistant's own action card in these messages still waits for its owner (web
 * assistantOtterState.ts `hasWaitingActionCard`): a card sent by [agentId] in `prepared` or
 * `reconfirm_required`.
 */
internal fun hasWaitingAssistantActionCard(messages: List<RaftThreadMessageItem>, agentId: String): Boolean =
    messages.any { message ->
        message.authorId == agentId && message.threadActionCard()?.state in WaitingActionCardStates
    }

/**
 * Given to the conversation the panel embeds: it reports the messages it holds, so the otter
 * knows of a card waiting there (web reads the DM's messages from its message store; the app
 * holds them only while the panel shows the conversation).
 */
internal class ThreadAssistantPanelEmbed(
    val agentId: String,
    val onWaitingActionCard: (Boolean) -> Unit,
)

/**
 * Set inside the panel's conversation: that conversation page is the assistant's DM in the
 * panel (web `ChatPanel hideHeader allowTasks={false}`): no page head and no tabs, and it
 * reports its messages here. Null on every other page.
 */
internal val LocalThreadAssistantPanelEmbed = compositionLocalOf<ThreadAssistantPanelEmbed?> { null }

/** The panel's conversation reporting what it holds (see [ThreadAssistantPanelEmbed]). */
@Composable
internal fun ThreadAssistantPanelMessagesReport(messages: List<RaftThreadMessageItem>) {
    val embed = LocalThreadAssistantPanelEmbed.current ?: return
    LaunchedEffect(embed, messages) {
        embed.onWaitingActionCard(hasWaitingAssistantActionCard(messages, embed.agentId))
    }
}

/**
 * The assistant's panel (web AssistantOtterPanel): the head, then [conversation] filling the
 * rest. The head is raft-ui's panel head: 56dp on the canvas with a seam under it; the otter's
 * head as the assistant's face (the image is cut at its chest, so it sits on the 32dp box's
 * bottom edge); the name in `text-headline` with the paused chip beside it; the meta line in
 * caption on `foreground-hint`, up to two lines (web `ASSISTANT_SUBTITLE_CLASS`); collapse (a
 * chevron down, as in a bottom sheet) at the end.
 */
@Composable
internal fun ThreadAssistantPanel(
    name: String,
    mood: RaftOtterMood,
    readsDms: Boolean,
    paused: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    conversation: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxSize().background(colors.layerCanvas).testTag(ThreadAssistantPanelTestTag)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ThreadAssistantPanelHeadHeight)
                .padding(start = 16.dp, end = 3.dp)
                .testTag(ThreadAssistantPanelHeaderTestTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(32.dp).clipToBounds(), contentAlignment = Alignment.BottomCenter) {
                Image(
                    painter = painterResource(Res.drawable.assistant_otter_curious),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(38.dp, 26.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RaftText(
                        name,
                        Modifier.weight(1f, fill = false).semantics { heading() },
                        style = RaftTheme.typography.headline,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (paused) {
                        // Web AssistantPausedChip: the agent state chip with the offline light and "Paused".
                        RaftBadge(
                            text = stringResource(Res.string.assistant_paused_chip),
                            modifier = Modifier.testTag(ThreadAssistantPanelPausedTestTag),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Muted,
                            icon = { RaftStatusDot(presence = RaftPresence.Offline) },
                        )
                    }
                }
                RaftText(
                    stringResource(assistantPanelMeta(mood, readsDms)),
                    style = RaftTheme.typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RaftIconButton(
                onClick = onCollapse,
                tooltip = stringResource(Res.string.assistant_otter_collapse),
                modifier = Modifier.testTag(ThreadAssistantPanelCollapseTestTag),
                variant = RaftButtonVariant.Ghost,
            ) {
                RaftGlyphIcon(RaftGlyph.ChevronDown, size = 18.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
        conversation()
    }
}

/**
 * The assistant's DM could not be opened (web `assistant-dm-missing`): one line in the body's
 * middle.
 */
@Composable
internal fun ColumnScope.ThreadAssistantPanelConversationMissing(name: String) {
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        RaftText(
            stringResource(Res.string.assistant_conversation_missing, name),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The otter's panel over the page while it is open (web AssistantOtter's phone `Drawer`,
 * `--drawer-content-height: 82vh`): a [RaftDrawer] holding [ThreadAssistantPanel] with
 * [conversation] as its body. Back, a tap on the scrim and collapse close it. The conversation's
 * composer pads for the home indicator and the keyboard itself; the drawer is its own window, so
 * the keyboard is that window's (no host IME report).
 */
@Composable
internal fun ThreadAssistantPanelSheet(
    otter: ThreadAssistantOtter,
    conversation: @Composable ColumnScope.() -> Unit,
) {
    if (!otter.panelOpen) return
    val close by rememberUpdatedState { otter.onPanelOpenChange(false) }
    RaftDrawer(
        onDismissRequest = { close() },
        modifier = Modifier.testTag(ThreadAssistantPanelSheetTestTag),
        clearNavigationBar = false,
    ) {
        CompositionLocalProvider(LocalRaftHostImeInsets provides null) {
            ThreadAssistantPanel(
                name = otter.assistantName,
                mood = otter.mood,
                readsDms = otter.readsDms,
                paused = otter.paused,
                onCollapse = { close() },
                conversation = {
                    CompositionLocalProvider(
                        LocalThreadAssistantPanelEmbed provides otter.panelEmbed,
                        // The otter's own composer is not in the panel: the DM has none.
                        LocalThreadAssistantOtter provides null,
                    ) {
                        conversation()
                    }
                },
            )
        }
    }
}

private val ThreadAssistantPanelHeadHeight = 56.dp

/** Web `data-testid`s: `assistant-otter-sheet`, `assistant-otter-panel`, its header and collapse. */
internal const val ThreadAssistantPanelSheetTestTag = "assistant-otter-sheet"
internal const val ThreadAssistantPanelTestTag = "assistant-otter-panel"
internal const val ThreadAssistantPanelHeaderTestTag = "assistant-otter-panel-header"
internal const val ThreadAssistantPanelCollapseTestTag = "assistant-otter-collapse"
internal const val ThreadAssistantPanelPausedTestTag = "assistant-paused-chip"
