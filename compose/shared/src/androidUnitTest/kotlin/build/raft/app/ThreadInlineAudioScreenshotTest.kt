package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * artin #20 gap 8: the inline audio card of a message (web `InlineAudioPlayer` in
 * `MessageAttachmentCard`), light and dark. Top to bottom: idle (paused at 0:00 of 2:15),
 * playing (0:42 of 2:15), loading, and failed (Retry / Download). A scripted player stands in
 * for ExoPlayer. Reports land in `build/reports/home-screenshots/`; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class ThreadInlineAudioScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun statesLight() = capture("thread.inline-audio", RaftAppAppearance.Light)

    @Test
    fun statesDark() = capture("thread.inline-audio.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadInlineAudioPlayer provides ScriptedAudioPlayer,
                LocalThreadInlineAudioPreviewHost provides audioHost,
            ) {
                RaftDesignSystemTheme { InlineAudioStates() }
            }
        }
}

private val audioHost =
    ThreadInlineAudioPreviewHost(
        states =
            mapOf(
                "idle" to ThreadInlineAudioPreviewUiState(ThreadInlineAudioPreviewStatus.Ready, url = "fixture://idle"),
                "playing" to ThreadInlineAudioPreviewUiState(ThreadInlineAudioPreviewStatus.Ready, url = "fixture://playing"),
                "loading" to ThreadInlineAudioPreviewUiState(ThreadInlineAudioPreviewStatus.Loading),
                "failed" to ThreadInlineAudioPreviewUiState(ThreadInlineAudioPreviewStatus.Error),
            ),
        requestPreview = { _, _ -> },
    )

/** Reports a fixed state per source, as a prepared player would. */
private val ScriptedAudioPlayer: ThreadInlineAudioPlayer = { source, _, _, onPlayStateChanged, onPlayTimeChanged, _ ->
    LaunchedEffect(source) {
        when (source) {
            "fixture://playing" -> {
                onPlayStateChanged(PlayState.PLAYING)
                onPlayTimeChanged(42_000, 135_000)
            }
            else -> {
                onPlayStateChanged(PlayState.PAUSED)
                onPlayTimeChanged(0, 135_000)
            }
        }
    }
}

@Composable
private fun InlineAudioStates() {
    Column(
        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("idle", "playing", "loading", "failed").forEach { id ->
            ThreadInlineAudioAttachmentCard(
                attachment = RaftThreadAttachmentItem(id = id, name = "standup-notes-2026-10-23.m4a", mimeType = "audio/mp4", sizeLabel = "1.4 MB", sizeBytes = 1_400_000),
                onDownload = {},
            )
        }
    }
}
