package build.raft.app

import build.raft.app.transfer.testUploadTransfer
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's pending image tile through an upload, as the tray draws it (64dp tiles, the
 * remove sticker on the top-end corner): done (no overlay), uploading at 42%, finishing (the
 * bar held filled at 99%), failed with Retry (the design-system goldens cover the file tile). artin 11-11 (Android
 * 1.23, dark, zh-CN): "正在完成上传…" spilled past the image tile, cut at the screen edge, over
 * the thumbnail. Every label must stay inside its tile, in English and Chinese, light and dark,
 * and at font scale 1.3. CJK goldens are recorded by CI. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadComposerUploadTileScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "en-w390dp-h200dp-xxhdpi")
    fun uploadTileLight() = capture("thread.composer-upload-tile", RaftAppAppearance.Light)

    @Test
    @Config(sdk = [34], qualifiers = "en-w390dp-h200dp-xxhdpi")
    fun uploadTileDark() = capture("thread.composer-upload-tile.dark", RaftAppAppearance.Dark)

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h200dp-xxhdpi")
    fun uploadTileChineseLight() = capture("thread.composer-upload-tile.zh", RaftAppAppearance.Light)

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h200dp-xxhdpi")
    fun uploadTileChineseDark() = capture("thread.composer-upload-tile.zh.dark", RaftAppAppearance.Dark)

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w390dp-h200dp-xxhdpi")
    fun uploadTileChineseLargeFont() =
        capture("thread.composer-upload-tile.zh.font-130", RaftAppAppearance.Dark, fontScale = 1.3f)

    private fun capture(name: String, appearance: RaftAppAppearance, fontScale: Float = 1f) =
        captureComposerTray(activities, name, appearance, uploadTiles, fontScale)
}

private val uploadTiles =
    listOf(
        RaftPendingAttachmentItem(id = "done", fileName = "done.png", mimeType = "image/png", sizeBytes = 284_000L),
        RaftPendingAttachmentItem(
            id = "uploading",
            fileName = "uploading.png",
            mimeType = "image/png",
            status = "uploading",
            uploadTransfer = testUploadTransfer(42L, 100L),
        ),
        RaftPendingAttachmentItem(
            id = "finishing",
            fileName = "finishing.jpg",
            mimeType = "image/jpeg",
            status = "uploading",
            uploadTransfer = testUploadTransfer(100L, 100L),
        ),
        RaftPendingAttachmentItem(id = "failed", fileName = "failed.png", mimeType = "image/png", status = "failed"),
    )
