package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl

/**
 * Native video/audio player surface (CMP replacement for Kuikly's `VideoView`).
 *
 * Android: Media3 ExoPlayer inside a `PlayerView` (system controller, fullscreen), ported from the
 * former Kuikly adapter `androidApp/.../SlockKRVideoViewAdapter.kt`.
 * iOS: AVPlayer inside an embedded `AVPlayerViewController` (system controller), ported from the
 * former Kuikly handler `iosApp/.../SlockKRVideoViewHandler.m`.
 *
 * The control surface mirrors the Kuikly attrs/events the pages used:
 * - [playControl] is applied only when it changes (Kuikly diffed the attr the same way);
 *   `PREPLAY` warms the first frame without starting playback, `STOP` releases the player.
 * - [seekRequestId]/[seekTimeMs]: a seek is issued once per distinct non-null id (the old
 *   `seekToTimeMs` prop carried `"id:timeMs"` for the same reason).
 * - [onPlayStateChanged], [onPlayTimeChanged] (current/total ms), [onFirstFrame] mirror
 *   `playStateDidChanged`, `playTimeDidChanged` and `firstFrameDidDisplay`.
 *
 * A new [source] creates a fresh player.
 */
@Composable
internal expect fun RaftVideoPlayer(
    source: String,
    playControl: VideoPlayControl,
    seekRequestId: Int?,
    seekTimeMs: Int,
    muted: Boolean,
    rate: Float,
    onPlayStateChanged: (PlayState) -> Unit,
    onPlayTimeChanged: (currentMs: Int, totalMs: Int) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier = Modifier,
)

/**
 * True around a [RaftVideoPlayer] that plays an audio-only attachment (the file preview's audio
 * panel). Hosts that cannot play audio-only media ([raftPlatformPlaysAudioOnlyMedia] false) route
 * it to their "not available" state before creating a player.
 */
internal val LocalRaftVideoPlayerAudioOnly = staticCompositionLocalOf { false }

/**
 * Whether the preview's video panel hands its controls to the platform host. An audio-only file on
 * a host that cannot play audio keeps the shared card and controls, as before that host had a player.
 */
internal fun raftFilePreviewVideoUsesNativeController(
    audioOnly: Boolean,
    platformNativeController: Boolean,
    platformPlaysAudioOnly: Boolean
): Boolean = platformNativeController && (!audioOnly || platformPlaysAudioOnly)
