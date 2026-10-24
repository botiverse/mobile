package build.raft.app

import build.raft.app.transfer.RaftTransferMeter
import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.transfer.testUploadTransfer
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every upload state of a composer chip (web MessageInput chip states), image and file tiles,
 * light and dark: "Checking file…", "Uploading" at 0%, 42% with its bar, "Finishing upload…" at
 * 99 until the server confirms, and failed (alert + Retry, the app's own failure treatment).
 * The tray reads [RaftComposerUploadDisplay] only; the states come from the upload meter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ThreadComposerUploadStatesScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun uploadStatesLight() = captureComposerTray(activities, "thread.composer-upload-states", RaftAppAppearance.Light, uploadStateAttachments)

    @Test
    fun uploadStatesDark() = captureComposerTray(activities, "thread.composer-upload-states.dark", RaftAppAppearance.Dark, uploadStateAttachments)
}

private fun finishing(): RaftTransferProgress = RaftTransferMeter(bytesTotal = 100L).apply { bytes(100L) }.finishing()

private val uploadStateAttachments =
    listOf(
        RaftPendingAttachmentItem(id = "i-check", fileName = "check.png", mimeType = "image/png", status = "uploading"),
        RaftPendingAttachmentItem(
            id = "i-zero",
            fileName = "zero.png",
            mimeType = "image/png",
            status = "uploading",
            uploadTransfer = RaftTransferMeter(bytesTotal = 100L).progress,
        ),
        RaftPendingAttachmentItem(id = "i-mid", fileName = "mid.png", mimeType = "image/png", status = "uploading", uploadTransfer = testUploadTransfer(42L, 100L)),
        RaftPendingAttachmentItem(id = "i-finish", fileName = "finish.png", mimeType = "image/png", status = "uploading", uploadTransfer = finishing()),
        RaftPendingAttachmentItem(id = "i-failed", fileName = "failed.png", mimeType = "image/png", status = "failed"),
        RaftPendingAttachmentItem(id = "f-check", fileName = "checking.zip", mimeType = "application/zip", sizeBytes = 8_400_000L, status = "uploading"),
        RaftPendingAttachmentItem(
            id = "f-zero",
            fileName = "zero.zip",
            mimeType = "application/zip",
            sizeBytes = 8_400_000L,
            status = "uploading",
            uploadTransfer = RaftTransferMeter(bytesTotal = 8_400_000L).progress,
        ),
        RaftPendingAttachmentItem(
            id = "f-mid",
            fileName = "mid.zip",
            mimeType = "application/zip",
            sizeBytes = 8_400_000L,
            status = "uploading",
            uploadTransfer = testUploadTransfer(42L, 100L),
        ),
        RaftPendingAttachmentItem(
            id = "f-finish",
            fileName = "finishing.zip",
            mimeType = "application/zip",
            sizeBytes = 8_400_000L,
            status = "uploading",
            uploadTransfer = finishing(),
        ),
        RaftPendingAttachmentItem(id = "f-failed", fileName = "failed.zip", mimeType = "application/zip", sizeBytes = 8_400_000L, status = "failed"),
    )
