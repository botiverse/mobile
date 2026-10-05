package build.raft.app

import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl

/** Source-scoped playback commands and callbacks for the inline audio card. */
internal data class ThreadInlineAudioPlayback(
    val generation: Int = 0,
    val control: VideoPlayControl = VideoPlayControl.PREPLAY,
    val state: PlayState = PlayState.NONE,
    val currentTimeMs: Int = 0,
    val totalTimeMs: Int = 0,
    val seekRequest: SlockFilePreviewVideoSeekRequest? = null
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
            seekRequest = null
        )
        PlayState.PLAYING -> copy(control = VideoPlayControl.PAUSE)
        else -> copy(control = VideoPlayControl.PLAY)
    }

    fun seekTo(timeMs: Int): ThreadInlineAudioPlayback {
        if (totalTimeMs <= 0) return this
        val target = timeMs.coerceIn(0, totalTimeMs)
        return copy(
            currentTimeMs = target,
            seekRequest = SlockFilePreviewVideoSeekRequest((seekRequest?.id ?: 0) + 1, target),
            // Seeking an ended item leaves it paused; the next Play must be a
            // new command. Seeking while playing/paused preserves that intent.
            control = if (state == PlayState.PLAY_END) VideoPlayControl.PAUSE else control,
            state = if (state == PlayState.PLAY_END) PlayState.PAUSED else state
        )
    }

    fun withState(playerGeneration: Int, next: PlayState): ThreadInlineAudioPlayback =
        if (playerGeneration == generation) copy(state = next) else this

    fun withTime(playerGeneration: Int, current: Int, total: Int): ThreadInlineAudioPlayback =
        if (playerGeneration == generation) copy(
            currentTimeMs = current.coerceAtLeast(0),
            totalTimeMs = total.coerceAtLeast(0)
        ) else this
}
