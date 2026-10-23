package build.raft.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.compose.LocalAsyncImagePreviewHandler
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin #20 lane E (and proj-mobile #12): a message's attachments as the web draws them
 * (MessageImageGallery, MessageAttachmentCard), light and dark.
 *
 * - `thread.message-images`: a lone 16:9 image, then galleries of two, three and four.
 * - `thread.message-files`: file cards (type chip, "kind · size" meta, comment count), a lone
 *   image that failed to load and a gallery with one failed tile (ImageOff on `fill-muted`).
 *
 * Coil's preview handler stands in for the network: each `fixture://<colour>` source decodes to
 * a flat bitmap of that colour, and `fixture://fail` fails. Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h960dp-xxhdpi")
class ThreadMessageAttachmentsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun imagesLight() = capture("thread.message-images", RaftAppAppearance.Light) { MessageImages() }

    @Test
    fun imagesDark() = capture("thread.message-images.dark", RaftAppAppearance.Dark) { MessageImages() }

    @Test
    fun filesLight() = capture("thread.message-files", RaftAppAppearance.Light) { MessageFiles() }

    @Test
    fun filesDark() = capture("thread.message-files.dark", RaftAppAppearance.Dark) { MessageFiles() }

    @OptIn(ExperimentalCoilApi::class)
    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        // The app installs its image loader at startup; without that, the first image request
        // creates Coil's default singleton and the later install throws inside a LaunchedEffect,
        // which stops recomposition (the failed-image placeholder would never appear).
        runCatching { build.raft.app.image.raftInstallImageLoader() }
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides FixtureImages,
            ) {
                RaftDesignSystemTheme { content() }
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
private val FixtureImages =
    AsyncImagePreviewHandler { _, request ->
        val source = request.data as? String
        val color = source?.removePrefix("fixture://")?.let(FixtureColors::get)
        if (color == null) {
            AsyncImagePainter.State.Error(null, ErrorResult(null, request, IllegalStateException("fixture: no image")))
        } else {
            val image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImage()
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }
    }

private val FixtureColors =
    mapOf(
        "sky" to 0xFF8EC9F0.toInt(),
        "sand" to 0xFFE9C98B.toInt(),
        "moss" to 0xFF9CC79A.toInt(),
        "rose" to 0xFFF0A5B8.toInt(),
        "lilac" to 0xFFB9A8E6.toInt(),
    )

private fun image(id: String, colour: String?, width: Int? = 1600, height: Int? = 1200) =
    RaftThreadAttachmentItem(
        id = id,
        name = "$id.png",
        mimeType = "image/png",
        sizeBytes = 240_000,
        thumbnailUrl = colour?.let { "fixture://$it" },
        width = width,
        height = height,
    )

@Composable
private fun AttachmentsColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun MessageImages() {
    AttachmentsColumn {
        ThreadMessageAttachments(listOf(image("single", "sky", 1600, 900)), alignEnd = false, onOpenAttachment = {})
        ThreadMessageAttachments(listOf(image("a", "sand"), image("b", "moss")), alignEnd = false, onOpenAttachment = {})
        ThreadMessageAttachments(listOf(image("c", "rose"), image("d", "lilac"), image("e", "sky")), alignEnd = false, onOpenAttachment = {})
        ThreadMessageAttachments(
            listOf(image("f", "moss"), image("g", "sand"), image("h", "lilac"), image("i", "rose")),
            alignEnd = false,
            onOpenAttachment = {},
        )
    }
}

@Composable
private fun MessageFiles() {
    AttachmentsColumn {
        ThreadMessageAttachments(
            listOf(
                RaftThreadAttachmentItem(id = "pdf", name = "visual-parity-notes.pdf", mimeType = "application/pdf", sizeBytes = 184_000),
                RaftThreadAttachmentItem(id = "md", name = "release-checklist.md", mimeType = "text/markdown", sizeBytes = 6_200, commentCount = 3),
                RaftThreadAttachmentItem(id = "html", name = "thread-screen-preview.html", mimeType = "text/html", sizeBytes = 32_000),
                RaftThreadAttachmentItem(id = "bin", name = "build-output.bin", sizeBytes = 2_100_000),
            ),
            alignEnd = false,
            onOpenAttachment = {},
        )
        ThreadMessageAttachments(listOf(image("broken", "fail", 1200, 900)), alignEnd = false, onOpenAttachment = {})
        ThreadMessageAttachments(listOf(image("j", "sky"), image("k", null, null, null)), alignEnd = false, onOpenAttachment = {})
    }
}
