package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ThreadAttachmentCommentAnchor
import build.raft.app.data.label
import build.raft.app.data.raftVideoTimestampLabel
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import kotlin.math.roundToInt

// Video timestamp comments, web parity (slock #3065, #3696, #3960; web VideoPreviewBody in
// attachmentPreviewSurfaces.tsx). The comments sheet being open is web's comment mode (the
// Preview/Comment segment opens and closes the panel as one axis). In comment mode:
// - pausing the video, or seeking while paused, attaches the current time as the pending
//   anchor (web `pause` / `seeked` listeners);
// - while paused past the start with no timestamp anchor (the person removed it, or opened the
//   comments while already paused), "Add Timestamp M:SS" attaches it;
// - send reads the pending anchor from the state at send time, so the timestamp captured just
//   before the tap rides along (#3960), and clears it once accepted.
// A comment's timestamp chip pauses the open player and seeks it there (web
// `createVideoSeekCoalescer`: pause, then set currentTime; it never starts playback).

/**
 * The open video's comment wiring: [commentMode] while the comments sheet is open,
 * [hasTimestampAnchor] while a timestamp is pending, [jumpRequest] the latest chip tap.
 */
internal data class RaftFilePreviewVideoComments(
    val commentMode: Boolean,
    val hasTimestampAnchor: Boolean,
    val jumpRequest: RaftFilePreviewVideoSeekRequest?,
    val onAttachTimestamp: (timeSeconds: Double) -> Unit,
)

/**
 * The player's position before anything is played: iOS shows its first frame by seeking to
 * 40ms (PREPLAY). Web only offers a timestamp past `currentTime > 0`; this keeps the opening
 * frame from reading as "0:00".
 */
private const val RaftVideoCommentStartPositionMs = 40

/** web `paused || ended`. */
internal fun raftVideoCommentPlayStateIsPaused(state: PlayState): Boolean =
    state == PlayState.PAUSED || state == PlayState.PLAY_END

/** web `Math.round(video.currentTime * 1000) / 1000`, offered only past the start. */
internal fun raftVideoCommentAnchorSeconds(currentTimeMs: Int): Double? =
    currentTimeMs.takeIf { it > RaftVideoCommentStartPositionMs }?.let { it / 1000.0 }

/** The time a pause (or a seek while paused) attaches in comment mode; null attaches nothing. */
internal fun raftVideoCommentAutoAnchorSeconds(
    commentMode: Boolean,
    playState: PlayState,
    currentTimeMs: Int,
): Double? =
    if (commentMode && raftVideoCommentPlayStateIsPaused(playState)) raftVideoCommentAnchorSeconds(currentTimeMs) else null

/** web `canAddPausedTimestamp`: comment mode, paused past the start, no timestamp pending. */
internal fun raftVideoCommentCanAddPausedTimestamp(
    commentMode: Boolean,
    playState: PlayState,
    currentTimeMs: Int,
    hasTimestampAnchor: Boolean,
): Boolean =
    commentMode &&
        !hasTimestampAnchor &&
        raftVideoCommentPlayStateIsPaused(playState) &&
        raftVideoCommentAnchorSeconds(currentTimeMs) != null

/** The seek request after [this] that moves the player to [timeMs]. */
internal fun RaftFilePreviewVideoSeekRequest?.nextVideoSeekRequest(timeMs: Int): RaftFilePreviewVideoSeekRequest =
    RaftFilePreviewVideoSeekRequest(id = (this?.id ?: 0) + 1, timeMs = timeMs.coerceAtLeast(0))

/** A comment chip tap: the jump to its timestamp, or null for anchors the player cannot reach. */
internal fun RaftFilePreviewVideoSeekRequest?.nextVideoTimestampJump(
    anchor: ThreadAttachmentCommentAnchor,
): RaftFilePreviewVideoSeekRequest? =
    when (anchor) {
        is ThreadAttachmentCommentAnchor.VideoTimestamp -> nextVideoSeekRequest((anchor.timeSeconds * 1000).roundToInt())
    }

internal val ThreadAttachmentCommentsDialogState.hasVideoTimestampAnchor: Boolean
    get() = pendingAnchor is ThreadAttachmentCommentAnchor.VideoTimestamp

/** Attach [timeSeconds] as the pending anchor; its chip reads `M:SS` above the composer. */
internal fun ThreadAttachmentCommentsDialogState.withPendingVideoTimestamp(
    timeSeconds: Double,
): ThreadAttachmentCommentsDialogState {
    val anchor = ThreadAttachmentCommentAnchor.VideoTimestamp(timeSeconds)
    return copy(pendingAnchor = anchor, pendingAnchorLabel = anchor.label)
}

/** The pending anchor chip's remove button. */
internal fun ThreadAttachmentCommentsDialogState.withoutPendingAnchor(): ThreadAttachmentCommentsDialogState =
    copy(pendingAnchor = null, pendingAnchorLabel = null, pendingQuoteLabel = null)

/** After the server accepted a comment: web clears the anchor it carried (`onAccepted`). */
internal fun ThreadAttachmentCommentsDialogState.afterAnchoredCommentAccepted(): ThreadAttachmentCommentsDialogState =
    if (pendingAnchor != null) copy(pendingAnchor = null, pendingAnchorLabel = null) else this

/**
 * The play control that matches what the native controller is doing, so a later PAUSE from a
 * comment jump is a change the player applies (players apply a control only when it changes).
 */
internal fun raftVideoCommentMirroredPlayControl(current: VideoPlayControl, state: PlayState): VideoPlayControl =
    when {
        current == VideoPlayControl.STOP -> current
        state == PlayState.PLAYING -> VideoPlayControl.PLAY
        state == PlayState.PAUSED -> VideoPlayControl.PAUSE
        else -> current
    }

/**
 * Comment-mode capture and chip jumps for the open video. A pause, or a seek while paused,
 * attaches the time (keyed on the paused flag and the position, so opening the comments while
 * paused, or removing the chip, attaches nothing until the next pause or seek: web listens for
 * `pause`/`seeked`). A jump that was already pending when the player (re)started is not replayed.
 */
@Composable
internal fun RaftFilePreviewVideoCommentEffects(
    videoComments: RaftFilePreviewVideoComments,
    source: String,
    playState: PlayState,
    currentTimeMs: Int,
    onJump: (timeMs: Int) -> Unit,
) {
    val latestComments by rememberUpdatedState(videoComments)
    val latestOnJump by rememberUpdatedState(onJump)
    val latestPlayState by rememberUpdatedState(playState)
    val paused = raftVideoCommentPlayStateIsPaused(playState)
    LaunchedEffect(source, paused, currentTimeMs) {
        val comments = latestComments
        raftVideoCommentAutoAnchorSeconds(comments.commentMode, latestPlayState, currentTimeMs)
            ?.let(comments.onAttachTimestamp)
    }
    val jump = videoComments.jumpRequest
    val replayedJumpId = remember(source) { jump?.id }
    LaunchedEffect(source, jump?.id) {
        if (jump != null && jump.id != replayedJumpId) latestOnJump(jump.timeMs)
    }
}

/** web `video-comment-add-timestamp`: an outline button under the player, "Add Timestamp M:SS". */
@Composable
internal fun RaftFilePreviewVideoAddTimestampButton(
    currentTimeMs: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftDesignSystemTheme {
        RaftButton(
            text = stringResource(Res.string.thread_comments_add_timestamp, raftVideoTimestampLabel(currentTimeMs / 1000.0)),
            onClick = onClick,
            modifier = modifier,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Plus) }
        )
    }
}
