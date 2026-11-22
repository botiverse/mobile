package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import build.raft.app.RaftDesktopMediaRoute
import build.raft.app.RaftDesktopVideoController
import build.raft.app.RaftDesktopVideoEngine
import build.raft.app.RaftDesktopVideoKeyAction
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
        override var hasMedia = false
        override var isPlaying = false
        override var isLoading = false
        override var failed = false
        override var failedWithoutVideo = false
        override var positionSeconds = 0.0
        override var durationSeconds = 0.0
        override var volume = 1f
        override var speed = 1f

        override fun open(uri: String, autoplay: Boolean) {
            calls += "open($uri,autoplay=$autoplay)"
        }

        override fun play() {
            calls += "play"
            isPlaying = true
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

    @Test
    fun preplayOpensPausedOnceAndPlayResumes() {
        val engine = FakeEngine()
        val controller = RaftDesktopVideoController(src, engine)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        controller.applyPlayControl(VideoPlayControl.PREPLAY)
        assertEquals(listOf("open($src,autoplay=false)"), engine.calls)
        ready(engine)
        assertEquals(PlayState.PAUSED, controller.snapshot().state)
        assertTrue(controller.snapshot().firstFrame)
        controller.applyPlayControl(VideoPlayControl.PLAY)
        assertEquals("play", engine.calls.last())
        assertEquals(PlayState.PLAYING, controller.snapshot().state)
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
