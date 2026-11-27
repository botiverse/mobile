package build.raft.app.thread

import build.raft.app.resources.*
import build.raft.app.traceId
import build.raft.app.withRaftFontWeight
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftToast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import build.raft.app.RaftColor
import build.raft.app.RaftFloatingOverlayHostZIndex
import build.raft.app.RaftNotificationLevelTarget
import build.raft.app.RaftTraceIdNote
import build.raft.app.RaftType
import build.raft.app.raftTopSafeAreaPadding
import kotlinx.coroutines.delay

@Composable
private fun ThreadHeaderAction(label: String, onClick: () -> Unit) {
    RaftButton(
        text = label,
        onClick = onClick,
        variant = RaftButtonVariant.Default,
        size = RaftButtonSize.Md
    )
}

@Composable
internal fun localizedThreadStateTitle(value: String): String =
    when (value) {
        ThreadReplyEmptyTitle -> stringResource(Res.string.thread_state_no_replies)
        "No messages yet" -> stringResource(Res.string.thread_state_no_messages)
        "Loading..." -> stringResource(Res.string.thread_state_loading)
        else -> value
    }

@Composable
internal fun localizedThreadStateDetail(value: String?): String? =
    when (value) {
        "This conversation has no messages yet." -> stringResource(Res.string.thread_state_no_messages_detail)
        else -> value
    }

/**
 * The thread page while its first load is in flight and nothing is known to stand in (artin 10-23):
 * raft-ui's spinner over a muted "Loading…". Callers gate initial reveal behind
 * [ThreadInitialLoadingRevealDelayMs] (`ThreadTimelineSlice.loadingIndicatorRevealed`) so fast
 * SQLite cache hits never flash a spinner, while slow or uncached loads never feel bare and never
 * show the empty state (which only a finished load may claim).
 */
@Composable
internal fun ThreadLoadingState(spinnerStillStep: Int? = null) {
    val label = stringResource(Res.string.thread_state_loading)
    Box(
        modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RaftSpinner(size = RaftSpinnerSize.Md, contentDescription = label, stillStep = spinnerStillStep)
            Text(
                text = label,
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
        }
    }
}

/**
 * #proj-mobile:#265: short grace window before revealing [ThreadLoadingState] on an initial
 * thread/channel open. Covers typical SQLite durable-cache reads (15–50 ms) and brief authority
 * waits without flashing a spinner, while still surfacing the loading indicator promptly on
 * genuinely uncached or slow network loads.
 */
internal const val ThreadInitialLoadingRevealDelayMs = 120L

/**
 * Task #80: how long a stand-in frame's "loading older" spinner waits before it shows, so a load that
 * answers quickly swaps the frame without a flash. The value the thread page's loading placeholder
 * used before #3596 (`ThreadLoadingRevealDelayMs`) and the file preview's minimum-visible time.
 */
internal const val ThreadStandInLoadingRevealDelayMs = 600L

/**
 * Task #1137 keeps a channel that was empty when last seen as a durable empty stand-in. Until the
 * first load answers it is only a guess (Activity may already list new messages there), so the page
 * shows the loading state instead of "No messages yet"; after a failed load it shows the stand-in
 * with the offline tip as before, and a canonical empty answer clears the stand-in flag.
 */
internal fun threadEmptyStandInAwaitsFirstLoad(
    status: String,
    durableLastKnown: Boolean,
    mainLoadFailed: Boolean,
): Boolean = status == "empty" && durableLastKnown && !mainLoadFailed

@Composable
internal fun ThreadStateMessage(title: String, detail: String?, traceId: String? = null) {
    Box(
        modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = RaftType.Body.withRaftFontWeight(FontWeight.W600).copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
            }
            RaftTraceIdNote(traceId)
        }
    }
}

/**
 * The receipt after a single forward: the web's forward toast (slock `ForwardComposerDialog` +
 * `ForwardToastProvider`): raft-ui Toast on the opaque HUD surface, title only (no intent icon),
 * one "View in chat" text action, top centre. It floats over the channel header, so it must be
 * the HUD toast, not the translucent soft-fill Banner (cindyz 10-29: unreadable in dark mode).
 */
@Composable
internal fun ThreadForwardSuccessToastView(
    banner: ThreadForwardSuccessBanner,
    onViewInChat: () -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = banner.target.forwardSuccessDisplayTitle()
    LaunchedEffect(banner) {
        delay(5_000)
        onDismiss()
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(RaftFloatingOverlayHostZIndex + 20f)
                .raftSafeAreaHorizontalPadding()
                // Web viewport: top-center, at most calc(100vw - 1rem) wide.
                .padding(start = 8.dp, top = raftTopSafeAreaPadding() + 8.dp, end = 8.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val viewInChat = banner.forwardSuccessViewInChatAvailable()
        RaftToast(
            title = stringResource(Res.string.thread_forward_success_banner, targetLabel),
            actionLabel = if (viewInChat) stringResource(Res.string.thread_forward_view_in_chat) else null,
            onAction = if (viewInChat) onViewInChat else null,
        )
    }
}

@Composable
internal fun ThreadStateActionMessage(
    title: String,
    detail: String?,
    actionLabel: String,
    onClick: () -> Unit,
    traceId: String? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = RaftType.Body.withRaftFontWeight(FontWeight.W600).copy(color = RaftColor.TextBlack),
                color = RaftColor.TextBlack
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = RaftType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
            }
            RaftTraceIdNote(traceId)
            ThreadHeaderAction(label = actionLabel, onClick = onClick)
        }
    }
}

/**
 * The level target of a reply thread or task conversation page: its own channel id under its
 * server, inheriting from [parentChannelId]. Null on a channel / DM page (those have their level in
 * the sidebar menu and the channel settings) or while the server or channel is not known.
 */
internal fun threadNotificationLevelTarget(
    serverId: String?,
    replyThreadOrTask: Boolean,
    threadChannelId: String?,
    parentChannelId: String?,
    parentIsDirectMessage: Boolean,
    muted: Boolean
): RaftNotificationLevelTarget? {
    if (!replyThreadOrTask) return null
    val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val thread = threadChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftNotificationLevelTarget(
        serverId = server,
        scope = build.raft.app.data.RaftNotificationLevelScope.Thread,
        id = thread,
        parentChannelId = parentChannelId?.trim()?.takeIf { it.isNotEmpty() && it != thread },
        isDirectMessage = parentIsDirectMessage,
        muted = muted
    )
}
