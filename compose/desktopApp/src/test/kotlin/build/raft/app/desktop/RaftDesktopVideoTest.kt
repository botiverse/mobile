package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import build.raft.app.RaftDesktopMediaRoute
import build.raft.app.RaftDesktopVideoController
import build.raft.app.RaftDesktopVideoEngine
import build.raft.app.RaftDesktopVideoKeyAction
import build.raft.app.RaftDesktopVideoSnapshot
import build.raft.app.RaftDesktopVideoSupport
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.raftDesktopVideoKeyAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop video host's headless parts; the native AVFoundation player itself needs a Mac. */
class RaftDesktopVideoTest {
    private class FakeEngine : RaftDesktopVideoEngine {
        val calls = mutableListOf<String>()

        /** The volume whenever the player was playing: when it started, and each change meanwhile. */
        val volumesWhilePlaying = mutableListOf<Float>()
        override var hasMedia = false
        override var isPlaying = false
        override var isLoading = false
        override var failed = false
        override var failedWithoutVideo = false
        override var positionSeconds = 0.0
        override var durationSeconds = 0.0
        override var volume = 1f
            set(value) {
                field = value
                if (isPlaying) volumesWhilePlaying += value
            }
        override var speed = 1f

        override fun open(uri: String, autoplay: Boolean) {
            calls += "open($uri,autoplay=$autoplay)"
        }

        override fun play() {
            calls += "play"
            isPlaying = true
            volumesWhilePlaying += volume
        }

        override fun pause() {
            calls += "pause"
            isPlaying = false
        }

        override fun seekToFraction(fraction: Float) {
            calls += "seek($fraction)"
            positionSeconds = durationSeconds * fraction
        }

        override fun stop() {
            calls += "stop"
            isPlaying = false
            hasMedia = false
        }

        override fun clearError() {
            calls += "clearError"
            failed = false
        }

        override fun release() {
            calls += "release"
        }
    }

    private val src = "https://files.example/clip.mp4"

    private fun ready(engine: FakeEngine, durationSeconds: Double = 10.0) {
        engine.hasMedia = true
        engine.durationSeconds = durationSeconds
    }

    /** Polls as the desktop host does until the first frame is up; the fake's clock runs while it plays. */
    private fun pollUntilFirstFrame(controller: RaftDesktopVideoController, engine: FakeEngine): List<RaftDesktopVideoSnapshot> {
        val polls = mutableListOf<RaftDesktopVideoSnapshot>()
        repeat(20) {
            if (engine.isPlaying) engine.positionSeconds += 0.1
            val snapshot = controller.poll()
            polls += snapshot
            if (snapshot.firstFrame) return polls
        }
        error("no first frame after ${polls.size} polls: calls=${engine.calls} polls=$polls")
    }

    // PREPLAY is what a message's inline video and the file preview ask for (ThreadInlineVideoSurface,
    // RaftFilePreviewVideoPanel): the video's first frame, paused, as on the web, Android and iOS.
    @Test
    fun preplayShowsTheFirstFramePausedAtTheStartWithoutSound() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        assertEquals(listOf("open($src,autoplay=false)"), engine.calls)
        ready(engine, durationSeconds = 20.0)
        // Loaded is not drawn (on macOS the player draws frames only while it plays): the page keeps
        // its spinner instead of showing a black box.
        assertFalse(controller.snapshot().firstFrame)
        // The file preview mirrors PAUSED back as PAUSE; the frame still comes up.
        controller.applyPlayControl(VideoPlayControl.PAUSE)

        val polls = pollUntilFirstFrame(controller, engine)

        // No autoplay as far as anyone can see or hear: paused at 0:00 throughout, played only silently.
        assertTrue(polls.all { it.state == PlayState.PAUSED && it.currentMs == 0 && it.totalMs == 20_000 }, "$polls")
        assertTrue(engine.volumesWhilePlaying.isNotEmpty(), "frames come from playback")
        assertTrue(engine.volumesWhilePlaying.all { it == 0f }, "heard: ${engine.volumesWhilePlaying}")
        // It rests paused at the start, with the volume back.
        assertFalse(engine.isPlaying)
        assertEquals(0.0, engine.positionSeconds)
        assertEquals(1f, engine.volume)
        assertFalse(controller.preparingFirstFrame)
        // Play plays it from there, with sound.
        controller.togglePlay()
        assertEquals(PlayState.PLAYING, controller.poll().state)
        assertEquals(1f, engine.volumesWhilePlaying.last())
    }

    @Test
    fun playBeforeTheFirstFrameIsUpPlaysFromTheStartWithSound() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        ready(engine)
        repeat(5) { if (!engine.isPlaying) controller.poll() }
        assertTrue(engine.isPlaying, "the silent warm-up should be playing: ${engine.calls}")
        engine.positionSeconds = 0.1
        // The controls still offer Play (not Pause), and the user clicks it.
        assertFalse(controller.isPlaying)
        controller.togglePlay()
        assertEquals(listOf("seek(0.0)", "play"), engine.calls.takeLast(2))
        assertEquals(1f, engine.volume)
        val playing = controller.poll()
        assertEquals(PlayState.PLAYING, playing.state)
        assertEquals(0, playing.currentMs)
        // The spinner goes once the picture moves.
        engine.positionSeconds = 0.5
        assertTrue(controller.poll().firstFrame)
    }

    @Test
    fun endedReportsPlayEndAndPlayRestartsFromTheStart() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        ready(engine)
        controller.togglePlay()
        engine.isPlaying = false
        engine.positionSeconds = 10.0
        controller.onEnded()
        val ended = controller.snapshot()
        assertEquals(PlayState.PLAY_END, ended.state)
        assertEquals(10_000, ended.currentMs)
        assertEquals(10_000, ended.totalMs)
        assertTrue(controller.ended)
        controller.togglePlay()
        assertEquals(listOf("seek(0.0)", "play"), engine.calls.takeLast(2))
        assertEquals(PlayState.PLAYING, controller.snapshot().state)
        assertFalse(controller.ended)
    }

    @Test
    fun seekRequestAppliesOncePerIdAndNeedsADuration() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        controller.applySeekRequest(1, 2_000)
        assertTrue(engine.calls.none { it.startsWith("seek") }, "no duration yet")
        ready(engine, durationSeconds = 8.0)
        controller.applySeekRequest(2, 2_000)
        controller.applySeekRequest(2, 2_000)
        assertEquals(listOf("seek(0.25)"), engine.calls.filter { it.startsWith("seek") })
        assertEquals(2_000, controller.snapshot().currentMs)
        assertEquals(0.25f, controller.progress)
    }

    @Test
    fun muteKeepsTheVolumeAndPageMuteWins() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        controller.changeVolume(0.4f)
        assertEquals(0.4f, engine.volume)
        controller.toggleMuted()
        assertTrue(controller.muted)
        assertEquals(0f, engine.volume)
        controller.toggleMuted()
        assertFalse(controller.muted)
        assertEquals(0.4f, engine.volume)
        controller.setHostMuted(true)
        assertTrue(controller.muted)
        assertEquals(0f, engine.volume)
        controller.changeVolume(0.8f)
        assertEquals(0f, engine.volume, "the page's mute stays")
        controller.setHostMuted(false)
        assertEquals(0.8f, engine.volume)
    }

    @Test
    fun failureReportsErrorAndTheButtonRetries() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        engine.failed = true
        assertEquals(PlayState.ERROR, controller.snapshot().state)
        assertFalse(controller.snapshot().firstFrame)
        controller.togglePlay()
        assertEquals(listOf("clearError", "stop", "open($src,autoplay=true)"), engine.calls.takeLast(3))
        assertEquals(PlayState.BUFFERING, controller.snapshot().state)
    }

    @Test
    fun audioOnlyMediaRoutesToTheNotAvailableNotice() {
        val notice = RaftDesktopMediaRoute.NotAvailableNotice
        // The preview's audio panel says so.
        assertEquals(notice, RaftDesktopVideoSupport.route(src, audioOnly = true, available = true))
        // Audio by file extension or MIME in the URL, whatever the panel.
        for (url in
            listOf(
                "https://files.example/a/voice.mp3",
                "https://files.example/a/memo.M4A?X-Amz-Signature=abc",
                "https://files.example/a/clip.aac#t=3",
                "https://files.example/a/take.wav",
                "https://files.example/a/blob?response-content-type=audio%2Fmpeg",
            )) {
            assertEquals(notice, RaftDesktopVideoSupport.route(url, audioOnly = false, available = true), url)
        }
        // Video goes to the player only where it runs.
        assertEquals(RaftDesktopMediaRoute.Player, RaftDesktopVideoSupport.route(src, audioOnly = false, available = true))
        assertEquals(
            RaftDesktopMediaRoute.Player,
            RaftDesktopVideoSupport.route("https://files.example/a/screen.mov?sig=1", audioOnly = false, available = true),
        )
        assertEquals(notice, RaftDesktopVideoSupport.route(src, audioOnly = false, available = false))
    }

    @Test
    fun stopDropsTheMediaAndReleaseIsFinal() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        ready(engine)
        controller.applyPlayControl(VideoPlayControl.STOP)
        assertEquals("stop", engine.calls.last())
        controller.applyPlayControl(VideoPlayControl.PLAY)
        assertEquals("open($src,autoplay=true)", engine.calls.last())
        controller.release()
        controller.release()
        controller.togglePlay()
        assertEquals(1, engine.calls.count { it == "release" })
        assertEquals("release", engine.calls.last())
        assertEquals(PlayState.NONE, controller.snapshot().state)
    }

    @Test
    fun spaceTogglesPlayAndEscapeIsLeftToThePage() {
        assertEquals(RaftDesktopVideoKeyAction.TogglePlay, raftDesktopVideoKeyAction(Key.Spacebar))
        assertNull(raftDesktopVideoKeyAction(Key.Escape))
        assertNull(raftDesktopVideoKeyAction(Key.Enter))
    }

    @Test
    fun platformGateIsMacOs14OrLater() {
        assertTrue(RaftDesktopVideoSupport.platformSupported("Mac OS X", "14.0"))
        assertTrue(RaftDesktopVideoSupport.platformSupported("Mac OS X", "26.1"))
        assertFalse(RaftDesktopVideoSupport.platformSupported("Mac OS X", "13.6.1"))
        assertFalse(RaftDesktopVideoSupport.platformSupported("Mac OS X", null))
        assertFalse(RaftDesktopVideoSupport.platformSupported("Linux", "6.12"))
        assertFalse(RaftDesktopVideoSupport.platformSupported("Windows 11", "10.0"))
        assertFalse(RaftDesktopVideoSupport.platformSupported(null, "14.0"))
    }
}
