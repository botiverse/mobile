package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.compat.ui.PlayState
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin #20 gaps 1 and 2: the inline video of a message (web `MessageVideoPreview`), light and
 * dark. Top to bottom: playable (a still frame stands in for the platform player), loading,
 * still processing, and failed (Retry / Download to View). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class ThreadInlineVideoScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun statesLight() = capture("thread.inline-video", RaftAppAppearance.Light)

    @Test
    fun statesDark() = capture("thread.inline-video.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadInlineVideoPlayer provides StillFramePlayer,
                LocalThreadInlineVideoPreviewHost provides videoHost,
            ) {
                RaftDesignSystemTheme { InlineVideoStates() }
            }
        }
}

private val videoHost =
    ThreadInlineVideoPreviewHost(
        states =
            mapOf(
                "playable" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Ready, url = "fixture://playable"),
                "loading" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Loading),
                "processing" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Processing),
                "failed" to ThreadInlineVideoPreviewUiState(ThreadInlineVideoPreviewStatus.Error),
            ),
        requestPreview = { _, _ -> },
    )

/** A still "first frame" in place of ExoPlayer: reports it at once, as a prepared player does. */
private val StillFramePlayer: ThreadInlineVideoPlayer = { _, _, onPlayStateChanged, onFirstFrame, modifier ->
    LaunchedEffect(Unit) {
        onPlayStateChanged(PlayState.PAUSED)
        onFirstFrame()
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF2B4C7E), Color(0xFF8FB8DE)))))
}

@Composable
private fun InlineVideoStates() {
    Column(
        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("playable", "loading", "processing", "failed").forEach { id ->
            ThreadInlineVideoAttachmentCard(
                attachment = RaftThreadAttachmentItem(id = id, name = "85711.mp4", mimeType = "video/mp4", sizeLabel = "2.1 MB", sizeBytes = 2_200_000),
                onDownload = {},
                onOpenPreview = {},
            )
        }
    }
}
