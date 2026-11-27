package build.raft.app.thread

import build.raft.app.RaftFilePreviewVideoSeekRequest
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl

/** Source-scoped playback commands and callbacks for the inline audio card. */
internal data class ThreadInlineAudioPlayback(
    val generation: Int = 0,
    val control: VideoPlayControl = VideoPlayControl.PREPLAY,
    val state: PlayState = PlayState.NONE,
    val currentTimeMs: Int = 0,
    val totalTimeMs: Int = 0,
    val seekRequest: RaftFilePreviewVideoSeekRequest? = null,
    /** Saved offset to seek back to on the next Play after the card was detached off-screen/off-page. */
    val pendingResumeTimeMs: Int? = null,
) {
    fun toggle(): ThreadInlineAudioPlayback = when (state) {
        // A PLAY attribute equal to the previous PLAY is not dispatched again.
        // A fresh player starts at zero without racing an asynchronous seek
        // against end-of-item callbacks from the previous player.
        PlayState.PLAY_END -> copy(
            generation = generation + 1,
            control = VideoPlayControl.PLAY,
            state = PlayState.NONE,
            currentTimeMs = 0,
            seekRequest = null,
            pendingResumeTimeMs = null,
        )
        PlayState.PLAYING -> copy(control = VideoPlayControl.PAUSE, pendingResumeTimeMs = null)
        else -> {
            val resumeTarget =
                pendingResumeTimeMs
                    ?: currentTimeMs.takeIf { control == VideoPlayControl.PLAY && it > 0 }
            val nextSeek =
                if (resumeTarget != null && resumeTarget > 0) {
                    RaftFilePreviewVideoSeekRequest((seekRequest?.id ?: 0) + 1, resumeTarget)
                } else {
                    seekRequest
                }
            if (control == VideoPlayControl.PLAY) {
                // Playback was externally paused or evicted by the player budget while `control`
                // remained PLAY: bump generation so a fresh player receives PLAY cleanly.
                copy(
                    generation = generation + 1,
                    control = VideoPlayControl.PLAY,
                    state = PlayState.NONE,
                    seekRequest = nextSeek,
                    pendingResumeTimeMs = null,
                )
            } else {
                copy(
                    control = VideoPlayControl.PLAY,
                    seekRequest = nextSeek,
                    pendingResumeTimeMs = null,
                )
            }
        }
    }

    fun seekTo(timeMs: Int): ThreadInlineAudioPlayback {
        if (totalTimeMs <= 0) return this
        val target = timeMs.coerceIn(0, totalTimeMs)
        return copy(
            currentTimeMs = target,
            seekRequest = RaftFilePreviewVideoSeekRequest((seekRequest?.id ?: 0) + 1, target),
            pendingResumeTimeMs = null,
            // Seeking an ended item leaves it paused; the next Play must be a
            // new command. Seeking while playing/paused preserves that intent.
            control = if (state == PlayState.PLAY_END) VideoPlayControl.PAUSE else control,
            state = if (state == PlayState.PLAY_END) PlayState.PAUSED else state
        )
    }

    /**
     * Releases the underlying platform player when the enclosing page becomes inactive or the card
     * scrolls out of the viewport, preserving the remembered timestamp/duration for seamless resume.
     */
    fun onPlayerDetached(): ThreadInlineAudioPlayback {
        val resumeOffset = currentTimeMs.takeIf { it > 0 && state != PlayState.PLAY_END }
        val detachedState =
            when (state) {
                PlayState.PLAYING, PlayState.BUFFERING -> PlayState.PAUSED
                PlayState.ERROR -> PlayState.NONE
                else -> state
            }
        val detachedControl =
            if (control == VideoPlayControl.PLAY || detachedState == PlayState.PAUSED) {
                VideoPlayControl.PAUSE
            } else {
                VideoPlayControl.PREPLAY
            }
        return copy(
            generation = generation + 1,
            control = detachedControl,
            state = detachedState,
            seekRequest = null,
            pendingResumeTimeMs = resumeOffset,
        )
    }

    fun withState(playerGeneration: Int, next: PlayState): ThreadInlineAudioPlayback =
        if (playerGeneration == generation) copy(state = next) else this

    fun withTime(playerGeneration: Int, current: Int, total: Int): ThreadInlineAudioPlayback =
        if (playerGeneration == generation) copy(
            currentTimeMs = current.coerceAtLeast(0),
            totalTimeMs = if (total > 0) total else totalTimeMs
        ) else this
}
