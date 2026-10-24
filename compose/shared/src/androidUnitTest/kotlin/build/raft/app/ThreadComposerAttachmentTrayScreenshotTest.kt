package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's attachment tray (web MessageInput on raft-ui ComposerAttachment), light and
 * dark, at the composer's 321dp: an image with no preview yet, a ready file, a file uploading at
 * 42% and one before its first byte, an image waiting for its first byte, and a file and an image
 * whose upload failed, under the web's overlays. Each has its remove sticker. Fixed fixture data;
 * spinners held still. Reports land in `build/reports/home-screenshots/`; verify /
 * record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
class ThreadComposerAttachmentTrayScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun attachmentTrayLight() = capture("thread.composer-attachments", RaftAppAppearance.Light)

    @Test
    fun attachmentTrayDark() = capture("thread.composer-attachments.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftSpinnerStillStep provides 0) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        Box(Modifier.width(321.dp)) {
                            ComposerAttachmentTray(
                                attachments = trayAttachments,
                                enabled = true,
                                previewEnabled = true,
                                onEvent = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
}

private val trayAttachments =
    listOf(
        RaftPendingAttachmentItem(id = "image", fileName = "launch-banner.png", mimeType = "image/png", sizeBytes = 284_000L),
        RaftPendingAttachmentItem(id = "notes", fileName = "release-notes-october-final.pdf", mimeType = "application/pdf", sizeBytes = 1_240_000L),
        RaftPendingAttachmentItem(
            id = "logs",
            fileName = "android-smoke-log.zip",
            mimeType = "application/zip",
            sizeBytes = 8_400_000L,
            status = "uploading",
            uploadProgress = 42,
        ),
        RaftPendingAttachmentItem(
            id = "recording",
            fileName = "ScreenRecording_10-24.mov",
            mimeType = "video/quicktime",
            sizeBytes = 31_000_000L,
            status = "uploading",
        ),
        RaftPendingAttachmentItem(id = "photo", fileName = "whiteboard.jpg", mimeType = "image/jpeg", status = "uploading"),
        RaftPendingAttachmentItem(
            id = "trace",
            fileName = "oversized-trace.jsonl",
            mimeType = "application/json",
            sizeBytes = 52_000_000L,
            status = "failed",
        ),
        RaftPendingAttachmentItem(id = "shot", fileName = "crash-screenshot.png", mimeType = "image/png", status = "failed"),
    )
