package build.raft.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.thread.LocalThreadInlineAudioPlayer
import build.raft.app.thread.LocalThreadInlineAudioPreviewHost
import build.raft.app.thread.LocalThreadInlineVideoPlayer
import build.raft.app.thread.LocalThreadInlineVideoPreviewHost
import build.raft.app.thread.LocalThreadShareCaptureActive
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.ThreadInlineAudioAttachmentCard
import build.raft.app.thread.ThreadInlineAudioPreviewHost
import build.raft.app.thread.ThreadInlineAudioPreviewStatus
import build.raft.app.thread.ThreadInlineAudioPreviewUiState
import build.raft.app.thread.ThreadInlineVideoAttachmentCard
import build.raft.app.thread.ThreadInlineVideoPreviewHost
import build.raft.app.thread.ThreadInlineVideoPreviewStatus
import build.raft.app.thread.ThreadInlineVideoPreviewUiState
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadInlineMediaLifecycleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun inlineVideoUnmountsWhenPageBecomesInactiveOrShareCaptureRunsAndRecoversOnReturn() {
        var pageActive by mutableStateOf(true)
        var shareCapture by mutableStateOf(false)
        var activePlayerCount = 0
        var totalMounts = 0
        val observedRoles = mutableListOf<RaftMediaPlayerRole>()
        val observedControls = mutableListOf<VideoPlayControl>()
        val attachment =
            RaftThreadAttachmentItem(
                id = "vid-1",
                name = "clip.mp4",
                mimeType = "video/mp4",
                sizeLabel = "4.2 MB",
            )
        val host =
            ThreadInlineVideoPreviewHost(
                states = mapOf("vid-1" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Ready, url = "https://cdn.example/vid-1.mp4")),
                requestPreview = { _, _ -> },
            )

        render {
            CompositionLocalProvider(
                LocalRaftPageActive provides pageActive,
                LocalThreadShareCaptureActive provides shareCapture,
                LocalThreadInlineVideoPreviewHost provides host,
                LocalThreadInlineVideoPlayer provides { source, control, onState, onFirstFrame, modifier ->
                    val role = LocalRaftVideoPlayerRole.current
                    DisposableEffect(source) {
                        activePlayerCount += 1
                        totalMounts += 1
                        observedRoles += role
                        onDispose { activePlayerCount -= 1 }
                    }
                    LaunchedEffect(source, control) {
                        observedControls += control
                        onFirstFrame()
                        onState(PlayState.PAUSED)
                    }
                    Box(modifier)
                },
            ) {
                ThreadInlineVideoAttachmentCard(attachment = attachment, onDownload = {})
            }
        }

        assertEquals(1, activePlayerCount, "visible card on active page must mount inline video player")
        assertEquals(1, totalMounts)
        assertEquals(listOf(RaftMediaPlayerRole.InlineVideo), observedRoles)
        assertEquals(VideoPlayControl.PREPLAY, observedControls.last())

        // Simulate pushing another route / switching shell tab -> LocalRaftPageActive becomes false.
        pageActive = false
        settle()
        assertEquals(0, activePlayerCount, "inactive stacked page must release inline video player immediately")

        // Popping back to the page remounts cleanly in PREPLAY.
        pageActive = true
        settle()
        assertEquals(1, activePlayerCount, "returning to active page must recover inline video player")
        assertEquals(2, totalMounts)
        assertEquals(VideoPlayControl.PREPLAY, observedControls.last())

        // Share image capture unmounts live players without leaking decoders.
        shareCapture = true
        settle()
        assertEquals(0, activePlayerCount, "share capture must not retain live video players")
    }

    @Test
    fun inlineAudioUnmountsWhenPageBecomesInactiveSetsAudioOnlyRoleAndRemountsCleanly() {
        var pageActive by mutableStateOf(true)
        var activeAudioCount = 0
        var totalMounts = 0
        val observedRoles = mutableListOf<RaftMediaPlayerRole>()
        val observedAudioOnly = mutableListOf<Boolean>()
        val attachment =
            RaftThreadAttachmentItem(
                id = "aud-1",
                name = "voice-note.m4a",
                mimeType = "audio/mp4",
                sizeLabel = "640 KB",
            )
        val host =
            ThreadInlineAudioPreviewHost(
                states = mapOf("aud-1" to ThreadInlineAudioPreviewUiState(ThreadInlineAudioPreviewStatus.Ready, url = "https://cdn.example/aud-1.m4a")),
                requestPreview = { _, _ -> },
            )

        render {
            CompositionLocalProvider(
                LocalRaftPageActive provides pageActive,
                LocalThreadInlineAudioPreviewHost provides host,
                LocalThreadInlineAudioPlayer provides { source, _, _, onState, onTime, modifier ->
                    val role = LocalRaftVideoPlayerRole.current
                    val audioOnly = LocalRaftVideoPlayerAudioOnly.current
                    DisposableEffect(source) {
                        activeAudioCount += 1
                        totalMounts += 1
                        observedRoles += role
                        observedAudioOnly += audioOnly
                        onDispose { activeAudioCount -= 1 }
                    }
                    LaunchedEffect(source) {
                        onTime(12_000, 45_000)
                        onState(PlayState.PLAYING)
                    }
                    Box(modifier)
                },
            ) {
                ThreadInlineAudioAttachmentCard(attachment = attachment, onDownload = {})
            }
        }

        assertEquals(1, activeAudioCount, "visible card on active page mounts inline audio slot")
        assertEquals(listOf(RaftMediaPlayerRole.InlineAudio), observedRoles)
        assertEquals(listOf(true), observedAudioOnly)

        // Leaving the page disposes the hidden audio player immediately.
        pageActive = false
        settle()
        assertEquals(0, activeAudioCount, "inactive page must release inline audio player immediately")

        // Returning to the page remounts cleanly.
        pageActive = true
        settle()
        assertEquals(1, activeAudioCount, "returning to active page must remount inline audio slot")
        assertTrue(totalMounts >= 2)
        assertFalse(observedAudioOnly.any { !it })
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun androidVideoViewDefersInlineAudioEvictsLruRecoversOnTapAndPreservesTerminalStates() {
        val activity = activities.launch()
        val budget = RaftMediaPlayerBudget(maxInlinePlayers = 2)
        val createdPlayers = mutableListOf<FakeExoPlayerHandle>()
        val factory: (android.content.Context, RaftAndroidVideoBufferPolicy) -> ExoPlayer = { _, policy ->
            FakeExoPlayerHandle(policy).also { createdPlayers += it }.proxy
        }

        // 1. InlineAudio in PREPLAY allocates zero ExoPlayers until PLAY.
        val audioView =
            RaftAndroidVideoView(
                context = activity,
                src = "https://cdn.example/audio.m4a",
                audioOnly = true,
                callbacks = NoopVideoCallbacks,
                role = RaftMediaPlayerRole.InlineAudio,
                budget = budget,
                playerFactory = factory,
            )
        audioView.applyPlayControl(VideoPlayControl.PREPLAY)
        assertEquals(0, createdPlayers.size, "idle InlineAudio must allocate zero ExoPlayers")
        assertFalse(audioView.hasLivePlayerForTest)
        assertTrue(audioView.uiState.isDormant)

        audioView.applyPlayControl(VideoPlayControl.PLAY)
        assertEquals(1, createdPlayers.size)
        assertTrue(audioView.hasLivePlayerForTest)
        assertFalse(audioView.uiState.isDormant)
        assertEquals(512 * 1024, createdPlayers.last().policy.targetBufferBytes)
        audioView.stop()
        assertEquals(1, createdPlayers.last().releaseCalls)
        assertEquals(0, budget.activeInlineCount())

        // 2. InlineVideo caps concurrent players at 2, evicts LRU to dormant, and recovers on tap.
        val statesV1 = mutableListOf<PlayState>()
        var firstFramesV1 = 0
        val v1 =
            RaftAndroidVideoView(
                context = activity,
                src = "https://cdn.example/v1.mp4",
                audioOnly = false,
                callbacks =
                    object : RaftAndroidVideoView.Callbacks {
                        override fun onPlayState(state: PlayState) {
                            statesV1 += state
                        }

                        override fun onPlayTime(currentMs: Long, totalMs: Long) = Unit

                        override fun onFirstFrame() {
                            firstFramesV1 += 1
                        }
                    },
                role = RaftMediaPlayerRole.InlineVideo,
                budget = budget,
                playerFactory = factory,
            )
        val v2 =
            RaftAndroidVideoView(
                context = activity,
                src = "https://cdn.example/v2.mp4",
                audioOnly = false,
                callbacks = NoopVideoCallbacks,
                role = RaftMediaPlayerRole.InlineVideo,
                budget = budget,
                playerFactory = factory,
            )
        val v3 =
            RaftAndroidVideoView(
                context = activity,
                src = "https://cdn.example/v3.mp4",
                audioOnly = false,
                callbacks = NoopVideoCallbacks,
                role = RaftMediaPlayerRole.InlineVideo,
                budget = budget,
                playerFactory = factory,
            )

        v1.applyPlayControl(VideoPlayControl.PREPLAY)
        val p1 = createdPlayers.last()
        p1.currentPosition = 7_500L
        p1.playbackState = Player.STATE_READY
        v2.applyPlayControl(VideoPlayControl.PREPLAY)
        val p2 = createdPlayers.last()
        assertEquals(2, budget.activeInlineCount())

        // Mounting v3 evicts v1 (oldest idle LRU) before constructing v3's player.
        v3.applyPlayControl(VideoPlayControl.PREPLAY)
        assertEquals(2, budget.activeInlineCount())
        assertEquals(1, p1.releaseCalls)
        assertFalse(v1.hasLivePlayerForTest)
        assertTrue(v1.uiState.isDormant)
        assertEquals(1, firstFramesV1, "eviction must unblock spinner so Play button is interactive")
        assertEquals(PlayState.PAUSED, statesV1.last())

        // Tapping Play on dormant v1 wakes a fresh ExoPlayer, restores 7500ms offset, and evicts v2.
        v1.togglePlayOrReplay()
        val p1Recovered = createdPlayers.last()
        assertTrue(v1.hasLivePlayerForTest)
        assertFalse(v1.uiState.isDormant)
        assertEquals(7_500L, p1Recovered.lastSeekMs)
        assertEquals(1, p1Recovered.playCalls)
        assertEquals(1, p2.releaseCalls)
        assertFalse(v2.hasLivePlayerForTest)
        assertTrue(v2.uiState.isDormant)

        // 3. Backgrounding (ON_STOP) releases inline player to dormant without overwriting terminal ERROR.
        p1Recovered.dispatchError()
        assertEquals(PlayState.ERROR, statesV1.last())
        v1.pauseForHostBackground()
        assertEquals(1, p1Recovered.releaseCalls)
        assertFalse(v1.hasLivePlayerForTest)
        assertEquals(PlayState.ERROR, statesV1.last(), "dormant release must not erase PlayState.ERROR")

        v1.stop()
        v2.stop()
        v3.stop()
        assertEquals(0, budget.activeInlineCount())
    }

    private object NoopVideoCallbacks : RaftAndroidVideoView.Callbacks {
        override fun onPlayState(state: PlayState) = Unit

        override fun onPlayTime(currentMs: Long, totalMs: Long) = Unit

        override fun onFirstFrame() = Unit
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private class FakeExoPlayerHandle(
        val policy: RaftAndroidVideoBufferPolicy,
    ) {
        var playbackState: Int = Player.STATE_IDLE
        var playWhenReady: Boolean = false
        var currentPosition: Long = 0L
        var duration: Long = 30_000L
        var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        var prepareCalls: Int = 0
        var playCalls: Int = 0
        var pauseCalls: Int = 0
        var releaseCalls: Int = 0
        var lastSeekMs: Long? = null
        private val listeners = mutableListOf<Player.Listener>()

        fun dispatchError() {
            val error = PlaybackException("boom", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            listeners.toList().forEach { it.onPlayerError(error) }
        }

        val proxy: ExoPlayer =
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "FakeExoPlayer"
                    "getApplicationLooper" -> Looper.getMainLooper()
                    "getPlaybackState" -> playbackState
                    "getPlayWhenReady" -> playWhenReady
                    "setPlayWhenReady" -> {
                        playWhenReady = (args?.firstOrNull() as? Boolean) ?: false
                        null
                    }
                    "isPlaying" -> playWhenReady && playbackState == Player.STATE_READY
                    "getCurrentPosition" -> currentPosition
                    "getDuration" -> duration
                    "getBufferedPosition" -> currentPosition
                    "getPlaybackParameters" -> playbackParameters
                    "setPlaybackParameters" -> {
                        (args?.firstOrNull() as? PlaybackParameters)?.let { playbackParameters = it }
                        null
                    }
                    "getVideoSize" -> VideoSize.UNKNOWN
                    "getCurrentTracks" -> Tracks.EMPTY
                    "getCurrentCues" -> CueGroup.EMPTY_TIME_ZERO
                    "getMediaMetadata" -> MediaMetadata.EMPTY
                    "getAvailableCommands" -> Player.Commands.EMPTY
                    "isCommandAvailable" -> false
                    "addListener" -> {
                        (args?.firstOrNull() as? Player.Listener)?.let { listeners += it }
                        null
                    }
                    "removeListener" -> {
                        (args?.firstOrNull() as? Player.Listener)?.let { listeners -= it }
                        null
                    }
                    "prepare" -> {
                        prepareCalls += 1
                        null
                    }
                    "play" -> {
                        playCalls += 1
                        playWhenReady = true
                        null
                    }
                    "pause" -> {
                        pauseCalls += 1
                        playWhenReady = false
                        null
                    }
                    "release" -> {
                        releaseCalls += 1
                        null
                    }
                    "seekTo" -> {
                        val pos = args?.lastOrNull() as? Long
                        if (pos != null) {
                            lastSeekMs = pos
                            currentPosition = pos
                        }
                        null
                    }
                    else ->
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            Float::class.javaPrimitiveType -> 0f
                            Double::class.javaPrimitiveType -> 0.0
                            else -> null
                        }
                }
            } as ExoPlayer
    }

    private fun render(content: @Composable () -> Unit): View {
        val activity = activities.launch()
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager = object : Pager() {}.apply { pageData = PageData(JSONObject()) }
        activity.setContent {
            CompositionLocalProvider(
                LocalRaftSpinnerStillStep provides 0,
                LocalActivity provides RaftPageActivity(pager),
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        Box(Modifier.fillMaxWidth()) {
                            content()
                        }
                    }
                }
            }
        }
        settle()
        return assertNotNull(findComposeRoot(activity.window.decorView), "no compose root")
    }

    private fun settle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
